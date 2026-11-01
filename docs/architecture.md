# Architecture

Events are truth, tables are projections. Crew devices write only through RPCs; business rules live in SQL.

## Data model (migration `20261101090000_core_schema.sql`)

| Table | Purpose |
|---|---|
| `profiles` | one row per auth user; `role` = admin / manager / crew / viewer (new users default to viewer) |
| `categories`, `asset_templates` | admin-managed; `field_schema` jsonb drives required custom fields |
| `locations` | hierarchy of any depth (max 10), `code` L00312, trigger-maintained `path_cache` |
| `assets` | `asset_code` A000123 / C000045, one parent at most (`parent_location_id` xor `parent_asset_id`), soft delete via `retired_at` |
| `tags` | `value` PK. Barcode text (trimmed) or NFC UID (uppercase hex, no separators) -> exactly one asset or location |
| `checkouts`, `checkout_items` | one open line per asset (partial unique index) |
| `scan_sessions`, `asset_events` | events are append-only (trigger + no grants), client UUID primary key |
| `audits`, `audit_lines` | found / missing / unexpected |
| `asset_photos`, `projects` | |

Views: `asset_effective_location` (recursive, `container_depth`), `overdue_checkout_items`. Both are `security_invoker`.

Integrity enforced in triggers: cycle prevention for `assets.parent_asset_id` and `locations.parent_id`, depth cap 10, only containers can be parents, a container cannot be demoted while it holds items.

Every asset and location gets a barcode tag for its own printed code on insert, so a code scan and a tag scan are the same lookup.

## RPC contract (migration `20261101090100_rpcs.sql`)

All are callable by `authenticated` only. `apply_scan_events` and `create_asset` require crew or above.

### `resolve_tag(p_value text) -> jsonb`
`kind`: `asset` | `container` | `location` | `unknown`. Accepts legacy dash-separated NFC ids (`04-46-88-0A-BF-2A-81`) and lowercase hex. Assets also return `status`, `location` (derived through containers), `container`, `container_depth`, `open_checkout`.
Since migration `20261101090200`: every known tag also returns `placement {location_id, container_id}` (direct parent, for undo) and `ancestor_ids` (containers above an asset/container, or locations above a location, nearest first), so a device can reject loops instantly.

### `apply_scan_events(p_events jsonb) -> jsonb`
Input: array (max 500) of events:
`{id, type, asset_id, occurred_at, to_location_id?, to_asset_id?, checkout_id?, session_id?, session_mode?, lat?, lng?, accuracy_m?, device_id?, payload?}`.

- Events are applied in `occurred_at` order (ties: array order), each in its own subtransaction.
- Result: array of `{id, status: accepted|duplicate|rejected, reason?, code?}`. `code` is the SQLSTATE.
- `duplicate`: the event id was already applied (safe retry). Rejected events leave no trace.
- `accepted` with `reason`: `superseded` (a newer placement/status already exists; the event is recorded but the projection is untouched), `not_checked_out` (check-in of an item that was not out; never blocks scanning), `already_in_checkout`.
- The server stamps `user_id` and `received_at`; the client values are ignored.

| Event type | Effect | Who |
|---|---|---|
| `placed_in_location` | sets location, clears container | crew+ |
| `placed_in_container` | sets container, clears location (cycle/depth checked) | crew+ |
| `removed_from_container` | clears container, optional `to_location_id` | crew+ |
| `seen` | updates `last_seen_*`; a container also marks its contents | crew+ |
| `checked_out` | needs `checkout_id` and `payload.{project_id or reason, due_at}`; `payload.transfer=true` moves an item that is already out | crew+ |
| `checked_in` | closes the open line, status available, optional placement | crew+ |
| `status_changed` | `payload.status`; crew limited to available / in_repair | crew+ (manager+ for the rest) |
| `tag_assigned` | `payload.tag {value, kind}`; duplicate -> `tag_in_use` | crew+ |
| `edited` | `payload.patch` (name, notes, manufacturer, model, serial_number, category_id, home_location_id, custom) | manager+ |
| `audited_found`, `audited_missing` | history only | crew+ |
| `created` | refused; produced by `create_asset` | |

A container checkout does not cascade on the server: the app expands a container into one `checked_out` event per item.

### `create_asset(p jsonb) -> jsonb`
Input: `{id?, name, category_id?, manufacturer?, model?, serial_number?, is_container?, tag?{value,kind}, location_id?, home_location_id?, asset_code?, custom?, notes?, occurred_at?, device_id?}`.
Output `status`: `created` | `duplicate` (same `id`) | `rejected` with `reason` `tag_in_use` (plus `existing`, so the wizard can show that asset), `asset_code_in_use`, `location_not_found`. Creates the asset, tag, `created` / `tag_assigned` / `placed_in_location` events atomically. A barcode tag shaped like `A000900` / `C000045` becomes the asset code (pre-printed label sheets).

### `search_assets(p_query text, p_limit int = 50) -> setof assets`
Security invoker (RLS applies). Exact code or tag, full text, trigram on name and serial, substring. Excludes retired assets.

## RLS summary

| | viewer | crew | manager | admin |
|---|---|---|---|---|
| read everything | yes | yes | yes | yes |
| `apply_scan_events`, `create_asset` | no | yes | yes | yes |
| direct writes to assets / locations / tags / checkouts / audits | no | no | yes | yes |
| `asset_photos` insert | no | own | yes | yes |
| projects | no | no | yes | yes |
| categories, templates, user roles | no | no | no | yes |
| `asset_events` direct write | nobody | | | |

`anon` has no table privileges. Assets are never deleted. Helper functions live in the non-exposed `private` schema.

## First admin
New users are `viewer`. Promote the first admin once in the SQL editor:
`update public.profiles set role = 'admin' where id = '<auth user id>';`

## Not done yet (tracked)
- Storage bucket and policies for photos (task 6).
- `legacy_id_map` table (task 8).
- `packages/shared-types` generation (needs a running local stack).
