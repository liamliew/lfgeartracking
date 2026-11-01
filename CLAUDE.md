# Gear Tracker: agent guide
Full spec: `docs/plan.md`. If official docs contradict it, follow the docs and record an ADR in `docs/adr/`.

## Glossary
- **Asset**: a tracked item (`asset_code` A000123). **Container**: asset with `is_container` (C000045).
- **Location**: hierarchical (site > room > shelf > cabinet > bin), code L00312.
- **Tag**: barcode text or NFC UID (uppercase hex, no separators) -> exactly one asset or location.
- **Event**: append-only, client-generated UUID. **Projection**: current-state columns derived from events.

## Commands
- Android (`apps/android`): `./gradlew testDebugUnitTest lintDebug assembleDebug` (setup and backend config: `apps/android/README.md`; version pins and why: `docs/adr/0002-android-toolchain.md`, bump libraries together, not one at a time)
- Web (`apps/web`): `npm run typecheck && npm run lint && npm test && npm run build`
- DB: `supabase start && supabase db reset && supabase test db` (migrations must apply from scratch); pgTAP in `supabase/tests`
- DB without Docker (cloud sessions): `scripts/db/test-local.sh` (needs `postgresql-16`, `postgresql-16-pgtap`; see ADR 0001). CI is the source of truth.
- Legacy importer (`scripts/legacy`): `npm ci && npm run typecheck && npm test`; with a database: `scripts/legacy/test-local.sh` (throwaway PostgreSQL, no Docker). Runbook and rules: `docs/legacy-migration.md`.
- Simulate a scan on emulator:
  `adb shell am broadcast -a com.lfcreative.geartracker.SCAN --es com.symbol.datawedge.data_string "A000123" --es com.symbol.datawedge.label_type "LABEL-TYPE-CODE128" -p com.lfcreative.geartracker.debug`

## Architecture
UI -> ViewModel -> UseCase -> Repository -> (Room | Supabase). All inputs (DataWedge, NFC, Debug, Manual) emit `ScanInput(value, source, symbology, ts)` into ONE Flow. Scan-session state machines are pure Kotlin and table-tested. Crew devices write only via RPCs (`apply_scan_events`, `create_asset`).

## Rules
- Never edit an applied migration; add a new one. RLS on every table, and explicit GRANTs (config sets `auto_expose_new_tables = false`; `anon` gets nothing). New functions: `set search_path = ''`, schema-qualify everything, revoke execute from `public, anon`, put helpers in `private`.
- DB contracts (events, RPC inputs/outputs, RLS matrix): `docs/architecture.md`. pgTAP files must be self-contained (each runs in its own rolled-back transaction).
- Legacy data: never export or migrate credentials; exports and reports stay out of git (`scripts/legacy/export/`); the importer only writes through a database-owner connection and refuses the legacy project.
- No secrets in git; no service-role keys; no legacy DB credentials in cloud sessions. Never migrate credentials.
- One branch + PR per task (summary, docs consulted, test plan, "How to test in Android Studio / on a Zebra").
- Definition of Done: docs linked, local + CI green, APK artifact, tests, RLS, migrations from scratch, docs/CLAUDE.md updated.

## Docs
developer.android.com (Compose, Room, WorkManager, NfcAdapter.enableReaderMode) · techdocs.zebra.com/datawedge/latest · supabase.com/docs

## Environment gotchas
- Android SDK installed by `.claude/hooks/session-start.sh` (needs `dl.google.com`, `dl-ssl.google.com` allowed).
- Maven Central rate-limits parallel Gradle downloads (429) through the proxy; the session hook installs `~/.gradle/init.d/central-mirror.gradle.kts` to use Google's Central mirror first.
- Background work: WorkManager is initialised on demand (`GearTrackerApp` + Hilt worker factory; default initializer removed in the manifest). Details of the sync flow: `docs/sync.md`.
- No emulator or device in cloud sessions: Android changes are verified by unit tests, lint and the APK build only. Say so in the PR instead of claiming UI was tested.
- GitHub proxy blocks branch deletion, tag pushes, GraphQL: use `gh api` (REST) instead of `gh pr`.
