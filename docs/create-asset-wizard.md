# Create-asset wizard, Asset Detail, Search

## Create-asset wizard (flow as steps)
Code: `asset/create/CreateAssetWizard.kt` (pure logic), `CreateAssetViewModel.kt`, `ui/asset/CreateAssetScreen.kt`.

1. **Tag.** Scan a barcode, tap an NFC tag, or choose "Generate code, print label later".
   - The scanned value is resolved first. If it already belongs to an asset or container, show that asset ("Open it" / "Scan another") instead of creating.
   - A location label is refused ("not gear").
   - A barcode shaped like `A000900` / `C000045` becomes the asset's own code (pre-printed label sheets); see ADR 0001.
2. **Type.** Recent templates as big chips, all templates, then category. Choosing a template fills category, name pattern, manufacturer, model, container flag and custom values.
   "Same as last" is offered here and on step 1.
3. **Details.** Name (may contain `{n}`: `SM58 #{n}` gives SM58 #1, SM58 #2, ...), manufacturer, model, "is a container", serial (typed, or press "Scan serial" and scan it).
4. **Required info.** Only shown when the category has required custom fields.
5. **Location.** Scan the location label where it lives. Defaults to the last location used.
6. **Confirm.** "Create" (opens the new asset) or "Create & next".
7. **Photos.** Not implemented yet (needs the Storage bucket and a camera flow). Follow-up task.

### Same as last and the fast path
"Same as last" restores category, template, name pattern (counter advanced), manufacturer, model, container flag, custom values and location. The wizard then shortens to **Tag -> Serial -> Confirm**:
1. scan the tag; 2. scan the serial; 3. scan the same tag again (or tap Create). Then it loops to step 1 for the next unit.

### Create & next
Keeps everything except the tag and serial, advances the `{n}` counter, and returns to step 1. The last asset's choices are remembered on the device for "Same as last".

### Failure handling
- The client generates the asset id once per draft. Retrying after "No connection" re-sends the same id, so `create_asset` returns `duplicate` instead of creating a second asset.
- `tag_in_use` shows the existing asset. `location_not_found` asks for a new scan.
- Creation currently needs a connection. Queueing it offline arrives with the sync worker (task 7).

## Asset Detail
`get_asset_detail` (one RPC) returns: fields, category, location path **including the container chain** (`Savanna > Bin 1A > Case A > Pouch`), last seen (time, by whom), open checkout, tags (printed code first), photo count, and the last 50 events.
Quick actions: **Add NFC tag** (arm, then tap; queues a `tag_assigned` event) and **Report damage** (confirm; queues `status_changed` to `in_repair`). Move, Check out and Print label are shown disabled until their updates.

## Search
`search_assets`: exact code or tag, full text, trigram on name and serial, substring; retired assets excluded. The query is debounced (300 ms). Scanning any tag on this screen opens that asset directly; a location or unknown tag explains itself.

## Not done yet
- Photos (capture, upload, viewing) and the Storage bucket policies.
- Offline creation (task 7), printing labels (web, task M3).
- Search results do not show location; open the asset to see it.
