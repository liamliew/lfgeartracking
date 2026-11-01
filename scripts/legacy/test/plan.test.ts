import { describe, expect, it } from "vitest";
import { buildFixture } from "../src/fixtures";
import { buildPlan, inferKind, isoOrNull, mapStatus, plannedEffectiveLocations } from "../src/plan";
import { DEFAULT_COLUMNS, type LegacyExport, type Row } from "../src/types";

const empty = (): LegacyExport => ({ assets: [], containers: [], locations: [], events: [], containerEvents: [], audits: [], auditItems: [] });
const plan = (d: Partial<LegacyExport>, kindOverrides?: Record<string, never>) =>
  buildPlan({ ...empty(), ...d }, { columns: DEFAULT_COLUMNS, now: new Date("2026-11-01T00:00:00Z"), kindOverrides });
const codes = (p: ReturnType<typeof plan>) => p.issues.map((i) => i.code);

const loc = (id: string, code: string, name: string, parent: string | null = null, extra: object = {}) => ({ id, code, name, parent_id: parent, ...extra });
const asset = (id: string, tag: string, extra: object = {}) => ({ id, asset_id: tag, name: `Asset ${id}`, status: "available", category: "Camera", ...extra });
const container = (id: string, tag: string, extra: object = {}) => ({ id, container_id: tag, name: `Case ${id}`, status: "available", ...extra });

describe("helpers", () => {
  it("maps statuses and flags unknown ones", () => {
    expect(mapStatus("checked_out")).toEqual({ status: "checked_out", known: true });
    expect(mapStatus("Checked-Out")).toEqual({ status: "checked_out", known: true });
    expect(mapStatus("maintenance")).toEqual({ status: "in_repair", known: true });
    expect(mapStatus("sold").status).toBe("retired");
    expect(mapStatus("weird")).toEqual({ status: "available", known: false });
  });

  it("treats a bare date as the end of that day, timestamps as themselves", () => {
    expect(isoOrNull("2026-11-03")).toBe("2026-11-03T23:59:59.000Z");
    expect(isoOrNull("2026-11-03T10:00:00+08:00")).toBe("2026-11-03T02:00:00.000Z");
    expect(isoOrNull("not a date")).toBeNull();
    expect(isoOrNull(null)).toBeNull();
  });

  it("proposes location kinds", () => {
    expect(inferKind("Savanna", false, false).kind).toBe("site");
    expect(inferKind("Storage 2B", true, true).kind).toBe("cabinet");
    expect(inferKind("Tray 1A", true, false).kind).toBe("bin");
    expect(inferKind("Office", true, true).kind).toBe("room");
    expect(inferKind("Whatever", true, false).kind).toBe("other");
  });
});

describe("locations", () => {
  const base = [loc("t1", "10137", "Tray 1A", "s1"), loc("s1", "S1", "Storage 1", "root"), loc("root", "SAV1", "Savanna")];

  it("orders parents before children and infers kinds (shown for approval)", () => {
    const p = plan({ locations: base });
    expect(p.locations.map((l) => l.legacyId)).toEqual(["root", "s1", "t1"]);
    expect(p.locations.map((l) => l.kind)).toEqual(["site", "cabinet", "bin"]);
  });

  it("accepts approved overrides by legacy id or code", () => {
    const p = plan({ locations: base }, { s1: "shelf", "10137": "room" } as never);
    expect(p.locations.find((l) => l.legacyId === "s1")!.kind).toBe("shelf");
    expect(p.locations.find((l) => l.legacyId === "t1")!.kindReason).toBe("approved override");
  });

  it("makes every location code scannable and keeps old codes", () => {
    const p = plan({ locations: base });
    expect(p.tags.filter((t) => t.locationId).map((t) => t.value).sort()).toEqual(["10137", "S1", "SAV1"]);
  });

  it("flags duplicate codes, orphan parents, cycles and linked assets", () => {
    const p = plan({ locations: [loc("a", "X", "A"), loc("b", "X", "B"), loc("c", "C", "C", "nope"), loc("d", "D", "D", "e"), loc("e", "E", "E", "d", {}), loc("f", "F", "F", null, { linked_asset_id: "ast-1" })] });
    expect(codes(p)).toEqual(expect.arrayContaining(["location_duplicate_code", "location_orphan_parent", "location_cycle", "location_linked_asset"]));
    expect(p.locations.find((l) => l.legacyId === "b")).toBeUndefined();
    expect(p.locations.filter((l) => ["d", "e"].includes(l.legacyId) && l.parentId === null).length).toBeGreaterThan(0);
  });
});

describe("tags", () => {
  it("normalises NFC ids and keeps barcodes", () => {
    const p = plan({ assets: [asset("1", "04-46-88-0A-BF-2A-81"), asset("2", " 110164 ")] });
    expect(p.tags.map((t) => [t.value, t.kind])).toEqual([["0446880ABF2A81", "nfc"], ["110164", "barcode"]]);
  });

  it("reports a collision and does not import the second tag", () => {
    const p = plan({ assets: [asset("1", "110164"), asset("2", "110164")] });
    expect(p.tags.filter((t) => t.value === "110164")).toHaveLength(1);
    expect(p.issues.find((i) => i.code === "tag_collision")!.legacyId).toBe("2");
    expect(p.assets).toHaveLength(2); // the asset itself is still imported
  });

  it("collides across assets, containers and locations alike", () => {
    const p = plan({ locations: [loc("l", "555", "Bin")], assets: [asset("1", "555")] });
    expect(codes(p)).toContain("tag_collision");
  });

  it("warns for short dashed ids and for values that look like new printed codes", () => {
    const p = plan({ assets: [asset("1", "12-34"), asset("2", "A000123"), asset("3", "")] });
    expect(codes(p)).toEqual(expect.arrayContaining(["tag_not_nfc", "tag_like_new_code", "no_tag"]));
  });
});

describe("containers and placement", () => {
  const d = (assets: Row[], containers: Row[] = [container("c1", "CT1")], locations: Row[] = [loc("l1", "L1", "Bin")]) => plan({ assets, containers, locations });
  const parentOf = (p: ReturnType<typeof plan>, id: string) => p.assets.find((a) => a.legacyId === id)!;

  it("imports containers as containers with their tag and category", () => {
    const p = d([]);
    expect(p.assets[0]).toMatchObject({ isContainer: true, category: "Cases & Bags" });
    expect(p.categories.find((c) => c.name === "Cases & Bags")!.defaultIsContainer).toBe(true);
  });

  it("resolves container_id by the container's primary key", () => {
    const p = d([asset("a1", "T1", { container_id: "c1" })]);
    expect(parentOf(p, "a1").parentAssetId).toBe(p.assets.find((a) => a.legacyId === "c1")!.id);
    expect(p.report.containerRefResolvedBy).toEqual({ "inventory_containers.id": 1 });
  });

  it("resolves container_id by the container's tag text (answers the brief's open question)", () => {
    const p = d([asset("a1", "T1", { container_id: "CT1" })]);
    expect(parentOf(p, "a1").parentAssetId).toBeTruthy();
    expect(p.report.containerRefResolvedBy).toEqual({ "inventory_containers.container_id (tag)": 1 });
  });

  it("uses the location when there is no container, and the container wins when both are set", () => {
    const p = d([asset("a1", "T1", { current_location_id: "l1" }), asset("a2", "T2", { container_id: "c1", current_location_id: "l1" })]);
    expect(parentOf(p, "a1").parentLocationId).toBeTruthy();
    expect(parentOf(p, "a2")).toMatchObject({ parentLocationId: null });
    expect(parentOf(p, "a2").parentAssetId).toBeTruthy();
    expect(codes(p)).toContain("both_parents");
  });

  it("falls back to the location when the container is missing or is not a container", () => {
    const p = d([asset("a1", "T1", { container_id: "nope", current_location_id: "l1" }), asset("a2", "T2"), asset("a3", "T3", { container_id: "a2", current_location_id: "l1" })]);
    expect(parentOf(p, "a1")).toMatchObject({ parentAssetId: null });
    expect(parentOf(p, "a1").parentLocationId).toBeTruthy();
    expect(parentOf(p, "a3").parentAssetId).toBeNull();
    expect(codes(p)).toEqual(expect.arrayContaining(["orphan_container_ref", "parent_not_container"]));
  });

  it("reports unknown locations", () => {
    expect(codes(d([asset("a1", "T1", { current_location_id: "ghost" })]))).toContain("orphan_location_ref");
  });

  it("derives an asset's effective location through its container", () => {
    const p = d([asset("a1", "T1", { container_id: "c1" })], [container("c1", "CT1", { current_location_id: "l1" })]);
    const eff = plannedEffectiveLocations(p);
    expect(eff.get(parentOf(p, "a1").id)).toBe(p.locations[0]!.id);
  });

  it("lists probable duplicates between containers and Bag / Hard Case assets without merging", () => {
    const p = d([asset("a1", "T1", { name: "Case c1" }), asset("a2", "CT1", { name: "Other bag" }), asset("a3", "T3", { name: "Camera" })]);
    expect(p.report.probableDuplicates.map((x) => x.reason).sort()).toEqual(["same name", "same tag"]);
    expect(p.assets.filter((a) => !a.isContainer)).toHaveLength(3);
  });
});

describe("categories, custom data and photos", () => {
  it("keeps type, size, cost, notes and photos, and flags 'Other' and missing types", () => {
    const p = plan({ assets: [asset("a1", "T1", { category: null, type: null, size: "L", cost: 99.5, notes: "n", photo_urls: ["https://x/1.jpg", "https://x/2.jpg"], serial_number: "SN1" })] });
    const a = p.assets[0]!;
    expect(a.category).toBe("Other");
    expect(a).toMatchObject({ notes: "n", serial: "SN1" });
    expect(a.custom).toMatchObject({ legacy_id: "a1", size: "L", cost: 99.5, legacy_tag: "T1" });
    expect(p.report.categoriesToClean).toEqual([{ category: "Other", count: 1 }]);
    expect(p.report.photos).toEqual([{ legacyId: "a1", name: "Asset a1", urls: ["https://x/1.jpg", "https://x/2.jpg"] }]);
    expect(codes(p)).toContain("no_type");
  });

  it("accepts photo_urls as a JSON string or a comma separated string", () => {
    const p = plan({ assets: [asset("a1", "T1", { photo_urls: '["https://x/1.jpg"]' }), asset("a2", "T2", { photo_urls: "https://x/a.jpg, https://x/b.jpg" })] });
    expect(p.report.photos.map((x) => x.urls.length)).toEqual([1, 2]);
  });

  it("copies last known position and time", () => {
    const p = plan({ assets: [asset("a1", "T1", { last_known_lat: "3.1", last_known_lng: 101.6, last_known_at: "2026-10-01T00:00:00Z" })] });
    expect(p.assets[0]).toMatchObject({ lastLat: 3.1, lastLng: 101.6, lastSeenAt: "2026-10-01T00:00:00.000Z" });
  });
});

describe("checked out assets become open checkouts", () => {
  const out = (id: string, extra: object) => asset(id, `T${id}`, { status: "checked_out", ...extra });

  it("creates one checkout per renter and due date, with a line per asset", () => {
    const p = plan({ assets: [out("a1", { renter: "Jamie", rental_due_date: "2026-11-03" }), out("a2", { renter: "Jamie", rental_due_date: "2026-11-03" }), out("a3", { renter: "Sam", rental_due_date: "2026-11-03" })] });
    expect(p.checkouts).toHaveLength(2);
    expect(p.checkoutItems).toHaveLength(3);
    expect(p.checkouts[0]).toMatchObject({ reason: "Legacy import: Jamie", dueAt: "2026-11-03T23:59:59.000Z" });
  });

  it("falls back to expected_return_date, and flags a missing due date", () => {
    const p = plan({ assets: [out("a1", { renter: "X", expected_return_date: "2026-12-01" }), out("a2", { renter: "X" })] });
    expect(p.checkouts.map((c) => c.dueAt)).toEqual(["2026-12-01T23:59:59.000Z", null]);
    expect(p.issues.filter((i) => i.code === "checkout_no_due").map((i) => i.legacyId)).toEqual(["a2"]);
  });

  it("uses the latest checkout event as the checkout date and links it", () => {
    const p = plan({
      assets: [out("a1", { renter: "X", rental_due_date: "2026-11-03" })],
      events: [
        { id: "e1", asset_id: "a1", event_type: "checkout", created_at: "2026-09-01T00:00:00Z" },
        { id: "e2", asset_id: "a1", event_type: "checkout", created_at: "2026-10-01T00:00:00Z" },
      ],
    });
    expect(p.checkoutItems[0]!.checkedOutAt).toBe("2026-10-01T00:00:00.000Z");
    expect(p.events.find((e) => e.legacyId === "e2")!.checkoutId).toBe(p.checkouts[0]!.id);
    expect(p.events.find((e) => e.legacyId === "e1")!.checkoutId).toBeNull();
  });

  it("does not cascade a container's checkout to its contents", () => {
    const p = plan({ containers: [container("c1", "C1", { status: "checked_out", renter: "X" })], assets: [asset("a1", "T1", { container_id: "c1" })] });
    expect(p.checkoutItems).toHaveLength(1);
    expect(p.assets.find((a) => a.legacyId === "a1")!.status).toBe("available");
  });
});

describe("events", () => {
  const A = asset("a1", "110164");
  const ev = (extra: object) => ({ id: "e1", asset_id: "a1", event_type: "created", created_at: "2026-01-02T03:04:05Z", ...extra });

  it("maps the event types and keeps original timestamps and the performer", () => {
    const rows = [
      ev({ id: "e1", event_type: "created" }), ev({ id: "e2", event_type: "checkin" }), ev({ id: "e3", event_type: "checkout" }),
      ev({ id: "e4", event_type: "edited" }), ev({ id: "e5", event_type: "inquiry", performed_by: "Alex" }),
    ];
    const p = plan({ assets: [A], events: rows });
    expect(p.events.map((e) => e.type)).toEqual(["created", "checked_in", "checked_out", "edited", "seen"]);
    expect(p.events[0]!.occurredAt).toBe("2026-01-02T03:04:05.000Z");
    expect(p.events[4]!.payload).toMatchObject({ legacy_inquiry: true, legacy_performer: "Alex" });
  });

  it("decides location events: placed when a location is known, otherwise seen", () => {
    const p = plan({
      locations: [loc("l1", "L1", "Bin")], assets: [A],
      events: [ev({ id: "e1", event_type: "location", location_id: "l1" }), ev({ id: "e2", event_type: "location", gps_lat: 3.1, gps_lng: 101.6 })],
    });
    expect(p.events.map((e) => e.type)).toEqual(["placed_in_location", "seen"]);
    expect(p.events[0]!.toLocationId).toBe(p.locations[0]!.id);
    expect(p.events[1]).toMatchObject({ lat: 3.1, lng: 101.6 });
  });

  it("finds the asset by primary key or by tag, and reports the orphans and the unmapped", () => {
    const p = plan({ assets: [A], events: [ev({ id: "e1", asset_id: "a1" }), ev({ id: "e2", asset_id: "110164" }), ev({ id: "e3", asset_id: "ghost" }), ev({ id: "e4", event_type: "teleport" }), ev({ id: "e5", created_at: null })] });
    expect(p.events).toHaveLength(2);
    expect(p.report.assetRefResolvedBy).toMatchObject({ id: 2, tag: 1, unresolved: 1 });
    expect(codes(p)).toEqual(expect.arrayContaining(["orphan_event", "unknown_event_type", "event_no_time"]));
  });

  it("maps container events", () => {
    const p = plan({
      containers: [container("c1", "CT1")], assets: [A], locations: [loc("l1", "L1", "Bin")],
      containerEvents: [
        { id: "k1", container_id: "c1", asset_id: "a1", event_type: "asset_added", created_at: "2026-01-01T00:00:00Z" },
        { id: "k2", container_id: "c1", asset_id: "a1", event_type: "asset_removed", created_at: "2026-01-02T00:00:00Z" },
        { id: "k3", container_id: "c1", event_type: "location_update", location_id: "l1", created_at: "2026-01-03T00:00:00Z" },
        { id: "k4", container_id: "c1", event_type: "check_out", created_at: "2026-01-04T00:00:00Z" },
        { id: "k5", container_id: "c1", event_type: "check_in", location_id: "l1", created_at: "2026-01-05T00:00:00Z" },
        { id: "k6", container_id: "c1", event_type: "created", created_at: "2026-01-06T00:00:00Z" },
      ],
    });
    const container1 = p.assets.find((a) => a.legacyId === "c1")!.id;
    const member = p.assets.find((a) => a.legacyId === "a1")!.id;
    expect(p.events.map((e) => [e.type, e.assetId === container1 ? "container" : "member"])).toEqual([
      ["placed_in_container", "member"], ["removed_from_container", "member"], ["placed_in_location", "container"],
      ["checked_out", "container"], ["checked_in", "container"], ["created", "container"],
    ]);
    expect(p.events[0]!.toAssetId).toBe(container1);
    expect(p.events[0]!.assetId).toBe(member);
  });

  it("gives events stable ids", () => {
    const a = plan({ assets: [A], events: [ev({})] }).events[0]!.id;
    const b = plan({ assets: [A], events: [ev({})] }).events[0]!.id;
    expect(a).toBe(b);
  });
});

describe("audits", () => {
  it("maps audits and items, skipping unknown results and duplicates", () => {
    const p = plan({
      locations: [loc("l1", "L1", "Bin")], assets: [asset("a1", "T1"), asset("a2", "T2")],
      audits: [{ id: "u1", location_id: "l1" }, { id: "u2", location_id: "ghost" }],
      auditItems: [
        { id: "i1", audit_id: "u1", asset_id: "a1", status: "found" }, { id: "i2", audit_id: "u1", asset_id: "a2", status: "MISSING" },
        { id: "i3", audit_id: "u1", asset_id: "a1", status: "found" }, { id: "i4", audit_id: "u1", asset_id: "a2", status: "???" }, { id: "i5", audit_id: "u2", asset_id: "a1", status: "found" },
      ],
    });
    expect(p.audits).toHaveLength(1);
    expect(p.auditLines.map((l) => l.result)).toEqual(["found", "missing"]);
    expect(codes(p)).toEqual(expect.arrayContaining(["audit_no_location", "duplicate_audit_item", "unknown_audit_result", "orphan_audit_item"]));
  });
});

describe("the fixture export", () => {
  const p = buildPlan(buildFixture(), { columns: DEFAULT_COLUMNS, now: new Date("2026-11-01T00:00:00Z") });
  const count = (code: string) => p.issues.filter((i) => i.code === code).length;

  it("has the shape described in the brief", () => {
    expect(p.report.legacyCounts).toMatchObject({ inventory_assets: 77, inventory_containers: 6, inventory_locations: 29, inventory_events: 741, inventory_container_events: 148, inventory_audits: 2 });
    expect(p.assets.filter((a) => a.legacyTable === "inventory_assets" && a.status === "checked_out")).toHaveLength(23);
    expect(p.assets.filter((a) => a.isContainer)).toHaveLength(6);
    expect(p.report.photos).toHaveLength(16);
    expect(p.report.categoriesToClean).toEqual([{ category: "Other", count: 45 }]);
  });

  it("catches every deliberate quirk", () => {
    expect(count("tag_collision")).toBe(1);
    expect(count("parent_not_container")).toBe(1);
    expect(count("orphan_container_ref")).toBe(1);
    expect(count("both_parents")).toBeGreaterThanOrEqual(1);
    expect(count("orphan_event")).toBeGreaterThanOrEqual(1);
    expect(count("event_no_time")).toBe(1);
    expect(count("location_linked_asset")).toBe(1);
    expect(count("probable_duplicate")).toBeGreaterThanOrEqual(1);
    expect(count("checkout_no_due")).toBe(1);
  });

  it("resolves container references both ways, so the report shows which the old system used", () => {
    expect(Object.keys(p.report.containerRefResolvedBy)).toEqual(expect.arrayContaining(["inventory_containers.id", "inventory_containers.container_id (tag)"]));
  });

  it("is deterministic", () => {
    const again = buildPlan(buildFixture(), { columns: DEFAULT_COLUMNS, now: new Date("2026-11-01T00:00:00Z") });
    expect(again.assets.map((a) => a.id)).toEqual(p.assets.map((a) => a.id));
    expect(again.events.map((e) => e.id)).toEqual(p.events.map((e) => e.id));
  });

  it("has no planned parent loops and every parent exists", () => {
    const ids = new Set(p.assets.map((a) => a.id));
    for (const a of p.assets) {
      if (a.parentAssetId) expect(ids.has(a.parentAssetId)).toBe(true);
      expect(a.parentAssetId && a.parentLocationId).toBeFalsy();
    }
  });
});

describe("report", () => {
  it("never claims a pass for a plan-only run, and lists errors before warnings before info", async () => {
    const { buildReconciliation, renderMarkdown } = await import("../src/report");
    const p = buildPlan(buildFixture(), { columns: DEFAULT_COLUMNS, now: new Date("2026-11-01T00:00:00Z") });
    const r = buildReconciliation(p, "plan-only", []);
    expect(r.passed).toBeNull();
    expect(renderMarkdown(r)).toContain("NOT CHECKED");
    expect(renderMarkdown(r)).not.toContain("**PASS**");
    const order = r.issuesByCode.map((i) => i.severity);
    expect(order).toEqual([...order].sort((a, b) => ({ error: 0, warn: 1, info: 2 } as Record<string, number>)[a]! - ({ error: 0, warn: 1, info: 2 } as Record<string, number>)[b]!));
  });
});
