import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { ExportError, findCredentialColumns, loadExport, validateColumns } from "../src/read";
import { DEFAULT_COLUMNS, FILES, mergeColumns, type LegacyExport } from "../src/types";

function dirWith(files: Record<string, unknown>) {
  const dir = mkdtempSync(join(tmpdir(), "legacy-"));
  for (const [name, value] of Object.entries(files)) writeFileSync(join(dir, name), JSON.stringify(value));
  return dir;
}

const empty = (): LegacyExport => ({ assets: [], containers: [], locations: [], events: [], containerEvents: [], audits: [], auditItems: [] });

describe("credential guard", () => {
  it("flags credential-like column names", () => {
    expect(findCredentialColumns([{ id: 1, pin: "1234", name: "x" }])).toEqual(["pin"]);
    expect(findCredentialColumns([{ password_hash: "x", api_key: "y", user_token: "z", spinning: 1 }]).sort()).toEqual(["api_key", "password_hash", "user_token"]);
    expect(findCredentialColumns([{ id: 1, name: "Bin", pinned: true, shipping_note: "x" }])).toEqual([]);
  });

  it("refuses to load an export that contains them", () => {
    const dir = dirWith({ [FILES.assets]: [{ id: 1, name: "x", admin_pin: "1" }], [FILES.locations]: [] });
    expect(() => loadExport(dir)).toThrow(/credential-like columns \(admin_pin\)/);
  });
});

describe("loadExport", () => {
  it("requires the assets and locations files", () => {
    expect(() => loadExport(dirWith({ [FILES.assets]: [] }))).toThrow(ExportError);
  });

  it("treats other missing files and json null as empty, with a warning", () => {
    const { data, issues } = loadExport(dirWith({ [FILES.assets]: null, [FILES.locations]: [] }));
    expect(data.assets).toEqual([]);
    expect(data.events).toEqual([]);
    expect(issues.some((i) => i.code === "missing_file" && i.table === FILES.events)).toBe(true);
  });

  it("rejects non-array and invalid JSON", () => {
    expect(() => loadExport(dirWith({ [FILES.assets]: { a: 1 }, [FILES.locations]: [] }))).toThrow(/array/);
  });
});

describe("validateColumns", () => {
  const row = { id: "1", asset_id: "110164", name: "Mic", status: "available" };

  it("stops with the columns that do exist when a required one is missing", () => {
    const data = { ...empty(), assets: [{ id: "1", tag: "x", name: "Mic", status: "available" }] };
    expect(() => validateColumns(data, DEFAULT_COLUMNS)).toThrow(/expected column\(s\) not found: tag -> "asset_id".*Columns present: id, name, status, tag/);
  });

  it("accepts a column override", () => {
    const data = { ...empty(), assets: [{ id: "1", tag: "x", name: "Mic", status: "available" }] };
    const columns = mergeColumns({ assets: { tag: "tag" } });
    expect(() => validateColumns(data, columns)).not.toThrow();
  });

  it("reports optional columns that are absent, without failing", () => {
    const issues = validateColumns({ ...empty(), assets: [row] }, DEFAULT_COLUMNS);
    expect(issues[0]!.code).toBe("optional_column_missing");
    expect(issues[0]!.message).toContain("photos");
  });

  it("skips empty tables", () => {
    expect(validateColumns(empty(), DEFAULT_COLUMNS)).toEqual([]);
  });
});
