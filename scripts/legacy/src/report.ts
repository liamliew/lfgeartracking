import type { Check } from "./verify";
import type { LoadResult } from "./load";
import type { Plan } from "./plan";
import { LEGACY_TABLE } from "./types";

export interface Reconciliation {
  mode: "plan-only" | "dry-run" | "committed" | "rolled-back";
  /** null for a plan-only run: nothing was checked against a database, so there is no pass to claim. */
  passed: boolean | null;
  generatedAt: string;
  counts: { table: string; legacy: number; planned: number; note: string }[];
  checks: Check[];
  inserted: Record<string, number>;
  issuesByCode: { code: string; severity: string; count: number; examples: string[] }[];
  assetsByStatus: Record<string, number>;
  containers: { legacyId: string; name: string; contents: number }[];
  resolution: { containerRefs: Record<string, number>; assetRefs: Record<string, number>; locationRefs: Record<string, number> };
  locationKindProposal: { code: string; name: string; parent: string | null; kind: string; reason: string }[];
  probableDuplicates: Plan["report"]["probableDuplicates"];
  photos: { assetsWithPhotos: number; urls: number };
  categoriesToClean: Plan["report"]["categoriesToClean"];
}

const SEVERITY_RANK: Record<string, number> = { error: 0, warn: 1, info: 2 };

export function buildReconciliation(plan: Plan, mode: Reconciliation["mode"], checks: Check[], load?: LoadResult): Reconciliation {
  const planned: Record<string, number> = {
    [LEGACY_TABLE.assets]: plan.assets.filter((a) => !a.isContainer).length, [LEGACY_TABLE.containers]: plan.assets.filter((a) => a.isContainer).length,
    [LEGACY_TABLE.locations]: plan.locations.length, [LEGACY_TABLE.events]: plan.events.filter((e) => e.payload.legacy && (e.payload.legacy as { table: string }).table === LEGACY_TABLE.events).length,
    [LEGACY_TABLE.containerEvents]: plan.events.filter((e) => (e.payload.legacy as { table: string }).table === LEGACY_TABLE.containerEvents).length,
    [LEGACY_TABLE.audits]: plan.audits.length, [LEGACY_TABLE.auditItems]: plan.auditLines.length,
  };
  const counts = Object.entries(plan.report.legacyCounts).map(([table, legacy]) => ({
    table, legacy, planned: planned[table] ?? 0, note: legacy === (planned[table] ?? 0) ? "" : `${legacy - (planned[table] ?? 0)} skipped, see issues`,
  }));
  const byCode = new Map<string, { severity: string; items: string[] }>();
  for (const i of plan.issues) {
    const e = byCode.get(i.code) ?? { severity: i.severity, items: [] };
    e.items.push(`${i.table}${i.legacyId ? ` ${i.legacyId}` : ""}: ${i.message}`);
    byCode.set(i.code, e);
  }
  const assetsByStatus: Record<string, number> = {};
  for (const a of plan.assets) assetsByStatus[a.status] = (assetsByStatus[a.status] ?? 0) + 1;
  const nameOf = new Map(plan.locations.map((l) => [l.id, l.name]));
  return {
    mode, passed: mode === "plan-only" ? null : checks.every((c) => c.ok),
    generatedAt: new Date().toISOString(), counts, checks, inserted: load?.inserted ?? {},
    issuesByCode: [...byCode].map(([code, v]) => ({ code, severity: v.severity, count: v.items.length, examples: v.items.slice(0, 5) })).sort((a, b) => SEVERITY_RANK[a.severity]! - SEVERITY_RANK[b.severity]! || b.count - a.count),
    assetsByStatus,
    containers: plan.assets.filter((a) => a.isContainer).map((c) => ({ legacyId: c.legacyId, name: c.name, contents: plan.assets.filter((a) => a.parentAssetId === c.id).length })),
    resolution: { containerRefs: plan.report.containerRefResolvedBy, assetRefs: plan.report.assetRefResolvedBy, locationRefs: plan.report.locationRefResolvedBy },
    locationKindProposal: plan.locations.map((l) => ({ code: l.code, name: l.name, parent: l.parentId ? nameOf.get(l.parentId) ?? null : null, kind: l.kind, reason: l.kindReason })),
    probableDuplicates: plan.report.probableDuplicates,
    photos: { assetsWithPhotos: plan.report.photos.length, urls: plan.report.photos.reduce((n, p) => n + p.urls.length, 0) },
    categoriesToClean: plan.report.categoriesToClean,
  };
}

export function renderMarkdown(r: Reconciliation): string {
  const L: string[] = [];
  const h = (t: string) => L.push("", `## ${t}`, "");
  L.push(`# Legacy import reconciliation (${r.mode})`, "", `Result: **${r.passed === null ? "NOT CHECKED (plan only: no database was used, so nothing was compared)" : r.passed ? "PASS" : "FAIL"}**  ·  ${r.generatedAt}`);
  h("Rows in vs out");
  L.push("| legacy table | in | imported | note |", "|---|---:|---:|---|", ...r.counts.map((c) => `| ${c.table} | ${c.legacy} | ${c.planned} | ${c.note} |`));
  if (Object.keys(r.inserted).length) { h("Created by this run"); L.push(Object.entries(r.inserted).map(([k, v]) => `${k}: ${v}`).join(" · ")); }
  h("Checks against the legacy snapshot");
  if (r.checks.length === 0) L.push("_not run (plan only)_");
  else L.push("| check | expected | actual | |", "|---|---:|---:|---|", ...r.checks.map((c) => `| ${c.name} | ${c.expected} | ${c.actual} | ${c.ok ? "ok" : "**MISMATCH**"} |`));
  h("Assets by status (new system)");
  L.push(Object.entries(r.assetsByStatus).map(([k, v]) => `${k}: ${v}`).join(" · "));
  h("Containers and their contents");
  L.push("| container | name | contents |", "|---|---|---:|", ...r.containers.map((c) => `| ${c.legacyId} | ${c.name} | ${c.contents} |`));
  h("How references were resolved (answers the open questions)");
  L.push(`- container_id on assets: ${JSON.stringify(r.resolution.containerRefs)}`, `- asset references in events/audits: ${JSON.stringify(r.resolution.assetRefs)}`, `- location references: ${JSON.stringify(r.resolution.locationRefs)}`);
  h("Issues to review");
  if (r.issuesByCode.length === 0) L.push("none");
  for (const i of r.issuesByCode) { L.push(`- **${i.code}** (${i.severity}) x${i.count}`); i.examples.forEach((e) => L.push(`  - ${e}`)); }
  h("Probable duplicates (containers vs Bag / Hard Case assets; NOT merged)");
  L.push(r.probableDuplicates.length ? r.probableDuplicates.map((d) => `- ${d.container} ~ ${d.asset} (${d.reason})`).join("\n") : "none");
  h("Photos (listed, not copied)");
  L.push(`${r.photos.assetsWithPhotos} asset(s) with ${r.photos.urls} URL(s); see the JSON report for the list.`);
  h("Clean-up needed in the web app");
  L.push(r.categoriesToClean.length ? r.categoriesToClean.map((c) => `- category "${c.category}": ${c.count} asset(s)`).join("\n") : "none");
  h("Location kind proposal (needs your approval; override with --kinds)");
  L.push("| code | name | parent | proposed kind | because |", "|---|---|---|---|---|", ...r.locationKindProposal.map((l) => `| ${l.code} | ${l.name} | ${l.parent ?? ""} | ${l.kind} | ${l.reason} |`));
  return L.join("\n") + "\n";
}
