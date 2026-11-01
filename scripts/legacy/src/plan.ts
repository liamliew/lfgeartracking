import { newId } from "./ids";
import { normalizeLegacyTag, tagLookupKeys, type TagKind } from "./tags";
import { type ColumnMap, type Issue, LEGACY_TABLE, type LegacyExport, type LocationKindOverrides, type Row, type Severity } from "./types";

// ---------------------------------------------------------------------------------------------------------------
// Planned (new-database) shapes. Pure data: nothing here touches a database.
// ---------------------------------------------------------------------------------------------------------------
export type AssetStatus = "available" | "checked_out" | "in_repair" | "lost" | "retired";
export type LocationKind = "site" | "room" | "shelf" | "cabinet" | "bin" | "vehicle" | "other";

export interface PlannedLocation {
  id: string; legacyId: string; code: string; name: string; kind: LocationKind; kindReason: string;
  parentId: string | null; linkedAssetLegacyId: string | null;
}
export interface PlannedAsset {
  id: string; legacyTable: string; legacyId: string; legacyTag: string | null;
  name: string; category: string; isContainer: boolean; status: AssetStatus; serial: string | null; notes: string | null;
  custom: Record<string, unknown>; lastSeenAt: string | null; lastLat: number | null; lastLng: number | null;
  parentAssetId: string | null; parentLocationId: string | null; createdAt: string | null;
}
export interface PlannedTag { value: string; kind: TagKind; assetId: string | null; locationId: string | null; legacyId: string }
export interface PlannedCheckout { id: string; reason: string; dueAt: string | null; createdAt: string }
export interface PlannedCheckoutItem { id: string; checkoutId: string; assetId: string; checkedOutAt: string }
export interface PlannedEvent {
  id: string; legacyTable: string; legacyId: string; assetId: string; type: string;
  toLocationId: string | null; toAssetId: string | null; checkoutId: string | null;
  lat: number | null; lng: number | null; payload: Record<string, unknown>; occurredAt: string;
}
export interface PlannedAudit { id: string; legacyId: string; locationId: string; startedAt: string | null; finishedAt: string | null }
export interface PlannedAuditLine { id: string; auditId: string; assetId: string; result: "found" | "missing" | "unexpected" }

export interface Plan {
  locations: PlannedLocation[];
  categories: { name: string; defaultIsContainer: boolean }[];
  assets: PlannedAsset[];
  tags: PlannedTag[];
  checkouts: PlannedCheckout[];
  checkoutItems: PlannedCheckoutItem[];
  events: PlannedEvent[];
  audits: PlannedAudit[];
  auditLines: PlannedAuditLine[];
  issues: Issue[];
  report: {
    /** How references were resolved: this answers "does container_id reference .id or .container_id?" */
    containerRefResolvedBy: Record<string, number>;
    assetRefResolvedBy: Record<string, number>;
    locationRefResolvedBy: Record<string, number>;
    probableDuplicates: { container: string; asset: string; reason: string }[];
    photos: { legacyId: string; name: string; urls: string[] }[];
    categoriesToClean: { category: string; count: number }[];
    legacyCounts: Record<string, number>;
  };
}

export interface PlanOptions {
  columns: ColumnMap;
  /** Overrides for the location kind proposal, keyed by legacy location id or code. */
  kindOverrides?: LocationKindOverrides;
  /** Used for rows without a usable timestamp. */
  now?: Date;
}

// ---------------------------------------------------------------------------------------------------------------
// Small value helpers
// ---------------------------------------------------------------------------------------------------------------
const str = (v: unknown): string | null => (v === null || v === undefined ? null : String(v).trim() || null);
const num = (v: unknown): number | null => {
  if (v === null || v === undefined || v === "") return null;
  const n = typeof v === "number" ? v : Number(v);
  return Number.isFinite(n) ? n : null;
};
/** Timestamps become ISO strings; a bare date (a due date) means the END of that day in UTC. */
export function isoOrNull(v: unknown): string | null {
  const s = str(v);
  if (!s) return null;
  const dateOnly = /^\d{4}-\d{2}-\d{2}$/.test(s);
  const d = new Date(dateOnly ? `${s}T23:59:59Z` : s);
  return Number.isNaN(d.getTime()) ? null : d.toISOString();
}
const get = (row: Row, col: string) => row[col];

export function mapStatus(raw: unknown): { status: AssetStatus; known: boolean } {
  const s = (str(raw) ?? "").toLowerCase().replace(/[\s-]+/g, "_");
  if (s === "available" || s === "in_stock" || s === "") return { status: "available", known: s !== "" };
  if (["checked_out", "out", "rented", "on_rent"].includes(s)) return { status: "checked_out", known: true };
  if (["in_repair", "repair", "maintenance", "damaged"].includes(s)) return { status: "in_repair", known: true };
  if (["lost", "missing"].includes(s)) return { status: "lost", known: true };
  if (["retired", "sold", "disposed", "decommissioned"].includes(s)) return { status: "retired", known: true };
  return { status: "available", known: false };
}

/** The proposal shown for approval: roots are sites, Storage* cabinets, Tray* bins, other children of a site rooms. */
export function inferKind(name: string, hasParent: boolean, parentIsRoot: boolean): { kind: LocationKind; reason: string } {
  if (!hasParent) return { kind: "site", reason: "root location" };
  if (/^storage\b/i.test(name)) return { kind: "cabinet", reason: 'name starts with "Storage"' };
  if (/^tray\b/i.test(name)) return { kind: "bin", reason: 'name starts with "Tray"' };
  if (parentIsRoot) return { kind: "room", reason: "direct child of a site" };
  return { kind: "other", reason: "no rule matched" };
}

const VALID_KINDS = new Set<LocationKind>(["site", "room", "shelf", "cabinet", "bin", "vehicle", "other"]);

// ---------------------------------------------------------------------------------------------------------------
export function buildPlan(data: LegacyExport, options: PlanOptions): Plan {
  const c = options.columns;
  const now = (options.now ?? new Date()).toISOString();
  const issues: Issue[] = [];
  const issue = (severity: Severity, code: string, table: string, legacyId: string | undefined, message: string) =>
    issues.push({ severity, code, table, legacyId, message });

  const report: Plan["report"] = {
    containerRefResolvedBy: {}, assetRefResolvedBy: {}, locationRefResolvedBy: {},
    probableDuplicates: [], photos: [], categoriesToClean: [],
    legacyCounts: Object.fromEntries((Object.keys(data) as (keyof LegacyExport)[]).map((k) => [LEGACY_TABLE[k], data[k].length])),
  };
  const bump = (m: Record<string, number>, k: string) => { m[k] = (m[k] ?? 0) + 1; };

  // ---- locations -------------------------------------------------------------------------------------------------
  const locByLegacyId = new Map<string, PlannedLocation>();
  const locByCode = new Map<string, PlannedLocation>();
  const rawLoc = new Map<string, Row>();
  const seenCodes = new Set<string>();
  const orderedLocRows = [...data.locations].sort((a, b) => String(get(a, c.locations.id)).localeCompare(String(get(b, c.locations.id))));
  for (const row of orderedLocRows) {
    const legacyId = str(get(row, c.locations.id));
    if (!legacyId) { issue("error", "location_no_id", "inventory_locations", undefined, "location without an id skipped"); continue; }
    const code = str(get(row, c.locations.code)) ?? legacyId;
    if (seenCodes.has(code.toLowerCase())) {
      issue("error", "location_duplicate_code", "inventory_locations", legacyId, `location code "${code}" is used twice; the second one is skipped`);
      continue;
    }
    seenCodes.add(code.toLowerCase());
    rawLoc.set(legacyId, row);
    const planned: PlannedLocation = {
      id: newId(LEGACY_TABLE.locations, legacyId), legacyId, code, name: str(get(row, c.locations.name)) ?? code,
      kind: "other", kindReason: "", parentId: null, linkedAssetLegacyId: str(get(row, c.locations.linkedAsset)),
    };
    locByLegacyId.set(legacyId, planned);
    locByCode.set(code, planned);
  }
  // parents, cycle breaking, kinds
  for (const [legacyId, loc] of locByLegacyId) {
    const parentLegacy = str(get(rawLoc.get(legacyId)!, c.locations.parent));
    if (!parentLegacy) continue;
    const parent = locByLegacyId.get(parentLegacy) ?? locByCode.get(parentLegacy);
    if (!parent) { issue("warn", "location_orphan_parent", "inventory_locations", legacyId, `parent "${parentLegacy}" not found; imported as a root`); continue; }
    loc.parentId = parent.id;
  }
  const locById = new Map([...locByLegacyId.values()].map((l) => [l.id, l]));
  for (const loc of locById.values()) {
    const seen = new Set<string>([loc.id]);
    let cur = loc.parentId ? locById.get(loc.parentId) : undefined;
    while (cur) {
      if (seen.has(cur.id)) { issue("error", "location_cycle", "inventory_locations", loc.legacyId, "parent chain loops; the parent link is dropped"); loc.parentId = null; break; }
      seen.add(cur.id);
      cur = cur.parentId ? locById.get(cur.parentId) : undefined;
    }
  }
  for (const loc of locById.values()) {
    const parent = loc.parentId ? locById.get(loc.parentId) : undefined;
    const inferred = inferKind(loc.name, !!parent, !!parent && !parent.parentId);
    const override = options.kindOverrides?.[loc.legacyId] ?? options.kindOverrides?.[loc.code];
    if (override && VALID_KINDS.has(override)) { loc.kind = override; loc.kindReason = "approved override"; }
    else { loc.kind = inferred.kind; loc.kindReason = inferred.reason; }
    if (loc.linkedAssetLegacyId) {
      issue("info", "location_linked_asset", "inventory_locations", loc.legacyId, `location is linked to asset "${loc.linkedAssetLegacyId}"; the link is not imported, please review`);
    }
  }
  const locations = topoSortLocations([...locById.values()]);

  const resolveLocation = (ref: unknown): PlannedLocation | null => {
    const key = str(ref);
    if (!key) return null;
    const byId = locByLegacyId.get(key);
    if (byId) { bump(report.locationRefResolvedBy, "id"); return byId; }
    const byCode = locByCode.get(key);
    if (byCode) { bump(report.locationRefResolvedBy, "code"); return byCode; }
    bump(report.locationRefResolvedBy, "unresolved");
    return null;
  };

  // ---- assets and containers -------------------------------------------------------------------------------------
  const assets: PlannedAsset[] = [];
  const tags: PlannedTag[] = [];
  const tagOwner = new Map<string, string>(); // normalised value -> "table:legacyId"
  const registerTag = (raw: string | null, ownerLegacy: string, table: string, target: { assetId?: string; locationId?: string }) => {
    const t = normalizeLegacyTag(raw);
    if (!t) { if (target.assetId) issue("warn", "no_tag", table, ownerLegacy, "no tag value; only its generated code will be scannable"); return; }
    if (t.downgraded) issue("warn", "tag_not_nfc", table, ownerLegacy, `"${raw}" looks like an NFC id but is too short; kept as a barcode`);
    if (/^[AC]\d{6}$|^L\d{5}$/.test(t.value)) issue("warn", "tag_like_new_code", table, ownerLegacy, `"${t.value}" has the shape of a new printed code and may clash with generated codes`);
    const existing = tagOwner.get(t.value);
    if (existing) { issue("error", "tag_collision", table, ownerLegacy, `tag "${t.value}" is already used by ${existing}; this one is not imported`); return; }
    tagOwner.set(t.value, `${table}:${ownerLegacy}`);
    tags.push({ value: t.value, kind: t.kind, assetId: target.assetId ?? null, locationId: target.locationId ?? null, legacyId: ownerLegacy });
  };
  for (const loc of locations) registerTag(loc.code, loc.legacyId, "inventory_locations", { locationId: loc.id });

  const byLegacyKey = new Map<string, PlannedAsset>(); // "table:id"
  const byTag = new Map<string, PlannedAsset[]>();
  const addAsset = (a: PlannedAsset) => {
    assets.push(a);
    byLegacyKey.set(`${a.legacyTable}:${a.legacyId}`, a);
    for (const k of tagLookupKeys(a.legacyTag)) byTag.set(k, [...(byTag.get(k) ?? []), a]);
  };
  const sortRows = (rows: Row[], idCol: string, atCol: string) =>
    [...rows].sort((a, b) => (isoOrNull(get(a, atCol)) ?? "").localeCompare(isoOrNull(get(b, atCol)) ?? "") || String(get(a, idCol)).localeCompare(String(get(b, idCol))));

  const rentalOf = (row: Row, cols: { renter: string; rentalDue: string; expectedReturn: string }) => ({
    renter: str(get(row, cols.renter)),
    due: isoOrNull(get(row, cols.rentalDue)) ?? isoOrNull(get(row, cols.expectedReturn)),
  });
  const rentals = new Map<string, { renter: string | null; due: string | null }>();

  for (const row of sortRows(data.containers, c.containers.id, c.containers.createdAt)) {
    const legacyId = str(get(row, c.containers.id));
    if (!legacyId) { issue("error", "container_no_id", "inventory_containers", undefined, "container without id skipped"); continue; }
    const st = mapStatus(get(row, c.containers.status));
    if (!st.known) issue("warn", "unknown_status", "inventory_containers", legacyId, `status "${str(get(row, c.containers.status))}" is not recognised; imported as available`);
    const a: PlannedAsset = {
      id: newId(LEGACY_TABLE.containers, legacyId), legacyTable: LEGACY_TABLE.containers, legacyId, legacyTag: str(get(row, c.containers.tag)),
      name: str(get(row, c.containers.name)) ?? `Container ${legacyId}`, category: "Cases & Bags", isContainer: true, status: st.status,
      serial: null, notes: str(get(row, c.containers.notes)), custom: { legacy_id: legacyId, legacy_table: LEGACY_TABLE.containers, legacy_tag: str(get(row, c.containers.tag)) },
      lastSeenAt: null, lastLat: null, lastLng: null, parentAssetId: null, parentLocationId: null, createdAt: isoOrNull(get(row, c.containers.createdAt)),
    };
    const loc = resolveLocation(get(row, c.containers.locationRef));
    if (str(get(row, c.containers.locationRef)) && !loc) issue("warn", "orphan_location_ref", "inventory_containers", legacyId, `location "${str(get(row, c.containers.locationRef))}" not found`);
    a.parentLocationId = loc?.id ?? null;
    if (st.status === "checked_out") rentals.set(a.id, rentalOf(row, c.containers));
    addAsset(a);
  }

  const categoryCounts = new Map<string, number>();
  const rowsByAsset = new Map<string, Row>();
  for (const row of sortRows(data.assets, c.assets.id, c.assets.createdAt)) {
    const legacyId = str(get(row, c.assets.id));
    if (!legacyId) { issue("error", "asset_no_id", "inventory_assets", undefined, "asset without id skipped"); continue; }
    const st = mapStatus(get(row, c.assets.status));
    if (!st.known) issue("warn", "unknown_status", "inventory_assets", legacyId, `status "${str(get(row, c.assets.status))}" is not recognised; imported as available`);
    const category = str(get(row, c.assets.category)) ?? "Other";
    categoryCounts.set(category, (categoryCounts.get(category) ?? 0) + 1);
    const type = str(get(row, c.assets.type));
    if (!type) issue("info", "no_type", "inventory_assets", legacyId, "no type; needs clean-up in the web app");
    const photos = photoUrls(get(row, c.assets.photos));
    const custom: Record<string, unknown> = { legacy_id: legacyId, legacy_table: LEGACY_TABLE.assets, legacy_tag: str(get(row, c.assets.tag)) };
    if (type) custom.legacy_type = type;
    const size = str(get(row, c.assets.size)); if (size) custom.size = size;
    const cost = get(row, c.assets.cost); if (cost !== null && cost !== undefined && cost !== "") custom.cost = cost;
    if (photos.length) { custom.legacy_photo_urls = photos; report.photos.push({ legacyId, name: str(get(row, c.assets.name)) ?? legacyId, urls: photos }); }
    const a: PlannedAsset = {
      id: newId(LEGACY_TABLE.assets, legacyId), legacyTable: LEGACY_TABLE.assets, legacyId, legacyTag: str(get(row, c.assets.tag)),
      name: str(get(row, c.assets.name)) ?? `Asset ${legacyId}`, category, isContainer: false, status: st.status,
      serial: str(get(row, c.assets.serial)), notes: str(get(row, c.assets.notes)), custom,
      lastSeenAt: isoOrNull(get(row, c.assets.lastAt)), lastLat: num(get(row, c.assets.lastLat)), lastLng: num(get(row, c.assets.lastLng)),
      parentAssetId: null, parentLocationId: null, createdAt: isoOrNull(get(row, c.assets.createdAt)),
    };
    if (st.status === "checked_out") rentals.set(a.id, rentalOf(row, c.assets));
    rowsByAsset.set(a.id, row);
    addAsset(a);
  }
  report.categoriesToClean = [...categoryCounts].filter(([name]) => /^(other|misc|miscellaneous|uncategori[sz]ed)$/i.test(name)).map(([category, count]) => ({ category, count }));

  // reference resolvers (try the legacy primary key first, then the tag text)
  const resolveContainerRef = (ref: unknown): PlannedAsset | null => {
    const key = str(ref);
    if (!key) return null;
    const byId = byLegacyKey.get(`${LEGACY_TABLE.containers}:${key}`);
    if (byId) { bump(report.containerRefResolvedBy, "inventory_containers.id"); return byId; }
    for (const k of tagLookupKeys(key)) {
      const hit = (byTag.get(k) ?? []).find((a) => a.legacyTable === LEGACY_TABLE.containers);
      if (hit) { bump(report.containerRefResolvedBy, "inventory_containers.container_id (tag)"); return hit; }
    }
    const asAsset = byLegacyKey.get(`${LEGACY_TABLE.assets}:${key}`) ?? tagLookupKeys(key).map((k) => byTag.get(k)?.[0]).find(Boolean);
    if (asAsset) { bump(report.containerRefResolvedBy, "inventory_assets (not a container)"); return asAsset; }
    bump(report.containerRefResolvedBy, "unresolved");
    return null;
  };
  const resolveAssetRef = (ref: unknown): PlannedAsset | null => {
    const key = str(ref);
    if (!key) return null;
    const byId = byLegacyKey.get(`${LEGACY_TABLE.assets}:${key}`) ?? byLegacyKey.get(`${LEGACY_TABLE.containers}:${key}`);
    if (byId) { bump(report.assetRefResolvedBy, "id"); return byId; }
    for (const k of tagLookupKeys(key)) {
      const hit = byTag.get(k)?.[0];
      if (hit) { bump(report.assetRefResolvedBy, "tag"); return hit; }
    }
    bump(report.assetRefResolvedBy, "unresolved");
    return null;
  };

  // placement of assets: container wins over location (an asset has at most one parent)
  for (const a of assets) {
    if (a.legacyTable !== LEGACY_TABLE.assets) continue;
    const row = rowsByAsset.get(a.id)!;
    const containerRef = str(get(row, c.assets.containerRef));
    const locRef = str(get(row, c.assets.locationRef));
    const loc = resolveLocation(locRef);
    if (locRef && !loc) issue("warn", "orphan_location_ref", "inventory_assets", a.legacyId, `location "${locRef}" not found`);
    let parent: PlannedAsset | null = null;
    if (containerRef) {
      parent = resolveContainerRef(containerRef);
      if (!parent) issue("warn", "orphan_container_ref", "inventory_assets", a.legacyId, `container "${containerRef}" not found; using its location instead`);
      else if (!parent.isContainer) {
        issue("warn", "parent_not_container", "inventory_assets", a.legacyId, `container "${containerRef}" is the asset "${parent.name}", which is not in inventory_containers; using its location instead`);
        parent = null;
      } else if (parent.id === a.id) parent = null;
    }
    if (parent) {
      a.parentAssetId = parent.id;
      if (loc) issue("info", "both_parents", "inventory_assets", a.legacyId, "has both a container and a location; the container wins (location is derived from it)");
    } else {
      a.parentLocationId = loc?.id ?? null;
    }
  }
  breakAssetLoops(assets, issue);

  // tags (after placement so collisions are reported against final owners)
  for (const a of assets) registerTag(a.legacyTag, a.legacyId, a.legacyTable, { assetId: a.id });

  // ---- categories ------------------------------------------------------------------------------------------------
  const categories = [...new Set(assets.map((a) => a.category))].sort().map((name) => ({ name, defaultIsContainer: name === "Cases & Bags" }));

  // ---- probable duplicates between containers and Bag / Hard Case assets ----------------------------------------
  const containers = assets.filter((a) => a.isContainer);
  const looksLikeCase = (a: PlannedAsset) => /\b(bag|case|pouch|box)\b/i.test(`${a.name} ${(a.custom.legacy_type as string) ?? ""} ${a.category}`);
  for (const ct of containers) {
    for (const a of assets) {
      if (a.isContainer || !looksLikeCase(a)) continue;
      const sameName = a.name.toLowerCase() === ct.name.toLowerCase();
      const sameTag = !!ct.legacyTag && !!a.legacyTag && tagLookupKeys(ct.legacyTag).some((k) => tagLookupKeys(a.legacyTag).includes(k));
      if (sameName || sameTag) report.probableDuplicates.push({ container: `${ct.name} (${ct.legacyId})`, asset: `${a.name} (${a.legacyId})`, reason: sameTag ? "same tag" : "same name" });
    }
  }
  for (const d of report.probableDuplicates) issue("warn", "probable_duplicate", "inventory_containers", undefined, `${d.container} may be the same item as asset ${d.asset} (${d.reason}); not merged`);

  // ---- events (needed for checkout dates), then checkouts --------------------------------------------------------
  const events: PlannedEvent[] = [];
  const eventTime = (row: Row, col: string, table: string, legacyId: string): string | null => {
    const t = isoOrNull(get(row, col));
    if (!t) issue("error", "event_no_time", table, legacyId, "event has no usable timestamp; skipped");
    return t;
  };
  for (const row of data.events) {
    const legacyId = str(get(row, c.events.id)) ?? "?";
    const at = eventTime(row, c.events.at, "inventory_events", legacyId);
    if (!at) continue;
    const asset = resolveAssetRef(get(row, c.events.assetRef));
    if (!asset) { issue("warn", "orphan_event", "inventory_events", legacyId, `asset "${str(get(row, c.events.assetRef))}" not found; event skipped`); continue; }
    const loc = resolveLocation(get(row, c.events.locationRef));
    const lat = num(get(row, c.events.lat)); const lng = num(get(row, c.events.lng));
    const legacyType = (str(get(row, c.events.type)) ?? "").toLowerCase();
    let type: string | null = null;
    const payload: Record<string, unknown> = { legacy: { table: LEGACY_TABLE.events, id: legacyId, type: legacyType } };
    switch (legacyType) {
      case "created": type = "created"; break;
      case "checkin": case "check_in": case "checked_in": type = "checked_in"; break;
      case "checkout": case "check_out": case "checked_out": type = "checked_out"; break;
      case "edited": case "edit": type = "edited"; break;
      case "location": type = loc ? "placed_in_location" : "seen"; payload.legacy_location_event = true; break;
      case "inquiry": type = "seen"; payload.legacy_inquiry = true; break;
      default: issue("warn", "unknown_event_type", "inventory_events", legacyId, `event type "${legacyType}" is not mapped; skipped`);
    }
    if (!type) continue;
    const performer = str(get(row, c.events.performer)); if (performer) payload.legacy_performer = performer;
    const notes = str(get(row, c.events.notes)); if (notes) payload.notes = notes;
    events.push({
      id: newId(LEGACY_TABLE.events, legacyId), legacyTable: LEGACY_TABLE.events, legacyId, assetId: asset.id, type,
      toLocationId: loc?.id ?? null, toAssetId: null, checkoutId: null, lat, lng, payload, occurredAt: at,
    });
  }
  for (const row of data.containerEvents) {
    const legacyId = str(get(row, c.containerEvents.id)) ?? "?";
    const at = eventTime(row, c.containerEvents.at, "inventory_container_events", legacyId);
    if (!at) continue;
    const container = resolveContainerRef(get(row, c.containerEvents.containerRef));
    if (!container) { issue("warn", "orphan_event", "inventory_container_events", legacyId, `container "${str(get(row, c.containerEvents.containerRef))}" not found; event skipped`); continue; }
    const legacyType = (str(get(row, c.containerEvents.type)) ?? "").toLowerCase();
    const loc = resolveLocation(get(row, c.containerEvents.locationRef));
    const payload: Record<string, unknown> = { legacy: { table: LEGACY_TABLE.containerEvents, id: legacyId, type: legacyType } };
    const performer = str(get(row, c.containerEvents.performer)); if (performer) payload.legacy_performer = performer;
    const notes = str(get(row, c.containerEvents.notes)); if (notes) payload.notes = notes;
    const base = { id: newId(LEGACY_TABLE.containerEvents, legacyId), legacyTable: LEGACY_TABLE.containerEvents, legacyId, checkoutId: null, lat: null, lng: null, occurredAt: at };
    if (legacyType === "asset_added" || legacyType === "asset_removed") {
      const member = resolveAssetRef(get(row, c.containerEvents.assetRef));
      if (!member) { issue("warn", "orphan_event", "inventory_container_events", legacyId, `asset "${str(get(row, c.containerEvents.assetRef))}" not found; event skipped`); continue; }
      payload.container = container.name;
      events.push(legacyType === "asset_added"
        ? { ...base, assetId: member.id, type: "placed_in_container", toLocationId: null, toAssetId: container.id, payload }
        : { ...base, assetId: member.id, type: "removed_from_container", toLocationId: null, toAssetId: null, payload });
      continue;
    }
    const mapped: Record<string, string> = { location_update: "placed_in_location", check_out: "checked_out", check_in: "checked_in", created: "created" };
    const type = mapped[legacyType];
    if (!type) { issue("warn", "unknown_event_type", "inventory_container_events", legacyId, `event type "${legacyType}" is not mapped; skipped`); continue; }
    if (type === "placed_in_location" && !loc) { issue("warn", "orphan_location_ref", "inventory_container_events", legacyId, "location_update without a known location; recorded as seen"); events.push({ ...base, assetId: container.id, type: "seen", toLocationId: null, toAssetId: null, payload }); continue; }
    events.push({ ...base, assetId: container.id, type, toLocationId: type === "placed_in_location" || type === "checked_in" ? loc?.id ?? null : null, toAssetId: null, payload });
  }
  events.sort((a, b) => a.occurredAt.localeCompare(b.occurredAt) || a.id.localeCompare(b.id));

  // ---- checkouts: one open checkout per (renter, due date); one line per asset that is currently out -------------
  const checkouts: PlannedCheckout[] = [];
  const checkoutItems: PlannedCheckoutItem[] = [];
  const checkoutById = new Map<string, PlannedCheckout>();
  for (const a of assets.filter((x) => x.status === "checked_out")) {
    const rental = rentals.get(a.id) ?? { renter: null, due: null };
    const lastOut = [...events].reverse().find((e) => e.assetId === a.id && e.type === "checked_out");
    const outAt = lastOut?.occurredAt ?? a.lastSeenAt ?? a.createdAt ?? now;
    if (!lastOut) issue("warn", "checkout_date_unknown", a.legacyTable, a.legacyId, "checked out but no checkout event; the checkout date is a fallback");
    if (!rental.due) issue("warn", "checkout_no_due", a.legacyTable, a.legacyId, "checked out without a due date (rental_due_date / expected_return_date)");
    if (!rental.renter) issue("info", "checkout_no_renter", a.legacyTable, a.legacyId, "checked out without a renter or user name");
    const key = `${rental.renter ?? "unknown"}|${rental.due ?? "no-due"}`;
    const id = newId("legacy_checkout", key);
    let co = checkoutById.get(id);
    if (!co) {
      co = { id, reason: `Legacy import: ${rental.renter ?? "unknown"}`, dueAt: rental.due, createdAt: outAt };
      checkoutById.set(id, co);
      checkouts.push(co);
    } else if (outAt < co.createdAt) co.createdAt = outAt;
    checkoutItems.push({ id: newId("legacy_checkout_item", a.id), checkoutId: id, assetId: a.id, checkedOutAt: outAt });
    if (lastOut) lastOut.checkoutId = id;
  }

  // ---- audits ----------------------------------------------------------------------------------------------------
  const audits: PlannedAudit[] = [];
  const auditLines: PlannedAuditLine[] = [];
  const auditByLegacy = new Map<string, PlannedAudit>();
  for (const row of data.audits) {
    const legacyId = str(get(row, c.audits.id));
    if (!legacyId) continue;
    const loc = resolveLocation(get(row, c.audits.locationRef));
    if (!loc) { issue("warn", "audit_no_location", "inventory_audits", legacyId, "audit has no known location; skipped (audits need one)"); continue; }
    const a: PlannedAudit = { id: newId(LEGACY_TABLE.audits, legacyId), legacyId, locationId: loc.id, startedAt: isoOrNull(get(row, c.audits.startedAt)), finishedAt: isoOrNull(get(row, c.audits.finishedAt)) };
    audits.push(a); auditByLegacy.set(legacyId, a);
  }
  const lineSeen = new Set<string>();
  for (const row of data.auditItems) {
    const legacyId = str(get(row, c.auditItems.id)) ?? "?";
    const audit = auditByLegacy.get(str(get(row, c.auditItems.auditRef)) ?? "");
    const asset = resolveAssetRef(get(row, c.auditItems.assetRef));
    if (!audit || !asset) { issue("warn", "orphan_audit_item", "inventory_audit_items", legacyId, "audit or asset not found; skipped"); continue; }
    const raw = (str(get(row, c.auditItems.result)) ?? "").toLowerCase();
    const result = ["found", "present", "ok", "verified"].includes(raw) ? "found" : ["missing", "not_found", "absent"].includes(raw) ? "missing" : ["unexpected", "extra", "misplaced"].includes(raw) ? "unexpected" : null;
    if (!result) { issue("warn", "unknown_audit_result", "inventory_audit_items", legacyId, `result "${raw}" is not mapped; skipped`); continue; }
    const key = `${audit.id}:${asset.id}`;
    if (lineSeen.has(key)) { issue("info", "duplicate_audit_item", "inventory_audit_items", legacyId, "asset already in this audit; skipped"); continue; }
    lineSeen.add(key);
    auditLines.push({ id: newId(LEGACY_TABLE.auditItems, legacyId), auditId: audit.id, assetId: asset.id, result });
  }

  return { locations, categories, assets, tags, checkouts, checkoutItems, events, audits, auditLines, issues, report };
}

// ---------------------------------------------------------------------------------------------------------------
function photoUrls(v: unknown): string[] {
  if (v === null || v === undefined || v === "") return [];
  let value: unknown = v;
  if (typeof v === "string") {
    try { value = JSON.parse(v); } catch { value = v.split(/[\s,]+/); }
  }
  return (Array.isArray(value) ? value : [value]).map((x) => String(x).trim()).filter(Boolean);
}

function topoSortLocations(all: PlannedLocation[]): PlannedLocation[] {
  const byId = new Map(all.map((l) => [l.id, l]));
  const out: PlannedLocation[] = [];
  const done = new Set<string>();
  const visit = (l: PlannedLocation) => {
    if (done.has(l.id)) return;
    done.add(l.id);
    const parent = l.parentId ? byId.get(l.parentId) : undefined;
    if (parent) visit(parent);
    out.push(l);
  };
  [...all].sort((a, b) => a.legacyId.localeCompare(b.legacyId)).forEach(visit);
  return out;
}

/** An asset inside itself, or a loop of containers, would be refused by the database: drop the closing link and say so. */
function breakAssetLoops(assets: PlannedAsset[], issue: (s: Severity, code: string, table: string, id: string | undefined, m: string) => void) {
  const byId = new Map(assets.map((a) => [a.id, a]));
  for (const a of assets) {
    const seen = new Set<string>([a.id]);
    let depth = 0;
    let cur = a.parentAssetId ? byId.get(a.parentAssetId) : undefined;
    while (cur) {
      depth++;
      if (seen.has(cur.id)) { issue("error", "container_cycle", a.legacyTable, a.legacyId, "container chain loops; its container link is dropped"); a.parentAssetId = null; break; }
      seen.add(cur.id);
      cur = cur.parentAssetId ? byId.get(cur.parentAssetId) : undefined;
    }
    if (a.parentAssetId && depth >= 10) { issue("error", "container_too_deep", a.legacyTable, a.legacyId, "container nesting exceeds 10 levels; its container link is dropped"); a.parentAssetId = null; }
  }
}

/** Effective location of every planned asset, derived the same way the database does (walk up the containers). */
export function plannedEffectiveLocations(plan: Plan): Map<string, string | null> {
  const byId = new Map(plan.assets.map((a) => [a.id, a]));
  const out = new Map<string, string | null>();
  for (const a of plan.assets) {
    let cur: PlannedAsset | undefined = a;
    let guard = 0;
    while (cur?.parentAssetId && guard++ < 20) cur = byId.get(cur.parentAssetId);
    out.set(a.id, cur?.parentLocationId ?? null);
  }
  return out;
}
