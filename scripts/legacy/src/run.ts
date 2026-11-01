import { Client } from "pg";
import { loadPlan } from "./load";
import { buildPlan, type Plan } from "./plan";
import { loadExport, validateColumns } from "./read";
import { buildReconciliation, type Reconciliation } from "./report";
import { type ColumnMap, type Issue, type LocationKindOverrides } from "./types";
import { verifyImport } from "./verify";

/** The old Supabase project. Importing INTO it would be a disaster, so refuse outright. */
const LEGACY_PROJECT_REF = "vxgtywdqijtfnkgyubkh";

export interface RunOptions {
  exportDir: string;
  columns: ColumnMap;
  kindOverrides?: LocationKindOverrides;
  /** null = plan only: validate the export and print the report without touching any database. */
  databaseUrl: string | null;
  commit: boolean;
  now?: Date;
}

export class RefusedError extends Error {}

export async function run(opts: RunOptions): Promise<{ plan: Plan; reconciliation: Reconciliation; exportIssues: Issue[] }> {
  const { data, issues: readIssues } = loadExport(opts.exportDir);
  const columnIssues = validateColumns(data, opts.columns);
  const plan = buildPlan(data, { columns: opts.columns, kindOverrides: opts.kindOverrides, now: opts.now });
  plan.issues.unshift(...readIssues, ...columnIssues);

  if (!opts.databaseUrl) return { plan, reconciliation: buildReconciliation(plan, "plan-only", []), exportIssues: [...readIssues, ...columnIssues] };
  if (opts.databaseUrl.includes(LEGACY_PROJECT_REF)) throw new RefusedError("Refusing to import into the legacy project's database.");

  const db = new Client({ connectionString: opts.databaseUrl });
  await db.connect();
  try {
    await db.query("begin");
    const importedAt = opts.now ?? new Date();
    const load = await loadPlan(db, plan, importedAt);
    const checks = await verifyImport(db, plan, load);
    const passed = checks.every((c) => c.ok);
    const willCommit = opts.commit && passed;
    await db.query(willCommit ? "commit" : "rollback");
    const mode = willCommit ? "committed" : opts.commit ? "rolled-back" : "dry-run";
    return { plan, reconciliation: buildReconciliation(plan, mode, checks, load), exportIssues: [...readIssues, ...columnIssues] };
  } catch (e) {
    await db.query("rollback").catch(() => undefined);
    throw e;
  } finally {
    await db.end();
  }
}
