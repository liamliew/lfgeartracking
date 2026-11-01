import type { LegacyExport, Row } from "./types";

/**
 * Hand-made stand-in for the legacy export, with the counts and quirks described in the brief:
 * 77 assets (54 available, 23 checked out, 21 inside a container, 3 serials, 16 photos, 45 "Other"),
 * 6 containers (4 available, 2 out), 29 locations, 741 + 148 events, 2 audits. Deterministic.
 * Column names are the ASSUMED ones in DEFAULT_COLUMNS; the real export may differ (see docs/legacy-migration.md).
 */
function prng(seed: number) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

const pad = (n: number, w = 3) => String(n).padStart(w, "0");
const uuid = (kind: string, n: number) => `${kind}-0000-4000-8000-${pad(n, 12)}`;
const hexByte = (r: () => number) => Math.floor(r() * 256).toString(16).padStart(2, "0").toUpperCase();

export function buildFixture(): LegacyExport {
  const r = prng(20261101);
  const day = (offset: number) => new Date(Date.UTC(2026, 0, 1) + offset * 86_400_000).toISOString();

  // ---- locations (29) --------------------------------------------------------------------------------------------
  const locations: Row[] = [];
  const loc = (id: string, code: string, name: string, parent: string | null, linked: string | null = null) =>
    locations.push({ id, code, name, parent_id: parent, kind: null, linked_asset_id: linked });
  loc("loc-sav", "SAV1", "Savanna", null);
  loc("loc-yf", "YF02", "YF House", null);
  loc("loc-loan", "10100", "Loan Out", null);
  loc("loc-wh", "10101", "Warehouse", null);
  loc("loc-event", "10102", "Event Site", null);
  loc("loc-s1", "10110", "Storage 1", "loc-sav");
  loc("loc-s2", "10111", "Storage 2", "loc-sav");
  loc("loc-s2b", "10112", "Storage 2B", "loc-sav");
  ["YF Office", "YF Studio", "YF Garage"].forEach((n, i) => loc(`loc-yf${i}`, `1012${i}`, n, "loc-yf"));
  const trays: string[] = [];
  [["loc-s1", "1", "10137"], ["loc-s2", "2", "10200"], ["loc-s2b", "3", "10300"]].forEach(([parent, n, base]) => {
    "ABCDEF".split("").forEach((letter, i) => {
      const id = `loc-t${n}${letter}`;
      trays.push(id);
      // the first tray of Storage 1 carries the numeric code from the brief
      loc(id, String(Number(base) + i), `Tray ${n}${letter}`, parent!, id === "loc-t3F" ? "ctr-0006" : null);
    });
  });

  // ---- containers (6): 4 available, 2 checked out ----------------------------------------------------------------
  const containers: Row[] = [];
  const containerNames = ["Pelican 1510 Case", "Pelican 1620 Case", "Camera Bag A", "Camera Bag B", "Hard Case", "Cable Pouch"];
  containerNames.forEach((name, i) => {
    const out = i >= 4;
    containers.push({
      id: `ctr-${pad(i + 1, 4)}`, container_id: i === 0 ? "04-46-88-0A-BF-2A-81" : `21000${i}`, name,
      status: out ? "checked_out" : "available", notes: null, current_location_id: out ? null : trays[i],
      renter: out ? "Rental House" : null, rental_due_date: out ? "2026-11-30" : null, expected_return_date: null, created_at: day(i),
    });
  });

  // ---- assets (77) ------------------------------------------------------------------------------------------------
  const assets: Row[] = [];
  const categories = ["Camera", "Lens", "Audio", "Lighting", "Grip"];
  const bagNames = ["Hard Case", "Bag", "Bag"]; // Bag (2) and Hard Case (1): the first matches a container name
  const tagFor = (i: number) => (i % 3 === 0
    ? `${hexByte(r)}-${hexByte(r)}-${hexByte(r)}-${hexByte(r)}-${hexByte(r)}-${hexByte(r)}-${hexByte(r)}`
    : String(110000 + i));
  for (let i = 1; i <= 77; i++) {
    const out = i <= 23;                       // the first 23 are checked out
    const inContainer = i > 23 && i <= 44;     // 21 sit inside a container
    const isBag = i >= 75;
    const other = i <= 45;                     // 45 are categorised "Other"
    const container = ((i - 24) % 4) + 1;      // containers 1..4 (available ones)
    assets.push({
      id: uuid("ast", i), asset_id: tagFor(i), name: isBag ? bagNames[i - 75] : `${categories[i % 5]} ${pad(i, 2)}`,
      category: other ? "Other" : categories[i % 5], type: i % 7 === 0 ? null : `type-${i % 5}`, size: i % 9 === 0 ? "L" : null, cost: i % 4 === 0 ? 100 + i : null,
      notes: i % 11 === 0 ? `note ${i}` : null, serial_number: [10, 20, 30].includes(i) ? `SN${i}` : null,
      photo_urls: (i % 5 === 0 || i === 2) ? [`https://old.example/p/${i}.jpg`] : null,
      status: out ? "checked_out" : "available",
      // alternate how containers are referenced, so the report shows both interpretations in use
      container_id: inContainer ? (i % 2 === 0 ? `ctr-${pad(container, 4)}` : String((containers[container - 1] as Row).container_id)) : null,
      current_location_id: inContainer ? null : out ? null : trays[i % trays.length],
      last_known_lat: i % 6 === 0 ? 3.139 + i / 1000 : null, last_known_lng: i % 6 === 0 ? 101.686 : null, last_known_at: i % 6 === 0 ? day(60 + i) : null,
      renter: out ? ["Studio A", "Rental House", "Jamie", "Client X"][i % 4] : null,
      rental_due_date: out && i !== 5 && i !== 6 ? `2026-1${(i % 2) + 0}-${pad(10 + (i % 15), 2)}`.replace("2026-10", "2026-10") : null,
      expected_return_date: out && i === 6 ? "2026-12-01" : null,
      created_at: day(i),
    });
  }
  // deliberate quirks ---------------------------------------------------------------------------------------------
  (assets[50] as Row).asset_id = (assets[51] as Row).asset_id;            // two assets share one tag
  (assets[60] as Row).container_id = "ast-0000-4000-8000-000000000003";    // parent is an asset, not a container
  (assets[60] as Row).current_location_id = trays[1];
  (assets[61] as Row).container_id = "ctr-9999";                           // container does not exist
  (assets[61] as Row).current_location_id = null;
  (assets[62] as Row).container_id = "ctr-0002"; (assets[62] as Row).current_location_id = trays[2]; // both parents

  // ---- events (741) -----------------------------------------------------------------------------------------------
  const events: Row[] = [];
  const types: [string, number][] = [["location", 332], ["checkin", 194], ["checkout", 182], ["created", 27], ["edited", 5], ["inquiry", 1]];
  const performers = ["Alex", "Sam", "Jordan", null];
  let ev = 0;
  for (const [type, count] of types) {
    for (let k = 0; k < count; k++) {
      ev++;
      const asset = assets[Math.floor(r() * 77)] as Row;
      const locEv = type === "location" || type === "checkin";
      events.push({
        id: `ev-${pad(ev, 4)}`, asset_id: ev % 2 === 0 ? asset.asset_id : asset.id, event_type: type, created_at: day(Math.floor(r() * 300)),
        performed_by: performers[ev % 4], location_id: locEv && r() > 0.2 ? trays[Math.floor(r() * trays.length)] : null,
        gps_lat: type === "location" && r() > 0.7 ? 3.14 : null, gps_lng: type === "location" && r() > 0.7 ? 101.69 : null, notes: type === "edited" ? "edited" : null,
      });
    }
  }
  (events[3] as Row).asset_id = "no-such-asset";                        // orphan event
  (events[4] as Row).created_at = null;                                  // event without a time

  // ---- container events (148) ------------------------------------------------------------------------------------
  const containerEvents: Row[] = [];
  const cTypes: [string, number][] = [["asset_added", 70], ["asset_removed", 26], ["location_update", 23], ["check_out", 17], ["created", 6], ["check_in", 6]];
  let cev = 0;
  for (const [type, count] of cTypes) {
    for (let k = 0; k < count; k++) {
      cev++;
      containerEvents.push({
        id: `cev-${pad(cev, 4)}`, container_id: `ctr-${pad((cev % 6) + 1, 4)}`, asset_id: type.startsWith("asset_") ? (assets[24 + (cev % 21)] as Row).id : null,
        event_type: type, created_at: day(Math.floor(r() * 300)), performed_by: performers[cev % 4], location_id: type === "location_update" ? trays[cev % trays.length] : null, notes: null,
      });
    }
  }

  const audits: Row[] = [
    { id: "aud-1", location_id: "loc-t1A", started_at: day(200), finished_at: day(200) },
    { id: "aud-2", location_id: "loc-t1B", started_at: day(210), finished_at: null },
  ];
  const auditItems: Row[] = [
    { id: "ai-1", audit_id: "aud-1", asset_id: (assets[30] as Row).id, status: "found" },
    { id: "ai-2", audit_id: "aud-1", asset_id: (assets[31] as Row).id, status: "missing" },
  ];
  return { assets, containers, locations, events, containerEvents, audits, auditItems };
}
