package com.lfcreative.geartracker.session

import com.lfcreative.geartracker.feedback.CueColor
import com.lfcreative.geartracker.feedback.FeedbackCues
import com.lfcreative.geartracker.feedback.Pulse
import com.lfcreative.geartracker.feedback.Sound
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackCuesTest {
    @Test fun `every kind has a cue with colour, icon and text`() {
        FeedbackKind.entries.forEach { kind ->
            val cue = FeedbackCues.forKind(kind)
            assertTrue("$kind needs a label", cue.label.isNotBlank())
        }
    }

    @Test fun `cues follow the spec table`() {
        fun cue(k: FeedbackKind) = FeedbackCues.forKind(k)
        assertEquals(CueColor.GREEN, cue(FeedbackKind.ACCEPTED).color)
        assertEquals(listOf(Pulse.SHORT), cue(FeedbackKind.ACCEPTED).pulses)
        assertEquals(Sound.HIGH_BEEP, cue(FeedbackKind.ACCEPTED).sound)

        assertEquals(CueColor.BLUE, cue(FeedbackKind.TARGET_SET).color)
        assertEquals(listOf(Pulse.SHORT, Pulse.SHORT), cue(FeedbackKind.TARGET_SET).pulses)
        assertEquals(Sound.RISING_TWO, cue(FeedbackKind.TARGET_SET).sound)

        assertEquals(CueColor.GREY, cue(FeedbackKind.TARGET_CLOSED).color)
        assertEquals(listOf(Pulse.LONG), cue(FeedbackKind.TARGET_CLOSED).pulses)
        assertEquals(Sound.FALLING_TWO, cue(FeedbackKind.TARGET_CLOSED).sound)

        assertEquals(CueColor.AMBER, cue(FeedbackKind.DUPLICATE).color)
        assertEquals(Sound.LOW_BLIP, cue(FeedbackKind.DUPLICATE).sound)

        assertEquals(CueColor.AMBER, cue(FeedbackKind.NEEDS_DECISION).color)
        assertEquals(listOf(Pulse.LONG, Pulse.LONG), cue(FeedbackKind.NEEDS_DECISION).pulses)
        assertEquals(Sound.DOUBLE_LOW, cue(FeedbackKind.NEEDS_DECISION).sound)

        assertEquals(CueColor.RED, cue(FeedbackKind.ERROR).color)
        assertEquals(listOf(Pulse.SHORT, Pulse.SHORT, Pulse.SHORT), cue(FeedbackKind.ERROR).pulses)
        assertEquals(Sound.BUZZ, cue(FeedbackKind.ERROR).sound)
    }

    @Test fun `switching a target is distinguishable from setting one`() {
        assertNotEquals(FeedbackCues.forKind(FeedbackKind.TARGET_SET), FeedbackCues.forKind(FeedbackKind.TARGET_SWITCHED))
        assertEquals(CueColor.BLUE, FeedbackCues.forKind(FeedbackKind.TARGET_SWITCHED).color)
    }
}

class ResolveTagJsonTest {
    private fun parse(json: String, scanned: String = "X") =
        ResolveTagJson.parse(scanned, Json.parseToJsonElement(json).jsonObject)

    @Test fun `asset`() {
        val tag = parse(
            """{"kind":"asset","id":"a1","code":"A000001","name":"SM58 #1","status":"available","retired":false,
                "ancestor_ids":["c2","c1"],"placement":{"location_id":null,"container_id":"c2"}}""",
        ) as ResolvedTag.Item
        assertEquals("a1", tag.id)
        assertEquals(Placement(containerId = "c2"), tag.placement)
    }

    @Test fun `container with ancestors and placement`() {
        val tag = parse(
            """{"kind":"container","id":"c2","code":"C000002","name":"Pouch","retired":false,
                "ancestor_ids":["c1"],"placement":{"location_id":null,"container_id":"c1"}}""",
        ) as ResolvedTag.Container
        assertEquals(setOf("c1"), tag.ancestorIds)
        assertEquals(Placement(containerId = "c1"), tag.placement)
    }

    @Test fun `location`() {
        val tag = parse(
            """{"kind":"location","id":"l3","code":"L00003","name":"Bin 1A","path":"Savanna > Storage 1 > Bin 1A",
                "archived":false,"ancestor_ids":["l2","l1"]}""",
        ) as ResolvedTag.Location
        assertEquals(setOf("l2", "l1"), tag.ancestorIds)
        assertEquals("Savanna > Storage 1 > Bin 1A", tag.path)
    }

    @Test fun `unknown and malformed results become Unknown`() {
        assertEquals(ResolvedTag.Unknown("X"), parse("""{"kind":"unknown","value":"X"}"""))
        assertEquals(ResolvedTag.Unknown("X"), parse("""{"kind":"asset"}"""))
        assertEquals(ResolvedTag.Unknown("X"), parse("""{}"""))
    }
}

class ResolveFirstTest {
    private class Source(val answer: ResolvedTag?, var calls: Int = 0) : TagSource {
        override suspend fun resolve(value: String): ResolvedTag? { calls++; return answer }
    }

    @Test fun `local answer wins and the server is not called`() = runTest {
        val local = Source(ResolvedTag.Item("X", "a1", "A1", "Mic"))
        val remote = Source(ResolvedTag.Item("X", "other", "A2", "Other"))
        assertEquals("a1", (resolveFirst("X", listOf(local, remote)) as ResolvedTag.Item).id)
        assertEquals(0, remote.calls)
    }

    @Test fun `falls back to the server, then to Unknown`() = runTest {
        val remote = Source(ResolvedTag.Item("X", "a2", "A2", "Remote"))
        assertEquals("a2", (resolveFirst("X", listOf(Source(null), remote)) as ResolvedTag.Item).id)
        assertEquals(ResolvedTag.Unknown("X"), resolveFirst("X", listOf(Source(null), Source(null))))
    }
}
