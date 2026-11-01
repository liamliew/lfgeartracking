package com.lfcreative.geartracker.asset

import com.lfcreative.geartracker.session.ResolvedTag
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssetJsonTest {
    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject
    private fun arr(s: String) = Json.parseToJsonElement(s).jsonArray

    @Test fun `categories carry their field schema`() {
        val c = AssetJson.categories(arr("""[{"id":"c1","name":"Lenses","default_is_container":false,
            "field_schema":{"fields":[{"key":"mount","label":"Mount","type":"text","required":true},{"key":"x"}]}},
            {"id":"c2","name":"Cases","default_is_container":true,"field_schema":{}}, {"name":"no id"}]"""))
        assertEquals(2, c.size)
        assertEquals(listOf(FieldDef("mount", "Mount", "text", true), FieldDef("x", "x", "text", false)), c[0].fields)
        assertTrue(c[1].defaultIsContainer)
    }

    @Test fun `search rows become summaries`() {
        val s = AssetJson.summaries(arr("""[{"id":"a1","asset_code":"A000001","name":"Mic","status":"checked_out","is_container":false,"serial_number":null}]"""))
        assertEquals(AssetSummary("a1", "A000001", "Mic", "checked_out", false, null), s.single())
    }

    @Test fun `create results`() {
        val created = AssetJson.createResult("X", obj("""{"status":"created","asset_code":"A000042","asset":{"id":"a1","name":"Mic"}}"""))
        assertEquals(CreateResult.Created("a1", "A000042", "Mic"), created)
        val dup = AssetJson.createResult("X", obj("""{"status":"duplicate","asset":{"id":"a1","asset_code":"A000042","name":"Mic"}}"""))
        assertEquals(CreateResult.Duplicate("a1", "A000042", "Mic"), dup)
    }

    @Test fun `a used tag returns the existing asset to show instead`() {
        val r = AssetJson.createResult("110164", obj("""{"status":"rejected","reason":"tag_in_use",
            "existing":{"kind":"asset","id":"a9","code":"A000009","name":"Old mic","placement":{}}}"""))
        val existing = (r as CreateResult.TagInUse).existing as ResolvedTag.Item
        assertEquals("a9", existing.id)
    }

    @Test fun `other rejections and garbage are reported, not thrown`() {
        assertEquals(CreateResult.Rejected("location_not_found"), AssetJson.createResult(null, obj("""{"status":"rejected","reason":"location_not_found"}""")))
        assertEquals(CreateResult.Rejected("unexpected response"), AssetJson.createResult(null, obj("""{}""")))
        assertEquals(CreateResult.Rejected("bad response"), AssetJson.createResult(null, obj("""{"status":"created"}""")))
    }

    @Test fun `asset detail with breadcrumb and timeline`() {
        val d = AssetJson.detail(obj("""{"found":true,
            "asset":{"id":"a1","code":"A000001","name":"Mic","status":"available","is_container":false,"serial_number":"SN1",
              "category":{"id":"c","name":"Audio"},"retired_at":null},
            "location":{"id":"l","path":"Savanna > Bin 1A"},
            "container_chain":[{"name":"Case A"},{"name":"Pouch"}],
            "last_seen":{"at":"2026-10-02T10:00:00Z","by":"Casey"},
            "open_checkout":{"project_name":"Brand Shoot","due_at":"2026-10-10T09:00:00Z"},
            "tags":[{"value":"A000001","retired":false},{"value":"OLD","retired":true},{"value":"0446880ABF2A81","retired":false}],
            "photos":[{"id":"p"}],
            "events":[{"id":"e1","type":"seen","occurred_at":"2026-10-02T10:00:00Z","user_name":"Casey","to_location":null,"to_asset":{"name":"Pouch"}}]}"""))!!
        assertEquals("Savanna > Bin 1A > Case A > Pouch", d.breadcrumb)
        assertEquals("Audio", d.category)
        assertEquals(listOf("A000001", "0446880ABF2A81"), d.tags)
        assertEquals("Brand Shoot (due 2026-10-10)", d.openCheckout)
        assertEquals("Casey", d.lastSeenBy)
        assertEquals(1, d.photoCount)
        assertEquals("Pouch", d.timeline.single().toAsset)
    }

    @Test fun `unknown assets and unplaced assets`() {
        assertNull(AssetJson.detail(obj("""{"found":false}""")))
        val d = AssetJson.detail(obj("""{"found":true,"asset":{"id":"a","code":"A1","name":"X"},"container_chain":[],"tags":[],"photos":[],"events":[]}"""))!!
        assertNull(d.breadcrumb)
    }
}
