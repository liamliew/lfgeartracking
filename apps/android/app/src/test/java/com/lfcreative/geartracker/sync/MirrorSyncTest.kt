package com.lfcreative.geartracker.sync

import com.lfcreative.geartracker.data.local.AssetEntity
import com.lfcreative.geartracker.data.local.LocationEntity
import com.lfcreative.geartracker.data.local.OpenCheckoutEntity
import com.lfcreative.geartracker.data.local.ProjectEntity
import com.lfcreative.geartracker.data.local.TagEntity
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class PagedApi(val rows: Map<MirrorSource, List<JsonObject>>, val failAt: Pair<MirrorSource, Long>? = null) : MirrorApi {
    val requested = mutableListOf<Pair<MirrorSource, Long>>()
    override suspend fun page(source: MirrorSource, from: Long): Result<JsonArray> {
        requested += source to from
        if (failAt == source to from) return Result.failure(RuntimeException("page failed"))
        val all = rows[source].orEmpty()
        return Result.success(JsonArray(all.drop(from.toInt()).take(PAGE_SIZE.toInt())))
    }
}

private class RecordingStore : MirrorStore {
    var tags = emptyList<TagEntity>(); var assets = emptyList<AssetEntity>(); var replaced = 0
    override suspend fun replaceAll(tags: List<TagEntity>, assets: List<AssetEntity>, locations: List<LocationEntity>, projects: List<ProjectEntity>, openCheckouts: List<OpenCheckoutEntity>) {
        this.tags = tags; this.assets = assets; replaced++
    }
}

private fun tag(i: Int) = buildJsonObject { put("value", JsonPrimitive("T$i")); put("kind", JsonPrimitive("barcode")); put("asset_id", JsonPrimitive("a$i")) }

class MirrorSyncTest {
    @Test fun `reads every page until a short one, then swaps everything at once`() = runTest {
        val api = PagedApi(mapOf(MirrorSource.TAGS to (0 until 1500).map(::tag)))
        val store = RecordingStore()
        assertTrue(MirrorSync(api, store).refresh().isSuccess)
        assertEquals(1500, store.tags.size)
        assertEquals(listOf(0L, 1000L), api.requested.filter { it.first == MirrorSource.TAGS }.map { it.second })
        assertEquals(1, store.replaced)
    }

    @Test fun `a page that is exactly full triggers one more request`() = runTest {
        val api = PagedApi(mapOf(MirrorSource.TAGS to (0 until 1000).map(::tag)))
        MirrorSync(api, RecordingStore()).refresh()
        assertEquals(listOf(0L, 1000L), api.requested.filter { it.first == MirrorSource.TAGS }.map { it.second })
    }

    @Test fun `a failure half-way leaves the old copy untouched`() = runTest {
        val api = PagedApi(mapOf(MirrorSource.TAGS to (0 until 10).map(::tag)), failAt = MirrorSource.LOCATIONS to 0L)
        val store = RecordingStore()
        val r = MirrorSync(api, store).refresh()
        assertTrue(r.isFailure)
        assertEquals(0, store.replaced)
    }
}

class MirrorJsonTest {
    private fun rows(s: String) = Json.parseToJsonElement(s).let { (it as JsonArray).map { e -> e.jsonObject } }

    @Test fun `retired and malformed tags are skipped`() {
        val t = MirrorJson.tags(rows("""[
            {"value":"A1","kind":"barcode","asset_id":"a1","location_id":null,"retired_at":null},
            {"value":"OLD","kind":"barcode","asset_id":"a1","retired_at":"2026-01-01T00:00:00+00:00"},
            {"value":"BOTH","kind":"barcode","asset_id":"a1","location_id":"l1"},
            {"value":"NONE","kind":"barcode"},
            {"kind":"nfc","asset_id":"a2"},
            {"value":"L1","kind":"barcode","location_id":"l1"}]"""))
        assertEquals(listOf("A1", "L1"), t.map { it.value })
        assertEquals("l1", t[1].locationId)
    }

    @Test fun `assets map placement, retired flag and offset timestamps`() {
        val a = MirrorJson.assets(rows("""[{"id":"a1","asset_code":"A000001","name":"Mic","status":"checked_out","is_container":true,
            "parent_location_id":null,"parent_asset_id":"c1","retired_at":null,"last_seen_at":"2026-10-02T10:00:00+00:00"},
            {"id":"a2","asset_code":"A000002","name":"Old","retired_at":"2026-01-01T00:00:00Z"}]"""))
        assertEquals("c1", a[0].parentAssetId); assertTrue(a[0].isContainer)
        assertEquals(java.time.Instant.parse("2026-10-02T10:00:00Z").toEpochMilli(), a[0].lastSeenAt)
        assertTrue(a[1].retired); assertEquals("available", a[1].status)
    }

    @Test fun `locations, projects and open checkouts`() {
        val l = MirrorJson.locations(rows("""[{"id":"l1","code":"L00001","name":"Bin","kind":"bin","parent_id":"l0","path_cache":"Site > Bin","archived_at":null}]"""))
        assertEquals("Site > Bin", l.single().path); assertEquals("l0", l.single().parentId)
        val p = MirrorJson.projects(rows("""[{"id":"p1","code":"PRJ-1","name":"Shoot","starts_on":"2026-10-01","ends_on":null}]"""))
        assertEquals("2026-10-01", p.single().startsOn)
        val c = MirrorJson.openCheckouts(rows("""[{"asset_id":"a1","checkout_id":"c1","project_id":null,"reason":"Test","due_at":"2026-10-10T09:00:00+00:00","checked_out_at":"2026-10-01T09:00:00+00:00"},{"checkout_id":"x"}]"""))
        assertEquals(1, c.size); assertEquals("Test", c[0].reason)
        assertEquals(java.time.Instant.parse("2026-10-10T09:00:00Z").toEpochMilli(), c[0].dueAt)
    }
}
