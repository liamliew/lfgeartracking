# MASTER PROMPT: Gear Tracker (paste this whole file into Claude Code as the first message)

You are the lead engineer for **Gear Tracker**, a from-scratch asset and inventory tracking system for LF Creative's photo/video/AV gear. It replaces an older custom system. It has three parts:

1. A **native Android app** (Kotlin + Jetpack Compose) used ONLY on **Zebra mobile computers** (TC2x/TC5x/TC7x/EC5x, Android 11-14), using the built-in scanner (DataWedge) and NFC.
2. A **desktop admin website** (Vite + React + TypeScript).
3. A **self-hosted Supabase backend** (Postgres, Auth, RLS, Storage, Edge Functions) behind a Cloudflare Tunnel, plus a **one-off migration** of the legacy data.

This message is the full spec. Save it as `docs/plan.md` first, then work from it.

---

## 0. Working rules (non-negotiable)

1. **Read the official documentation before writing any platform code**, and link what you read in each PR:
   - developer.android.com: Compose, Room, WorkManager, `NfcAdapter.enableReaderMode`, BroadcastReceiver export flags
   - techdocs.zebra.com/datawedge/latest: Intent Output, `SET_CONFIG`, `SOFT_SCAN_TRIGGER`, Barcode Input
   - supabase.com/docs: RLS, database functions, CLI migrations, supabase-kt, supabase-js
   If the docs contradict this spec, follow the docs and record the decision in `docs/adr/`.
2. One feature branch and one PR per task. Each PR has: summary, docs consulted, test plan, screenshots for UI, and "How to test in Android Studio / on a Zebra".
3. Before pushing:
   - `apps/android`: `./gradlew testDebugUnitTest lintDebug assembleDebug`
   - `apps/web`: `npm run typecheck && npm run lint && npm test && npm run build`
   - Migrations must apply from scratch with `supabase db reset`.
4. **No secrets in git.** Use `local.properties` / `.env.local` (gitignored). Never ask for or use service-role keys, and never use credentials for the legacy database in this cloud session.
5. **Crew devices write only through RPCs** (`apply_scan_events`, `create_asset`). The app emits idempotent events with client-generated UUIDs. Business rules live in SQL.
6. Keep explanations of flows as step lists in `docs/` that can be turned into flowcharts (scan session, create-asset wizard, check in, check out, update, audit).

## 1. Cloud environment

The Android SDK is not preinstalled. Create `.claude/hooks/session-start.sh` that:
- installs the Android command-line tools into `$HOME/android-sdk`
- accepts licences and installs `platform-tools`, `platforms;android-35`, `build-tools;35.0.0`
- exports `ANDROID_HOME` and writes `sdk.dir` to `apps/android/local.properties`

Downloads need `dl.google.com`. If unreachable, STOP and tell me to set the environment's network access to **Custom**, keep "default package managers", and add `dl.google.com` and `dl-ssl.google.com`.

GitHub note: the proxy blocks branch deletion, tag pushes and GraphQL. Use `gh api` (REST) rather than `gh pr` subcommands if needed.

## 2. Repo layout

```
gear-tracker/
  CLAUDE.md   .claude/settings.json   .claude/hooks/session-start.sh
  apps/android/    (package com.lfcreative.geartracker, debug suffix .debug; open this folder in Android Studio)
  apps/web/
  supabase/        config.toml, migrations/, seed.sql, functions/, tests/ (pgTAP)
  scripts/legacy/  export.sql, import tooling, mapping docs
  packages/shared-types/   (supabase gen types)
  docs/            plan.md, architecture.md, scan-state-machine.md, datawedge.md, legacy-migration.md, adr/
  .github/workflows/  android.yml (uploads app-debug.apk as artifact), web.yml, db.yml
```

Android stack: Kotlin 2.x, version catalog, minSdk 30, targetSdk 35, Compose + Material 3, Navigation Compose, Hilt, Room, WorkManager, Coroutines/Flow, kotlinx.serialization, supabase-kt, CameraX (fallback scanning), Coil, play-services-location.
Web stack: Vite, React, TypeScript, shadcn/ui, TanStack Router/Query/Table, React Hook Form + Zod, supabase-js, bwip-js, Papa Parse, Vitest, Playwright smoke tests.

## 3. CLAUDE.md (write first, keep updated)

- Glossary: asset; container (an asset with `is_container`); location hierarchy; tag (barcode text or NFC UID, resolves to exactly one asset or location); event; projection.
- Build and test commands, and the emulator simulate-scan command:
  `adb shell am broadcast -a com.lfcreative.geartracker.SCAN --es com.symbol.datawedge.data_string "A000123" --es com.symbol.datawedge.label_type "LABEL-TYPE-CODE128" -p com.lfcreative.geartracker.debug`
- Architecture: UI -> ViewModel -> UseCase -> Repository -> (Room | Supabase). All inputs (DataWedge, NFC, Debug, Manual) emit `ScanInput(value, source, symbology, ts)` into ONE Flow. Scan-session state machines are pure Kotlin and table-tested.
- Migration rules (never edit an applied migration), Definition of Done, doc links, environment gotchas.

## 4. Architecture principles

1. **Events are truth, tables are projections.** Devices emit events; a server RPC applies them transactionally and updates current-state columns. Each event has a device-generated UUID, so retries are harmless (`on conflict (id) do nothing`).
2. **One tag lookup for everything.** Barcode text and NFC UIDs both normalise into `tags.value` (NFC: uppercase hex, no separators; barcode: trimmed). A tag points to exactly one asset or one location.
3. **Contained items inherit their container's location.** An item in a case stores only `parent_asset_id`. Effective location is derived by walking up the chain (recursive view `asset_effective_location` with `container_depth`).
4. **Offline-first, server has the final say.** Room caches tags, assets, locations, projects and open checkouts. An outbox of UUID events syncs via WorkManager with backoff. Rejected events go to an on-device "Sync issues" inbox. Anomalies go to a web conflicts report, never block scanning.

## 5. Data model (Postgres; implement as migration 0001, then refine as needed)

- Enums: `app_role (admin, manager, crew, viewer)`, `tag_kind (barcode, nfc)`, `asset_status (available, checked_out, in_repair, lost, retired)`, `event_type (created, tag_assigned, placed_in_location, placed_in_container, removed_from_container, checked_out, checked_in, seen, audited_found, audited_missing, status_changed, edited)`.
- `profiles` (id -> auth.users, display_name, role)
- `categories` (name, parent_id, `field_schema` jsonb, default_is_container) and `asset_templates` (name, category, defaults jsonb)
- `locations` (name, `kind` in site/room/shelf/cabinet/bin/vehicle/other, parent_id, trigger-maintained `path_cache`, archived_at). Arbitrary depth: bins in cabinets in shelves in rooms in sites.
- `assets`: `asset_code` unique (printed id: A000123), name, category_id, manufacturer, model, serial_number, `is_container`, status, `parent_location_id`, `parent_asset_id`, home_location_id, last_seen_at/by/lat/lng/accuracy_m, `custom` jsonb, notes, retired_at (soft delete), timestamps, generated `search` tsvector. Constraints: at most one parent (`num_nonnulls(parent_location_id, parent_asset_id) <= 1`), not self. GIN indexes on `search`, and trigram on name and serial.
- `tags` (`value` pk, kind, asset_id, location_id, retired_at; exactly one target)
- `asset_photos`, `projects` (code, name, starts_on, ends_on)
- `checkouts` (project_id, reason, due_at, created_by, closed_at; project OR reason required) and `checkout_items` (checked_out_at, returned_at, returned_to_location_id). Unique partial index: one open checkout line per asset.
- `scan_sessions` (id, mode, device_id, user_id, started_at, ended_at)
- `asset_events` (append-only: client UUID pk, asset_id, type, session_id, to_location_id, to_asset_id, checkout_id, lat/lng, payload jsonb, occurred_at, received_at, user_id, device_id). `revoke update, delete` from authenticated.
- `audits` and `audit_lines` (found / missing / unexpected)
- **Cycle prevention:** BEFORE INSERT/UPDATE triggers on `assets.parent_asset_id` and `locations.parent_id` using a recursive CTE; cap depth at 10. Containers may nest (pouch in case) but never inside themselves or their descendants.
- **RPCs (security definer):**
  - `resolve_tag(value)` -> kind (asset/container/location/unknown), id, name, status, effective location, open checkout
  - `apply_scan_events(jsonb)` -> idempotent; applies in `occurred_at` order; per-event accept/reject with a reason (e.g. "would create cycle")
  - `create_asset(jsonb)` -> asset + tag + first placement atomically
- **RLS:** viewer read-only; crew read + scan/create RPCs only (no direct writes to projection tables); manager edits assets, locations, projects and closes checkouts; admin manages users, categories, templates and settings.
- **Search:** `websearch_to_tsquery` + trigram on name/serial + exact match on `tags.value` / `asset_code`.
- `overdue_checkout_items` view (open lines with `due_at < now()`).
- `seed.sql`: 3 sites; a shelf -> cabinet -> bin hierarchy; 5 categories; ~60 assets including 6 containers (one nested); barcode and NFC tags; 2 projects; 1 overdue checkout. pgTAP tests for cycles, RLS per role, idempotency.

## 6. Scan-session engine (the core behaviour)

**Pipeline for every mode:** input -> normalise -> debounce (same value within 1.5 s ignored) -> resolve from Room (RPC fallback when online, otherwise "unknown") -> mode state machine -> feedback -> append event to outbox -> UI updates immediately.

**Feedback:** accepted = green + short high beep + 1 short vibrate; target set/switched = blue banner + rising two-tone + 2 short; target closed = banner greys + falling two-tone + 1 long; duplicate = amber + low blip; needs decision = amber card + double low + 2 long; error/unknown = red + buzz + 3 short. Always colour + icon + text.

**Pack / Move (containers and storage locations):**

```
IDLE      --scan item-->                   BASKET [add]
IDLE      --scan container/location-->     TARGET(t)
BASKET    --scan item-->                   BASKET [add; duplicate -> amber]
BASKET    --scan container/location-->     IDLE [commit basket INTO t; toast "5 items -> Bin 7"]
BASKET    --Clear-->                       IDLE [confirm discard]
TARGET(t) --scan item-->                   TARGET(t) [place item in t; removes it from its previous parent]
TARGET(t) --scan t again-->                IDLE [close target]
TARGET(t) --scan other target u-->         TARGET(u) [SWITCH with distinct cue; 6 s snackbar "Put u inside t instead?" -> NEST]
TARGET(t) --Done / Back-->                 IDLE
TARGET(t) --idle > N s (default 90)-->     IDLE [warn at N-10 s]
TARGET(t) --scan ancestor of t-->          reject (cycle), red
any       --Undo-->                        previous state [compensating event logged]
any       --unknown tag-->                 sheet: Create asset / Assign to existing / Create location / Ignore
```

Rules: switching is the default and nesting is explicit (this is how "stop putting items into the container when the user moves on" works). The target banner is always visible (type icon, name, path, count added, countdown ring for the last 10 s). Moving a container moves its contents because location is derived.

**Check In:** scan items/containers into a basket (each row shows "was out on Project X, due 3 Nov"), then scan a location to place them all there, closing their checkout lines and setting them available. Location-first also works (location becomes the target; each later item is checked in at once, same re-scan/timeout/switch rules). Done with no location -> ask "Return to home locations?" or "Pick location". A container checks in its contents with a summary ("8 items, 1 missing vs. last pack").

**Check Out:** scan items/containers into a cart (a container brings its contents; item already out -> amber "transfer?"; in repair/retired -> red, blocked). Next -> return date (chips Tomorrow / +3 d / +1 wk, date picker, default project end date) -> project (recent first, searchable) or free-text reason -> Confirm creates checkout, lines and events. Cart persists offline.

**Update (on site, "last seen"):** get a fused location fix, show "Seen at: [site] +-25 m", optional venue label. Each scan logs a `seen` event (lat/lng/accuracy/time/user). Containers mark contents "seen via container". Changes only `last_seen_*`, never checkout or storage placement. No GPS -> "no fix" plus label.

**Audit:** scan or pick a location -> load expected contents of the subtree -> scan what is there -> Found / Expected-not-found / Unexpected (with "move here") -> Finish records `audited_missing`; managers confirm "mark lost" on the web.

**Create-asset wizard (mobile; each step minimal):**
1. Tag: scan a barcode, tap an NFC tag, or "generate code, print later". If the tag already exists, show that asset instead.
2. Template/category: recent templates as big chips; **"Same as last"** pre-fills everything from the previous asset.
3. Identity: name (pattern like "SM58 #{n}"), serial (scannable), manufacturer/model, "Is container?" toggle.
4. Photo (optional, multiple).
5. Required custom fields from the category schema.
6. Location: scan where it lives (default: last used).
7. Confirm: **Create** or **Create & next** (loops to step 1, keeping everything except tag and serial).
Bulk fast path: tag -> serial -> create (3 trigger pulls, 1 tap). Reachable from any session via "unknown tag", and returns to the same session afterwards.

## 7. Zebra integration

**DataWedge profile `GearTracker`**, created idempotently on first launch via `SET_CONFIG` with `CREATE_IF_NOT_EXIST`:
- `APP_LIST`: `com.lfcreative.geartracker` and `.debug`, activity `*`
- Decoders: Code 128, QR, Data Matrix only (EAN-13 optional)
- Intent Output: action `com.lfcreative.geartracker.SCAN`, category DEFAULT, delivery = broadcast
- Keystroke output off; `SEND_RESULT` + `COMMAND_IDENTIFIER`, with results shown on a Diagnostics screen

**Receiver:** context-registered, active only while a scan screen is resumed, explicitly flagged exported (Android 14), reads `com.symbol.datawedge.data_string`, `label_type`, `source`. On-screen scan button sends `SOFT_SCAN_TRIGGER` START on press, STOP on release. DataWedge absent -> banner plus debug panel (optional CameraX + ML Kit fallback). Do not use EMDK barcode APIs alongside DataWedge.

**NFC:** NFC is not a DataWedge input. Use standard Android `enableReaderMode` with `FLAG_READER_NFC_A|B|F|V|SKIP_NDEF_CHECK|NO_PLATFORM_SOUNDS` while a scan screen is foregrounded; disable in `onPause`. Identity = `tag.id` as uppercase hex, stored as `tags.value` with kind `nfc`. **Never depend on MIFARE Classic sector reads.** Recommended tags: NTAG213/215/216 (on-metal variants for cases).

**Debug tooling (build in M1):** debug-only floating Scan button (free text, recents, seed presets, "send as NFC" toggle); a debug receiver that also accepts the real DataWedge action so one adb command works on emulator and Zebra; Diagnostics screen with the raw last intents and DataWedge result payloads.

**Codes and labels:** prefixes `A000123` asset, `C000045` container, `L00312` location. Labels carry Code 128 + QR + human-readable code and name. The web app generates ZPL (50x25 mm asset, 25x12 mm small items, 100x50 mm locations) for Zebra printers via a small relay (TCP 9100) or Browser Print, with a PDF fallback.

## 8. UI rules for rugged handhelds

Touch targets >= 48 dp (primary actions 64-72 dp, glove-friendly); primary actions in the bottom third; last-scanned item in large text (>= 22 sp), list rows >= 56 dp; high contrast; scan-first (sessions need no touch except decisions); minimal typing (chips, pickers, recents, scan-to-fill); persistent session banner; confirm only destructive or ambiguous actions.

Screens: Login; Home (search/scan box, sync chip, big tiles: Pack/Move, Check In, Check Out, Update, Audit, New Asset, overdue count); each session screen; Asset Detail (photos, code/name, status, location breadcrumb incl. container chain, last seen, quick actions Move / Check out / Print label / Add NFC tag / Report damage, timeline); Search; Location browser (breadcrumb, children, jump by scanning a location label); Settings/Diagnostics (server URL, timeout, sound/vibration, DataWedge status + "Recreate profile", NFC status, sync queue, version, debug scan panel in debug builds).

## 9. Website (desktop admin)

Dashboard (out now, overdue, due this week, recent activity, conflicts, audit progress) · Assets table (server pagination, search by code/serial/name/tag, filters for category/status/location subtree/project/container/"last seen older than N days", saved views, bulk print labels/move/retire/recategorise) · Asset detail (history timeline with map pins, contents, tags, checkouts, photos) · Locations tree (drag-and-drop reparent, surface cycle errors, contents counts, label printing, start audit) · Projects and check-outs (extend due date, force return, overdue export) · Audits (found/missing/unexpected, confirm lost) · CSV import wizard (map -> validate -> dry run -> commit) · Labels (templates, batch queue, ZPL preview) · Admin (users/roles, categories with field schemas, templates, devices with last sync/app version, settings).

## 10. LEGACY DATA MIGRATION (old Supabase project "LF Creative", ref `vxgtywdqijtfnkgyubkh`)

I already inspected the old database read-only. Facts (do not assume beyond them; verify against the export):

| Old table | Rows | Notes |
|---|---|---|
| `inventory_assets` | 77 | 54 available, 23 checked out, 21 inside a container; only 3 with serial numbers, 16 with photos (`photo_urls`); 45 in category "Other" |
| `inventory_containers` | 6 | separate table (4 available, 2 checked out); there are also Bag (2) and Hard Case (1) assets, which may duplicate these |
| `inventory_locations` | 29 | `parent_id` hierarchy: Savanna(SAV1) -> Storage 1 / Storage 2 / Storage 2B -> Tray 1A..4F; YF House(YF02) root. Codes like `10137`, `SAV1` |
| `inventory_events` | 741 | location 332, checkin 194, checkout 182, created 27, edited 5, inquiry 1 |
| `inventory_container_events` | 148 | asset_added 70, asset_removed 26, location_update 23, check_out 17, created 6, check_in 6 |
| `inventory_audits` / `inventory_audit_items` | 2 / 2 | |
| `inventory_sessions` / `inventory_session_items` | 5 / 4 | transient; do not migrate |
| `inventory_team_members` | 2 | contains PINs and passwords: **never export or migrate credentials**; re-invite users via Supabase Auth |
| job tables (`inventory_jobs`, shoot days, job assets, media, team) | 0 | empty; ignore |

Legacy `asset_id` values are a mix of NFC UIDs written as dash-separated hex (e.g. `04-46-88-0A-BF-2A-81`) and plain numeric barcodes (e.g. `110164`). Old labels must keep working.

**Security boundary:** this cloud session must not hold credentials for the old database. So:
1. Write `scripts/legacy/export.sql`: read-only SELECTs for the inventory tables above (explicitly excluding `inventory_team_members` credential columns, `inventory_employee_settings`, and anything non-inventory). Document how I run it and save results as JSON/CSV into `scripts/legacy/export/` (I will supply the files; if they are missing, build against hand-made fixtures that mimic the shapes above).
2. Write the importer as a **TypeScript or SQL tool that reads those export files** and loads into the NEW database (local `supabase start` for development; I will run it against staging/production myself). It must be **idempotent**, support `--dry-run`, and print a reconciliation report.

**Mapping rules (propose, document in `docs/legacy-migration.md`, flag ambiguities rather than guessing):**
- Every legacy `asset_id` becomes a row in `tags`. If it matches dash-separated hex pairs, normalise to uppercase hex without separators and set kind `nfc`; otherwise kind `barcode` (trimmed). Generate a new `asset_code` (A000001...) per asset and store the old id in `custom.legacy_id` (plus a `legacy_id_map` table for traceability).
- `inventory_containers` -> assets with `is_container = true` (tags from their `container_id`). Detect overlap with Bag/Hard Case assets and report probable duplicates for my review; do not merge automatically.
- `inventory_assets.container_id` -> `parent_asset_id` (inspect whether it references `inventory_containers.id` or `.container_id`). Else `current_location_id` -> `parent_location_id`. Enforce the "at most one parent" rule and report violations.
- Locations: `code` -> barcode tag, `parent_id` -> `parent_id`, `kind` inferred (roots = site, "Storage*" = cabinet, "Tray*" = bin; show the proposal for my approval), `linked_asset_id` handled if non-null.
- Categories/types: create categories from legacy `category` + `type`, flag "Other" and null types for clean-up in the web UI; map `size`, `cost`, `notes` into `custom` / notes; `photo_urls` listed in a report (copy to Storage only if the URLs are fetchable; otherwise report).
- Status: `checked_out` -> create an open `checkouts` row (reason "Legacy import: <renter/user>", due = `rental_due_date` or `expected_return_date`, else flag) with `checkout_items`. Container checkouts cascade to contents only if the legacy data shows it.
- Last seen: `last_known_*` and `last_known_at` -> `last_seen_*`.
- Events: `created -> created`, `checkin -> checked_in`, `checkout -> checked_out`, `location -> placed_in_location`/`seen` (decide from `gps_*` and `location_id` and document it), `edited -> edited`, `inquiry` -> store in payload as `legacy_inquiry`. Container events: `asset_added -> placed_in_container`, `asset_removed -> removed_from_container`, `location_update -> placed_in_location`, `check_out/check_in` -> corresponding events. Preserve original timestamps in `occurred_at` and use deterministic UUIDs (e.g. UUIDv5 from legacy table + id) for idempotency. Preserve the legacy performer name in `payload`.
- Audits and audit items -> `audits` / `audit_lines` (result mapping documented).
- Reconciliation report (must pass before I run it for real): per-table counts in vs. out, assets by status, containers and their contents counts, orphan references, tag collisions, duplicates, anything skipped and why. Verify the post-import projection (status, location, container contents) equals the legacy snapshot.

## 11. Tasks now (M0 + M1 + migration tooling), in this order

1. Scaffold repo, CLAUDE.md, session-start hook, CI workflows (Android build + APK artifact, web, db).
2. Supabase migration 0001 per section 5, triggers, views, RPCs, RLS, seed.sql, pgTAP tests.
3. Android skeleton: Hilt, Room mirror + `outbox_events`, email/password auth, Home tiles, Settings/Diagnostics.
4. Scan input: DataWedge auto-config, receiver, soft-scan button, NFC reader mode, debug panel + receiver.
5. TagResolver (Room first, RPC fallback) + Pack/Move session with full table-driven transition tests + Compose screen (banner, list, Undo, Nest, Done, timeout ring) + feedback service.
6. Create-asset wizard ("Same as last", "Create & next"), Asset Detail, Search.
7. Outbox sync worker (WorkManager, batches, backoff, accepted/rejected, sync chip).
8. Legacy migration tooling: `export.sql`, importer with dry-run and reconciliation, `docs/legacy-migration.md`, tested on fixtures.

Later milestones (do not start until I ask):
- **M2:** Check Out, Check In, Update with GPS, offline guarantees.
- **M3:** Web admin v1, CSV import, ZPL/PDF labels, roles.
- **M4:** Audit mode, conflict report, sync-issue inbox, StageNow rollout, release signing, performance for 20k assets.
- **v2:** Expected vs. actual container contents and sealing, reservations, consumables, repairs, notifications, Enterprise Home Screen kiosk, in-app updater.

## 12. Every PR's test plan

Unit tests; emulator steps using seed codes (adb simulate-scan command); real-Zebra steps (profile `GearTracker` exists, trigger works, NFC works); offline test (airplane mode, 20 scans, reconnect, no duplicate events).

## 13. Definition of Done

Docs consulted and linked; local + CI green; APK artifact attached; new behaviour unit-tested; RLS on every new table; migrations apply from scratch; CLAUDE.md and docs updated; manual test plan for Android Studio and a Zebra.

## 14. Start now

Restate your plan for task 1 in 15 bullets or fewer, list any questions that block you (device models, label printer model, app id), then execute task 1.
