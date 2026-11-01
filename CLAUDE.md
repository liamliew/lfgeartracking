# Gear Tracker: agent guide
Full spec: `docs/plan.md`. If official docs contradict it, follow the docs and record an ADR in `docs/adr/`.

## Glossary
- **Asset**: a tracked item (`asset_code` A000123). **Container**: asset with `is_container` (C000045).
- **Location**: hierarchical (site > room > shelf > cabinet > bin), code L00312.
- **Tag**: barcode text or NFC UID (uppercase hex, no separators) -> exactly one asset or location.
- **Event**: append-only, client-generated UUID. **Projection**: current-state columns derived from events.

## Commands
- Android (`apps/android`): `./gradlew testDebugUnitTest lintDebug assembleDebug`
- Web (`apps/web`): `npm run typecheck && npm run lint && npm test && npm run build`
- DB: `supabase db reset` (migrations must apply from scratch); pgTAP in `supabase/tests`
- Simulate a scan on emulator:
  `adb shell am broadcast -a com.lfcreative.geartracker.SCAN --es com.symbol.datawedge.data_string "A000123" --es com.symbol.datawedge.label_type "LABEL-TYPE-CODE128" -p com.lfcreative.geartracker.debug`

## Architecture
UI -> ViewModel -> UseCase -> Repository -> (Room | Supabase). All inputs (DataWedge, NFC, Debug, Manual) emit `ScanInput(value, source, symbology, ts)` into ONE Flow. Scan-session state machines are pure Kotlin and table-tested. Crew devices write only via RPCs (`apply_scan_events`, `create_asset`).

## Rules
- Never edit an applied migration; add a new one. RLS on every table.
- No secrets in git; no service-role keys; no legacy DB credentials in cloud sessions. Never migrate credentials.
- One branch + PR per task (summary, docs consulted, test plan, "How to test in Android Studio / on a Zebra").
- Definition of Done: docs linked, local + CI green, APK artifact, tests, RLS, migrations from scratch, docs/CLAUDE.md updated.

## Docs
developer.android.com (Compose, Room, WorkManager, NfcAdapter.enableReaderMode) · techdocs.zebra.com/datawedge/latest · supabase.com/docs

## Environment gotchas
- Android SDK installed by `.claude/hooks/session-start.sh` (needs `dl.google.com`, `dl-ssl.google.com` allowed).
- GitHub proxy blocks branch deletion, tag pushes, GraphQL: use `gh api` (REST) instead of `gh pr`.
