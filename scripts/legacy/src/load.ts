import type { Client } from "pg";
import { type Plan } from "./plan";
import { LEGACY_TABLE } from "./types";

export interface LoadResult {
  /** Rows created by THIS run (re-running adds only what is missing). */
  inserted: Record<string, number>;
  /** Ids of assets that were created by this run: only those are compared strictly by the verifier. */
  newAssetIds: Set<string>;
  newLocationIds: Set<string>;
  problems: { code: string; message: string }[];
}

/**
 * Writes the plan inside the caller's transaction. Idempotent: every row has a deterministic id and is inserted
 * with ON CONFLICT DO NOTHING, so a second run never overwrites edits made in the new system.
 * Runs as a database owner (e.g. the `postgres` role), never through the public API.
 */
export async function loadPlan(db: Client, plan: Plan, importedAt: Date): Promise<LoadResult> {
  const res: LoadResult = { inserted: {}, newAssetIds: new Set(), newLocationIds: new Set(), problems: [] };
  const bump = (k: string, n = 1) => { res.inserted[k] = (res.inserted[k] ?? 0) + n; };

  // categories
  for (const c of plan.categories) {
    const r = await db.query("insert into public.categories (name, default_is_container) values ($1, $2) on conflict (name) do nothing", [c.name, c.defaultIsContainer]);
    bump("categories", r.rowCount ?? 0);
  }
  const categoryIds = new Map<string, string>();
  for (const row of (await db.query<{ id: string; name: string }>("select id, name from public.categories")).rows) categoryIds.set(row.name, row.id);

  // locations, parents first. The code is unique, so a clash with an existing row is reported, not fatal.
  // Whatever points at a location that could not be imported is skipped and reported (never an FK crash).
  const missingLocations = new Set<string>();
  for (const l of plan.locations) {
    if (l.parentId && missingLocations.has(l.parentId)) {
      missingLocations.add(l.id);
      res.problems.push({ code: "location_not_imported", message: `location ${l.legacyId} (${l.code}): its parent was not imported` });
      continue;
    }
    await db.query("savepoint loc");
    try {
      const r = await db.query(
        "insert into public.locations (id, code, name, kind, parent_id) values ($1, $2, $3, $4::public.location_kind, $5) on conflict (id) do nothing",
        [l.id, l.code, l.name, l.kind, l.parentId],
      );
      if (r.rowCount) { bump("locations"); res.newLocationIds.add(l.id); }
      await db.query("release savepoint loc");
    } catch (e) {
      await db.query("rollback to savepoint loc");
      missingLocations.add(l.id);
      res.problems.push({ code: "location_not_imported", message: `location ${l.legacyId} (${l.code}): ${(e as Error).message}` });
    }
  }

  // assets and containers, without parents (a parent may come later in the list)
  for (const a of plan.assets) {
    const r = await db.query(
      `insert into public.assets (id, name, category_id, is_container, status, serial_number, notes, custom,
                                  last_seen_at, last_seen_lat, last_seen_lng, retired_at, created_at)
       values ($1, $2, $3, $4, $5::public.asset_status, $6, $7, $8::jsonb, $9, $10, $11, $12, coalesce($13::timestamptz, $14::timestamptz))
       on conflict (id) do nothing`,
      [
        a.id, a.name, categoryIds.get(a.category) ?? null, a.isContainer, a.status, a.serial, a.notes, JSON.stringify(a.custom),
        a.lastSeenAt, a.lastLat, a.lastLng, a.status === "retired" ? importedAt.toISOString() : null, a.createdAt, importedAt.toISOString(),
      ],
    );
    if (r.rowCount) { bump(a.isContainer ? "containers" : "assets"); res.newAssetIds.add(a.id); }
  }
  // placement, only for rows created by this run (never move something someone has since moved)
  for (const a of plan.assets) {
    if (!res.newAssetIds.has(a.id)) continue;
    if (a.parentAssetId) await db.query("update public.assets set parent_asset_id = $2 where id = $1", [a.id, a.parentAssetId]);
    else if (a.parentLocationId && missingLocations.has(a.parentLocationId)) {
      res.problems.push({ code: "placement_skipped", message: `${a.legacyTable} ${a.legacyId} (${a.name}): its location was not imported, so it has no placement` });
    } else if (a.parentLocationId) await db.query("update public.assets set parent_location_id = $2 where id = $1", [a.id, a.parentLocationId]);
  }

  // tags (the printed-code tags already exist via triggers)
  for (const t of plan.tags) {
    if (t.locationId && missingLocations.has(t.locationId)) continue; // already reported as location_not_imported
    const r = await db.query(
      "insert into public.tags (value, kind, asset_id, location_id) values ($1, $2::public.tag_kind, $3, $4) on conflict (value) do nothing",
      [t.value, t.kind, t.assetId, t.locationId],
    );
    if (r.rowCount) { bump("tags"); continue; }
    const owner = (await db.query<{ asset_id: string | null; location_id: string | null }>("select asset_id, location_id from public.tags where value = $1", [t.value])).rows[0];
    if (!owner || owner.asset_id !== t.assetId || owner.location_id !== t.locationId) {
      res.problems.push({ code: "tag_owned_elsewhere", message: `tag "${t.value}" (legacy ${t.legacyId}) already belongs to another row in the new database` });
    }
  }

  // open checkouts
  for (const c of plan.checkouts) {
    const r = await db.query("insert into public.checkouts (id, reason, due_at, created_at) values ($1, $2, $3, $4) on conflict (id) do nothing", [c.id, c.reason, c.dueAt, c.createdAt]);
    bump("checkouts", r.rowCount ?? 0);
  }
  for (const i of plan.checkoutItems) {
    const r = await db.query("insert into public.checkout_items (id, checkout_id, asset_id, checked_out_at) values ($1, $2, $3, $4) on conflict (id) do nothing", [i.id, i.checkoutId, i.assetId, i.checkedOutAt]);
    bump("checkout_items", r.rowCount ?? 0);
  }

  // history, with the original timestamps. Written directly (not through apply_scan_events) so nothing replays.
  for (const e of plan.events) {
    const r = await db.query(
      `insert into public.asset_events (id, asset_id, type, to_location_id, to_asset_id, checkout_id, lat, lng, payload, occurred_at, device_id)
       values ($1, $2, $3::public.event_type, $4, $5, $6, $7, $8, $9::jsonb, $10, 'legacy-import') on conflict (id) do nothing`,
      [e.id, e.assetId, e.type, e.toLocationId && missingLocations.has(e.toLocationId) ? null : e.toLocationId, e.toAssetId, e.checkoutId, e.lat, e.lng, JSON.stringify(e.payload), e.occurredAt],
    );
    bump("events", r.rowCount ?? 0);
  }

  // audits
  const skippedAudits = new Set<string>();
  for (const a of plan.audits) {
    if (missingLocations.has(a.locationId)) {
      skippedAudits.add(a.id);
      res.problems.push({ code: "audit_skipped", message: `audit ${a.legacyId}: its location was not imported` });
      continue;
    }
    const r = await db.query("insert into public.audits (id, location_id, started_at, finished_at) values ($1, $2, coalesce($3::timestamptz, $5::timestamptz), $4) on conflict (id) do nothing", [a.id, a.locationId, a.startedAt, a.finishedAt, importedAt.toISOString()]);
    bump("audits", r.rowCount ?? 0);
  }
  for (const l of plan.auditLines) {
    if (skippedAudits.has(l.auditId)) continue;
    const r = await db.query("insert into public.audit_lines (id, audit_id, asset_id, result) values ($1, $2, $3, $4::public.audit_result) on conflict (id) do nothing", [l.id, l.auditId, l.assetId, l.result]);
    bump("audit_lines", r.rowCount ?? 0);
  }

  // traceability: legacy row -> new row
  const maps: [string, string, string, string][] = [
    ...plan.locations.map((l) => [LEGACY_TABLE.locations, l.legacyId, "locations", l.id] as [string, string, string, string]),
    ...plan.assets.map((a) => [a.legacyTable, a.legacyId, "assets", a.id] as [string, string, string, string]),
    ...plan.events.map((e) => [e.legacyTable, e.legacyId, "asset_events", e.id] as [string, string, string, string]),
    ...plan.audits.map((a) => [LEGACY_TABLE.audits, a.legacyId, "audits", a.id] as [string, string, string, string]),
  ];
  for (const [lt, lid, nt, nid] of maps) {
    await db.query("insert into public.legacy_id_map (legacy_table, legacy_id, new_table, new_id, imported_at) values ($1, $2, $3, $4, $5) on conflict do nothing", [lt, lid, nt, nid, importedAt.toISOString()]);
  }
  return res;
}
