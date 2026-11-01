export type Row = Record<string, unknown>;

/** The legacy tables we import (see docs/legacy-migration.md for what is deliberately NOT imported). */
export interface LegacyExport {
  assets: Row[];
  containers: Row[];
  locations: Row[];
  events: Row[];
  containerEvents: Row[];
  audits: Row[];
  auditItems: Row[];
}

export type TableKey = keyof LegacyExport;

export const FILES: Record<TableKey, string> = {
  assets: "inventory_assets.json",
  containers: "inventory_containers.json",
  locations: "inventory_locations.json",
  events: "inventory_events.json",
  containerEvents: "inventory_container_events.json",
  audits: "inventory_audits.json",
  auditItems: "inventory_audit_items.json",
};

/** Legacy table names, used for deterministic ids and for the legacy_id_map table. */
export const LEGACY_TABLE: Record<TableKey, string> = {
  assets: "inventory_assets",
  containers: "inventory_containers",
  locations: "inventory_locations",
  events: "inventory_events",
  containerEvents: "inventory_container_events",
  audits: "inventory_audits",
  auditItems: "inventory_audit_items",
};

/**
 * Which legacy column holds what.
 *
 * ASSUMPTION: the real column names were not available to the author. The names below are inferred from the
 * brief (asset_id holds the tag text, container_id, current_location_id, last_known_*, photo_urls,
 * rental_due_date, expected_return_date, linked_asset_id, parent_id, event types, gps_*). Anything that does not
 * match is caught by `validateColumns` and fixed with a small JSON override (`--columns file.json`), never
 * guessed silently.
 */
export interface ColumnMap {
  assets: {
    id: string; tag: string; name: string; status: string;
    category: string; type: string; size: string; cost: string; notes: string; serial: string; photos: string;
    containerRef: string; locationRef: string;
    lastLat: string; lastLng: string; lastAt: string;
    renter: string; rentalDue: string; expectedReturn: string; createdAt: string;
  };
  containers: {
    id: string; tag: string; name: string; status: string; notes: string; locationRef: string;
    renter: string; rentalDue: string; expectedReturn: string; createdAt: string;
  };
  locations: { id: string; code: string; name: string; parent: string; kind: string; linkedAsset: string };
  events: { id: string; assetRef: string; type: string; at: string; performer: string; locationRef: string; lat: string; lng: string; notes: string };
  containerEvents: { id: string; containerRef: string; assetRef: string; type: string; at: string; performer: string; locationRef: string; notes: string };
  audits: { id: string; locationRef: string; startedAt: string; finishedAt: string };
  auditItems: { id: string; auditRef: string; assetRef: string; result: string };
}

export const DEFAULT_COLUMNS: ColumnMap = {
  assets: {
    id: "id", tag: "asset_id", name: "name", status: "status",
    category: "category", type: "type", size: "size", cost: "cost", notes: "notes", serial: "serial_number", photos: "photo_urls",
    containerRef: "container_id", locationRef: "current_location_id",
    lastLat: "last_known_lat", lastLng: "last_known_lng", lastAt: "last_known_at",
    renter: "renter", rentalDue: "rental_due_date", expectedReturn: "expected_return_date", createdAt: "created_at",
  },
  containers: {
    id: "id", tag: "container_id", name: "name", status: "status", notes: "notes", locationRef: "current_location_id",
    renter: "renter", rentalDue: "rental_due_date", expectedReturn: "expected_return_date", createdAt: "created_at",
  },
  locations: { id: "id", code: "code", name: "name", parent: "parent_id", kind: "kind", linkedAsset: "linked_asset_id" },
  events: { id: "id", assetRef: "asset_id", type: "event_type", at: "created_at", performer: "performed_by", locationRef: "location_id", lat: "gps_lat", lng: "gps_lng", notes: "notes" },
  containerEvents: { id: "id", containerRef: "container_id", assetRef: "asset_id", type: "event_type", at: "created_at", performer: "performed_by", locationRef: "location_id", notes: "notes" },
  audits: { id: "id", locationRef: "location_id", startedAt: "started_at", finishedAt: "finished_at" },
  auditItems: { id: "id", auditRef: "audit_id", assetRef: "asset_id", result: "status" },
};

/** Columns without which a table cannot be imported at all. Everything else is optional (and reported if absent). */
export const REQUIRED: { [K in TableKey]: (keyof ColumnMap[K])[] } = {
  assets: ["id", "tag", "name", "status"],
  containers: ["id", "tag", "name"],
  locations: ["id", "code", "name", "parent"],
  events: ["id", "assetRef", "type", "at"],
  containerEvents: ["id", "containerRef", "type", "at"],
  audits: ["id"],
  auditItems: ["id", "auditRef", "assetRef"],
};

export type LocationKindOverrides = Record<string, "site" | "room" | "shelf" | "cabinet" | "bin" | "vehicle" | "other">;

export type Severity = "error" | "warn" | "info";

export interface Issue {
  severity: Severity;
  code: string;
  table: string;
  legacyId?: string;
  message: string;
}

/** Merge a partial override (from --columns) onto the defaults, table by table. */
export function mergeColumns(override: { [K in TableKey]?: Partial<ColumnMap[K]> } | undefined): ColumnMap {
  const out = structuredClone(DEFAULT_COLUMNS);
  if (!override) return out;
  for (const key of Object.keys(override) as TableKey[]) Object.assign(out[key], override[key]);
  return out;
}
