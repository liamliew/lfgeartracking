# Sync (offline first, server has the final say)

Code: `sync/` (`SyncEngine`, `MirrorSync`, `SyncWorker`, `SyncScheduler`). Pure logic is unit tested; the WorkManager wiring is not.

## What runs when (flow as steps)
1. **Trigger.** Any of: an event is queued (`OutboxRepository.enqueue` -> `SyncTrigger.requestSync`), a user signs in, the "Sync now" button, or the 15-minute periodic job (safety net).
2. **Schedule.** Unique work `sync-now` with `KEEP` (a request while one is waiting is ignored), network required, 2 s initial delay so a burst of scans becomes one batch, exponential backoff from 30 s. Periodic work `sync-periodic` every 15 min (the minimum), `KEEP`.
3. **Worker.** Does nothing while signed out (events wait on the device). Otherwise runs `SyncEngine`.
4. **Push.** Take up to 100 pending events, oldest `occurred_at` first, and call `apply_scan_events`. Repeat until none are left (max 50 batches per run, so one run drains the queue and `KEEP` is safe).
5. **Per event result.**
   - `accepted` / `duplicate` -> SENT (purged after 7 days).
   - `rejected` -> REJECTED with the server's reason. **Never retried.** Shown under Settings -> Sync issues; the Home chip turns into "N sync issue(s)". "Clear sync issues" removes them.
   - not answered, or the whole call failed (network, 5xx) -> stays PENDING, `attempts` + `lastError` updated, the worker returns `retry()` (WorkManager backoff). **No event is ever dropped.**
6. **Pull.** Only when nothing is left pending (otherwise stale server data would overwrite unsent moves), and at most every 5 minutes (or on "Sync now" / sign-in): read tags, assets, locations, projects and open checkouts (`get_open_checkouts`) in pages of 1000 (the API's `max_rows`), and only after ALL pages are read replace the Room tables in one transaction. A failure half-way leaves the old copy intact.
7. **Status.** Last push time, last mirror time and the last error are kept on the device and shown in Diagnostics.

## Why events are safe to retry
Each event has a client-generated UUID; `apply_scan_events` ignores ids it has already applied (`duplicate`). A response lost on the way back therefore just means the next attempt gets `duplicate` and marks it sent.

## Known limits
- The pull is a full refresh. Fine for today's sizes; incremental sync is planned for the 20k-asset performance work (M4).
- A tag created on another device appears here after the next pull (up to 5 minutes), so a scan in between falls back to the server (online) or Unknown (offline).
- `create_asset` is still online-only; queueing it is future work.
- Rejected events are shown as text; a proper Sync issues inbox (retry / discard per event) is M4.
- Not run on a device: WorkManager scheduling, the on-demand initializer and Hilt worker factory are covered by the build only.
