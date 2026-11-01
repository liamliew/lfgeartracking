import { mkdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { buildFixture } from "./fixtures";
import { FILES, type TableKey } from "./types";

// npm run fixtures -- <out-dir>   writes a fake export you can dry-run the importer against.
const out = process.argv[2];
if (!out) { console.error("usage: npm run fixtures -- <out-dir>"); process.exit(2); }
mkdirSync(out, { recursive: true });
const data = buildFixture();
for (const key of Object.keys(FILES) as TableKey[]) writeFileSync(join(out, FILES[key]), JSON.stringify(data[key], null, 1));
console.log(`wrote ${Object.keys(FILES).length} files to ${out}`);
