# Pack / Move scan session

Implemented as a pure Kotlin state machine: `session/PackMoveMachine.kt`, `reduce(state, input, nowMs) -> (state, effects)`.
No Android, clocks or I/O inside it. Every row below is a unit test in `PackMoveMachineTest`.

Containers and locations are **targets** (things items go INTO). Everything else is an **item**. Switching targets is the
default; nesting one container in another is always an explicit tap.

## Pipeline
`input (DataWedge / NFC / debug / manual)` -> `ScanInputBus` (normalise, 1.5 s debounce) -> `TagResolver`
(Room first, then the `resolve_tag` RPC, else Unknown) -> `PackMoveMachine` -> effects: feedback cue, outbox events, decision sheet -> UI.

## Transitions
| State | Input | Next state | Effects |
|---|---|---|---|
| Idle | item | Basket[item] | accepted |
| Idle | container / location | Target(t) | target set |
| Basket | item | Basket[+item] (repeat: unchanged) | accepted (repeat: duplicate) |
| Basket | container / location t | Idle | place every item in t (items already there are skipped, "N items -> t"); accepted. All already there: stays in Basket, duplicate |
| Basket | Clear | Idle | target closed |
| Target(t) | item | Target(t) | place item in t, accepted. Repeat, or already in t: duplicate, no event |
| Target(t) | t again | Idle | target closed ("closed t, N added") |
| Target(t) | other target u | Target(u) | target switched; if t is a container, offer **Nest** for 6 s |
| Target(t) | an ancestor of t | unchanged | error (would make a loop) |
| Target(t) | Nest (within 6 s) | Target(u) | place container t inside u (location or container), accepted |
| Target(t) | Done / Back | Idle | target closed |
| Target(t) | idle 80 s | Target(t) | idle warning (once) |
| Target(t) | idle 90 s | Idle | target closed. Both times come from Settings (default 90 s, warning 10 s before) |
| any | Undo | previous state | compensating events (item goes back to where it was), "undone" |
| any | unknown tag | unchanged | needs decision: sheet (Ignore works now; create / assign arrive with New Asset) |
| any | retired item / archived location | unchanged | error |

Undo history keeps the last 20 steps. Compensating events use what the session itself already changed, never the possibly stale cache.
A basket never times out.

## Events written
`placed_in_location` (to_location_id), `placed_in_container` (to_asset_id), `removed_from_container` (for an item that had no
placement). Each carries asset_id, session_id, session_mode `pack`, and a strictly increasing `occurred_at` so the server applies
a move and its undo in order. The server re-checks everything (cycles, depth, retired) in `apply_scan_events`; the device check is only for instant feedback.

## Feedback cues (colour + icon + text always; sound and vibration are extra)
| Kind | Colour | Sound | Vibration |
|---|---|---|---|
| accepted | green | high beep | 1 short |
| target set | blue | rising two-tone | 2 short |
| target switched | blue | rising three-tone | 3 short |
| target closed | grey | falling two-tone | 1 long |
| duplicate / idle warning | amber | low blip | none |
| needs decision | amber | double low | 2 long |
| error | red | buzz | 3 short |
| undone | grey | low blip | 1 short |

Tones are DTMF stand-ins; tune on a real Zebra.

## Known limits
- Offline, a tag that is not in the Room mirror resolves to Unknown (the mirror is filled by the sync worker, task 7). The server rejects a duplicate tag on create, so this cannot create duplicate tags.
- The Room mirror is not updated optimistically after a move; it refreshes on sync.
- Not yet run on a device.
