import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { join, resolve } from "node:path";
import { ExportError } from "./read";
import { renderMarkdown } from "./report";
import { RefusedError, run } from "./run";
import { type LocationKindOverrides, mergeColumns } from "./types";

const USAGE = `Usage: npm run import -- --export-dir <dir> [options]

  --export-dir <dir>      folder with the JSON files written by export.sql (required)
  --plan-only             validate the export and print the report; no database is touched
  --database-url <url>    target database (or set DATABASE_URL). A database owner connection, never the legacy project
  --dry-run               do everything in a transaction, print the report, ROLL BACK
  --commit                same, but COMMIT if every check passes (otherwise rolls back)
  --report-dir <dir>      also write reconciliation.md and reconciliation.json here
  --kinds <file.json>     approved location kinds: {"<legacy id or code>": "cabinet", ...}
  --columns <file.json>   column overrides: {"assets": {"tag": "asset_tag"}, ...}

Exactly one of --plan-only, --dry-run, --commit is required. Exit code 0 = pass, 1 = checks failed, 2 = bad input.
`;

function arg(name: string): string | undefined {
  const i = process.argv.indexOf(name);
  return i >= 0 ? process.argv[i + 1] : undefined;
}
const flag = (name: string) => process.argv.includes(name);
const readJson = <T>(path: string | undefined): T | undefined => (path ? (JSON.parse(readFileSync(path, "utf8")) as T) : undefined);

async function main() {
  const exportDir = arg("--export-dir");
  const modes = ["--plan-only", "--dry-run", "--commit"].filter(flag);
  if (!exportDir || modes.length !== 1) { console.error(USAGE); process.exit(2); }
  const databaseUrl = flag("--plan-only") ? null : arg("--database-url") ?? process.env.DATABASE_URL ?? null;
  if (!flag("--plan-only") && !databaseUrl) { console.error("No database: pass --database-url or set DATABASE_URL (or use --plan-only).\n"); console.error(USAGE); process.exit(2); }

  const { reconciliation } = await run({
    exportDir: resolve(exportDir),
    columns: mergeColumns(readJson(arg("--columns"))),
    kindOverrides: readJson<LocationKindOverrides>(arg("--kinds")),
    databaseUrl,
    commit: flag("--commit"),
  });
  const markdown = renderMarkdown(reconciliation);
  console.log(markdown);
  const reportDir = arg("--report-dir");
  if (reportDir) {
    mkdirSync(reportDir, { recursive: true });
    writeFileSync(join(reportDir, "reconciliation.md"), markdown);
    writeFileSync(join(reportDir, "reconciliation.json"), JSON.stringify(reconciliation, null, 2));
    console.log(`Report written to ${reportDir}`);
  }
  process.exit(reconciliation.passed === false ? 1 : 0);
}

main().catch((e) => {
  if (e instanceof ExportError || e instanceof RefusedError) { console.error(`\nERROR: ${e.message}\n`); process.exit(2); }
  console.error(e);
  process.exit(2);
});
