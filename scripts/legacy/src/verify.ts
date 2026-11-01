import type { Client } from "pg";
import type { LoadResult } from "./load";
import { type Plan, plannedEffectiveLocations } from "./plan";

export interface Check { name: string; expected: string | number; actual: string | number; ok: boolean; note?: string }

/**
 * Reads the database back (inside the same transaction) and compares it with the legacy snapshot as planned.
 * "Status, location and container contents equal the legacy snapshot" is the acceptance test for the import.
 */
export async function verifyImport(db: Client, plan: Plan, load: LoadResult): Promise<Check[]> {
  const checks: Check[] = [];
  const add = (name: string, expected: string | number, actual: string | number, note?: string) => checks.push({ name, expected, actual, ok: expected === actual, note });
  const ids = plan.assets.map((a) => a.id);

  const present = await db.query<{ n: string }>("select count(*) n from public.assets where id = any($1::uuid[])", [ids]);
  add("assets + containers present", plan.assets.length, Number(present.rows[0]!.n));

  const byStatus = await db.query<{ status: string; n: string }>("select status::text status, count(*) n from public.assets where id = any($1::uuid[]) group by 1", [ids]);
  const actualStatus = Object.fromEntries(byStatus.rows.map((r) => [r.status, Number(r.n)]));
  const plannedStatus: Record<string, number> = {};
  for (const a of plan.assets) plannedStatus[a.status] = (plannedStatus[a.status] ?? 0) + 1;
  for (const s of new Set([...Object.keys(plannedStatus), ...Object.keys(actualStatus)])) add(`assets with status ${s}`, plannedStatus[s] ?? 0, actualStatus[s] ?? 0);

  // container contents
  const contents = await db.query<{ parent: string; n: string }>("select parent_asset_id::text parent, count(*) n from public.assets where parent_asset_id = any($1::uuid[]) group by 1", [ids]);
  const actualContents = new Map(contents.rows.map((r) => [r.parent, Number(r.n)]));
  for (const c of plan.assets.filter((a) => a.isContainer)) {
    const expected = plan.assets.filter((a) => a.parentAssetId === c.id).length;
    add(`contents of container ${c.legacyId} (${c.name})`, expected, actualContents.get(c.id) ?? 0);
  }

  // placement and effective location, per asset; strict for rows this run created, informational for older rows
  const rows = await db.query<{ id: string; parent_asset_id: string | null; parent_location_id: string | null; eff: string | null }>(
    `select a.id::text id, a.parent_asset_id::text parent_asset_id, a.parent_location_id::text parent_location_id, e.location_id::text eff
     from public.assets a left join public.asset_effective_location e on e.asset_id = a.id where a.id = any($1::uuid[])`, [ids]);
  const dbRow = new Map(rows.rows.map((r) => [r.id, r]));
  const eff = plannedEffectiveLocations(plan);
  let placementBad = 0, effectiveBad = 0, olderDiffers = 0;
  for (const a of plan.assets) {
    const r = dbRow.get(a.id);
    const placementOk = !!r && r.parent_asset_id === a.parentAssetId && r.parent_location_id === a.parentLocationId;
    const effOk = !!r && (r.eff ?? null) === (eff.get(a.id) ?? null);
    if (load.newAssetIds.has(a.id)) { if (!placementOk) placementBad++; if (!effOk) effectiveBad++; }
    else if (!placementOk || !effOk) olderDiffers++;
  }
  add("assets whose placement differs from legacy (new rows)", 0, placementBad);
  add("assets whose effective location differs from legacy (new rows)", 0, effectiveBad);
  if (olderDiffers > 0) checks.push({ name: "pre-existing rows that differ from legacy (edited since a previous import?)", expected: "informational", actual: olderDiffers, ok: true });

  const open = await db.query<{ n: string }>("select count(*) n from public.checkout_items where returned_at is null and asset_id = any($1::uuid[])", [ids]);
  add("open checkout lines", plan.checkoutItems.length, Number(open.rows[0]!.n));

  const tagRows = await db.query<{ n: string }>(
    "select count(*) n from public.tags where value = any($1::text[])", [plan.tags.map((t) => t.value)]);
  add("legacy tags present", plan.tags.length, Number(tagRows.rows[0]!.n));

  const locs = await db.query<{ n: string }>("select count(*) n from public.locations where id = any($1::uuid[])", [plan.locations.map((l) => l.id)]);
  add("locations present", plan.locations.length, Number(locs.rows[0]!.n), load.problems.some((p) => p.code === "location_not_imported") ? "some locations were not imported, see problems" : undefined);

  const evs = await db.query<{ type: string; n: string }>("select type::text type, count(*) n from public.asset_events where id = any($1::uuid[]) group by 1", [plan.events.map((e) => e.id)]);
  const actualEv = Object.fromEntries(evs.rows.map((r) => [r.type, Number(r.n)]));
  const plannedEv: Record<string, number> = {};
  for (const e of plan.events) plannedEv[e.type] = (plannedEv[e.type] ?? 0) + 1;
  for (const t of new Set([...Object.keys(plannedEv), ...Object.keys(actualEv)])) add(`events of type ${t}`, plannedEv[t] ?? 0, actualEv[t] ?? 0);

  const aud = await db.query<{ n: string }>("select count(*) n from public.audits where id = any($1::uuid[])", [plan.audits.map((a) => a.id)]);
  add("audits present", plan.audits.length, Number(aud.rows[0]!.n));
  const lines = await db.query<{ n: string }>("select count(*) n from public.audit_lines where id = any($1::uuid[])", [plan.auditLines.map((l) => l.id)]);
  add("audit lines present", plan.auditLines.length, Number(lines.rows[0]!.n));

  for (const p of load.problems) checks.push({ name: `problem: ${p.code}`, expected: "none", actual: p.message, ok: false });
  return checks;
}
