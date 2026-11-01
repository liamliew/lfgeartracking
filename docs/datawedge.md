# Scanner input (DataWedge, NFC, debug)

## Status
| Piece | State |
|---|---|
| One input flow `ScanInputBus` (normalise -> 1.5 s debounce -> emit) | done, unit tested |
| Scan broadcast receiver (`ScanInputEffect`) | done; **not yet exercised on a device** |
| NFC reader mode (`NfcReader`) | done; **not yet exercised on a device** |
| Debug scan button (debug builds), Diagnostics "Scanner test" log | done |
| DataWedge profile `GearTracker` auto-creation (`SET_CONFIG`) | **pending: official DataWedge docs not yet read** |
| On-screen soft scan button (`SOFT_SCAN_TRIGGER`) | **pending** |
| `SEND_RESULT` / `COMMAND_IDENTIFIER` results on Diagnostics, "Recreate profile" | **pending** |

`techdocs.zebra.com` is not in the cloud session's allowed domains, so the DataWedge API pages could not be read. The rules say docs are read before platform code, so the profile code is not written from memory. Unblock: add `techdocs.zebra.com` to the environment's allowed domains.

## Flow
`DataWedge / NFC / debug panel / manual` -> `ScanInputBus.submit` -> normalise (barcodes: strip control chars, trim; NFC: uppercase hex, no separators) -> debounce (same value within 1.5 s ignored) -> `scans` flow (sessions collect it) and `log` (Diagnostics).

## Receiver
- Action `com.lfcreative.geartracker.SCAN`; extras `com.symbol.datawedge.data_string`, `com.symbol.datawedge.label_type` (from the spec and the adb command in CLAUDE.md).
- Context-registered, only while a scan screen is RESUMED, `RECEIVER_EXPORTED` (the sender is another app: Android's broadcast guidance says to export in that case). Consequence: any app on the device can inject a scan. Acceptable on managed, single-purpose Zebra devices; revisit if the app ever runs on shared devices.
- The same receiver handles the real DataWedge broadcast and the adb simulation, so one command works on the emulator and on a Zebra.

## NFC
Standard `enableReaderMode` with `FLAG_READER_NFC_A|B|F|V|SKIP_NDEF_CHECK|NO_PLATFORM_SOUNDS` (values checked against `android.jar`) while a scan screen is resumed, disabled on pause. Identity is `Tag.id` as uppercase hex. The callback runs off the main thread (the bus is thread safe). We never read MIFARE Classic sectors. Recommended tags: NTAG213/215/216.

## Part 2 plan (needs the DataWedge docs)
Profile `GearTracker`, created idempotently on first launch with `CREATE_IF_NOT_EXIST`:
- `APP_LIST`: `com.lfcreative.geartracker` and `.debug`, activity `*`
- Decoders: Code 128, QR, Data Matrix only (EAN-13 optional)
- Intent output: action `com.lfcreative.geartracker.SCAN`, category DEFAULT, delivery = broadcast
- Keystroke output off; `SEND_RESULT` + `COMMAND_IDENTIFIER`, results shown on Diagnostics
- On-screen scan button: soft trigger START on press, STOP on release. Do not mix EMDK barcode APIs with DataWedge.

## Testing
Emulator: `adb shell am broadcast -a com.lfcreative.geartracker.SCAN --es com.symbol.datawedge.data_string "A000123" --es com.symbol.datawedge.label_type "LABEL-TYPE-CODE128" -p com.lfcreative.geartracker.debug`, with Settings open (Scanner test shows OK / IGNORED). Zebra: open Settings -> Scanner test, tap an NFC tag, expect an `NFC` row with uppercase hex.
