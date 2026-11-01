import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { type ColumnMap, FILES, type Issue, type LegacyExport, REQUIRED, type Row, type TableKey } from "./types";

/** Column names that suggest secrets. The export must never contain them (see export.sql). */
const CREDENTIAL_LIKE = /(^|_)(pin|passw(or)?d|passcode|secret|token|hash|salt|credential|api_?key)(_|$)/i;

export class ExportError extends Error {}

export function findCredentialColumns(rows: Row[]): string[] {
  const keys = new Set<string>();
  for (const row of rows.slice(0, 50)) for (const k of Object.keys(row)) keys.add(k);
  return [...keys].filter((k) => CREDENTIAL_LIKE.test(k));
}

export function loadExport(dir: string): { data: LegacyExport; issues: Issue[] } {
  const issues: Issue[] = [];
  const data = {} as LegacyExport;
  for (const key of Object.keys(FILES) as TableKey[]) {
    const path = join(dir, FILES[key]);
    if (!existsSync(path)) {
      if (key === "assets" || key === "locations") throw new ExportError(`Missing required export file ${path}`);
      issues.push({ severity: "warn", code: "missing_file", table: FILES[key], message: `${FILES[key]} not found; treating as empty` });
      data[key] = [];
      continue;
    }
    let parsed: unknown;
    try {
      parsed = JSON.parse(readFileSync(path, "utf8"));
    } catch (e) {
      throw new ExportError(`${path} is not valid JSON: ${(e as Error).message}`);
    }
    // json_agg over zero rows is null in some setups; accept it as empty.
    const rows = parsed === null ? [] : parsed;
    if (!Array.isArray(rows)) throw new ExportError(`${path} must contain a JSON array of rows`);
    const bad = findCredentialColumns(rows as Row[]);
    if (bad.length > 0) {
      throw new ExportError(
        `${path} has credential-like columns (${bad.join(", ")}). Credentials must never be exported or migrated: re-export without them.`,
      );
    }
    data[key] = rows as Row[];
  }
  return { data, issues };
}

/**
 * Every required column must exist on the first row of each non-empty table. A wrong column map therefore stops
 * the import with the list of columns that DO exist, instead of silently importing nulls.
 */
export function validateColumns(data: LegacyExport, columns: ColumnMap): Issue[] {
  const issues: Issue[] = [];
  for (const key of Object.keys(REQUIRED) as TableKey[]) {
    const first = data[key][0];
    if (!first) continue;
    const present = new Set(Object.keys(first));
    const map = columns[key] as Record<string, string>;
    const missingRequired = (REQUIRED[key] as string[]).filter((field) => !present.has(map[field]!));
    if (missingRequired.length > 0) {
      const expected = missingRequired.map((f) => `${f} -> "${map[f]}"`).join(", ");
      throw new ExportError(
        `Table ${key}: expected column(s) not found: ${expected}. Columns present: ${[...present].sort().join(", ")}. ` +
          `Fix with --columns (see docs/legacy-migration.md).`,
      );
    }
    const missingOptional = Object.entries(map).filter(([field, col]) => !(REQUIRED[key] as string[]).includes(field) && !present.has(col));
    if (missingOptional.length > 0) {
      issues.push({
        severity: "info", code: "optional_column_missing", table: key,
        message: `Optional column(s) not present, imported as empty: ${missingOptional.map(([f, c]) => `${f} ("${c}")`).join(", ")}`,
      });
    }
  }
  return issues;
}
