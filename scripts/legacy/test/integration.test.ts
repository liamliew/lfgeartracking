import { Client } from "pg";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { buildFixture } from "../src/fixtures";
import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { FILES, mergeColumns, type LegacyExport, type TableKey } from "../src/types";
import { RefusedError, run } from "../src/run";
import { ExportError } from "../src/read";
import { renderMarkdown } from "../src/report";

const URL = process.env.LEGACY_TEST_DB_URL;

function writeExport(data: LegacyExport): string {
  const dir = mkdtempSync(join(tmpdir(), "legacy-export-"));
  for (const key of Object.keys(FILES) as TableKey[]) writeFileSync(join(dir, FILES[key]), JSON.stringify(data[key]));
  return dir;
}

// These tests share one database on purpose and run in order: dry runs first (they must leave nothing behind),
// then a real import, then a re-run.
describe.skipIf(!URL)("import into a real database", () => {
  let db: Client;
  const exportDir = writeExport(buildFixture());
  const opts = (commit: boolean, dir = exportDir) => ({
    exportDir: dir, columns: mergeColumns(undefined), databaseUrl: URL!, commit, now: new Date("2026-11-01T00:00:00Z"),
  });
  const count = async (sql: string, params: unknown[] = []) => Number((await db.query(sql, params)).rows[0].n);

  beforeAll(async () => { db = new Client({ connectionString: URL }); await db.connect(); });
  afterAll(async () => { await db.end(); });

  it("a dry run passes every check and leaves nothing behind", async () => {
    const before = await count("select count(*) n from public.assets");
    const { reconciliation } = await run(opts(false));
    expect(reconciliation.mode).toBe("dry-run");
    const failed = reconciliation.checks.filter((c) => !c.ok);
    expect(failed).toEqual([]);
    expect(reconciliation.passed).toBe(true);
    expect(await count("select count(*) n from public.assets")).toBe(before);
    expect(await count("select count(*) n from public.legacy_id_map")).toBe(0);
  }, 120_000);

  it("a location code that already exists is reported and the run fails safe", async () => {
    const existing = await count("select count(*) n from public.locations where code = 'L00001'");
    if (existing === 0) return; // needs the seed data
    const data = buildFixture();
    (data.locations[0] as Record<string, unknown>).code = "L00001";
    const { reconciliation } = await run(opts(true, writeExport(data)));
    expect(reconciliation.passed).toBe(false);
    expect(reconciliation.mode).toBe("rolled-back");
    expect(reconciliation.checks.some((c) => c.name.startsWith("problem: location_not_imported"))).toBe(true);
    expect(await count("select count(*) n from public.legacy_id_map")).toBe(0);
  }, 120_000);

  it("refuses to import into the legacy project", async () => {
    await expect(run({ ...opts(false), databaseUrl: "postgresql://x:y@db.vxgtywdqijtfnkgyubkh.supabase.co:5432/postgres" })).rejects.toThrow(RefusedError);
  });

  it("stops before touching the database when a column does not match", async () => {
    await expect(run({ ...opts(false), columns: mergeColumns({ assets: { tag: "no_such_column" } }) })).rejects.toThrow(ExportError);
    expect(await count("select count(*) n from public.legacy_id_map")).toBe(0);
  });

  let firstAssetId = "";
  it("a real import commits and the new system matches the legacy snapshot", async () => {
    const { plan, reconciliation } = await run(opts(true));
    expect(reconciliation.mode).toBe("committed");
    expect(reconciliation.checks.filter((c) => !c.ok)).toEqual([]);
    firstAssetId = plan.assets[0]!.id;

    expect(await count("select count(*) n from public.assets where custom ? 'legacy_id'")).toBe(83);
    expect(await count("select count(*) n from public.assets where custom ? 'legacy_id' and is_container")).toBe(6);
    expect(await count("select count(*) n from public.assets where custom->>'legacy_table' = 'inventory_assets' and status = 'checked_out'")).toBe(23);
    expect(await count("select count(*) n from public.legacy_id_map where new_table = 'assets'")).toBe(83);
    // each checked-out asset has exactly one open line
    expect(await count("select count(*) n from public.checkout_items i join public.assets a on a.id = i.asset_id where a.custom ? 'legacy_id' and i.returned_at is null")).toBe(plan.checkoutItems.length);
  }, 120_000);

  it("old labels keep working: dash-separated NFC ids and numeric barcodes are tags", async () => {
    expect(await count("select count(*) n from public.tags where kind = 'nfc' and value ~ '^[0-9A-F]+$' and asset_id in (select id from public.assets where custom ? 'legacy_id')")).toBeGreaterThan(20);
    expect(await count("select count(*) n from public.tags where value = '110001' and kind = 'barcode'")).toBe(1);
    expect(await count("select count(*) n from public.tags t join public.locations l on l.id = t.location_id where t.value = 'SAV1'")).toBe(1);
  });

  it("history keeps its original timestamps and who did it", async () => {
    expect(await count("select count(*) n from public.asset_events where device_id = 'legacy-import'")).toBeGreaterThan(800);
    expect(await count("select count(*) n from public.asset_events where device_id = 'legacy-import' and payload ? 'legacy_performer'")).toBeGreaterThan(100);
    expect(await count("select count(*) n from public.asset_events where device_id = 'legacy-import' and occurred_at < '2026-06-01'")).toBeGreaterThan(100);
  });

  it("the container tree is derived: moving a container moved its contents' effective location", async () => {
    const r = await db.query(
      `select count(*)::int n from public.assets a join public.asset_effective_location e on e.asset_id = a.id
       where a.custom ? 'legacy_id' and a.parent_asset_id is not null and e.container_depth >= 1`);
    expect(r.rows[0].n).toBeGreaterThan(15);
  });

  it("running it again changes nothing and does not overwrite later edits", async () => {
    await db.query("update public.assets set name = 'Renamed after import' where id = $1", [firstAssetId]);
    const events = await count("select count(*) n from public.asset_events where device_id = 'legacy-import'");
    const assets = await count("select count(*) n from public.assets");
    const { reconciliation } = await run(opts(true));
    expect(reconciliation.mode).toBe("committed");
    expect(Object.values(reconciliation.inserted).reduce((a, b) => a + b, 0)).toBe(0);
    expect(await count("select count(*) n from public.asset_events where device_id = 'legacy-import'")).toBe(events);
    expect(await count("select count(*) n from public.assets")).toBe(assets);
    expect(await count("select count(*) n from public.assets where id = $1 and name = 'Renamed after import'", [firstAssetId])).toBe(1);
  }, 120_000);

  it("the markdown report has the sections you need to approve the import", async () => {
    const { reconciliation } = await run(opts(false));
    const md = renderMarkdown(reconciliation);
    for (const heading of ["Rows in vs out", "Checks against the legacy snapshot", "Containers and their contents", "Issues to review", "Probable duplicates", "Location kind proposal"]) {
      expect(md).toContain(heading);
    }
    expect(md).toContain("**PASS**");
  }, 120_000);
});
