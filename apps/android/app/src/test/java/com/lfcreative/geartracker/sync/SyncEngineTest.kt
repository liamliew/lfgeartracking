package com.lfcreative.geartracker.sync

import com.lfcreative.geartracker.data.local.AssetEntity
import com.lfcreative.geartracker.data.local.LocationEntity
import com.lfcreative.geartracker.data.local.OpenCheckoutEntity
import com.lfcreative.geartracker.data.local.OutboxDao
import com.lfcreative.geartracker.data.local.OutboxEventEntity
import com.lfcreative.geartracker.data.local.OutboxState
import com.lfcreative.geartracker.data.local.ProjectEntity
import com.lfcreative.geartracker.data.local.TagEntity
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory stand-in for the Room outbox. */
private class FakeOutbox : OutboxDao {
    val rows = MutableStateFlow<List<OutboxEventEntity>>(emptyList())
    var purged = false
    override suspend fun insert(event: OutboxEventEntity): Long { rows.value = rows.value + event; return 1 }
    override suspend fun pending(limit: Int) = rows.value.filter { it.state == OutboxState.PENDING }.sortedWith(compareBy({ it.occurredAt }, { it.createdAt })).take(limit)
    override fun pendingCount(): Flow<Int> = rows.map { r -> r.count { it.state == OutboxState.PENDING } }
    override fun rejectedCount(): Flow<Int> = rows.map { r -> r.count { it.state == OutboxState.REJECTED } }
    override suspend fun markSent(ids: List<String>) = update(ids) { it.copy(state = OutboxState.SENT) }
    override suspend fun markRejected(id: String, error: String) = update(listOf(id)) { it.copy(state = OutboxState.REJECTED, lastError = error) }
    override suspend fun recordFailure(ids: List<String>, error: String) = update(ids) { it.copy(attempts = it.attempts + 1, lastError = error) }
    override fun rejected(): Flow<List<OutboxEventEntity>> = rows.map { r -> r.filter { it.state == OutboxState.REJECTED } }
    override suspend fun clearRejected() { rows.value = rows.value.filter { it.state != OutboxState.REJECTED } }
    override suspend fun purgeSent(beforeMillis: Long) { purged = true }
    private fun update(ids: List<String>, f: (OutboxEventEntity) -> OutboxEventEntity) {
        rows.value = rows.value.map { if (it.id in ids) f(it) else it }
    }
    fun state(id: String) = rows.value.first { it.id == id }
}

private class FakeEvents(var respond: (List<JsonObject>) -> Result<List<EventResult>>) : EventsApi {
    val calls = mutableListOf<List<String>>()
    override suspend fun apply(events: List<JsonObject>): Result<List<EventResult>> {
        calls += events.map { it["id"]!!.jsonPrimitive.content }
        return respond(events)
    }
}

private class FakeStatus : SyncStatusStore {
    private val s = MutableStateFlow(SyncStatus())
    override val status: StateFlow<SyncStatus> = s
    override fun update(transform: (SyncStatus) -> SyncStatus) { s.value = transform(s.value) }
}

private class FakeMirrorApi(var fail: Boolean = false) : MirrorApi {
    var calls = 0
    override suspend fun page(source: MirrorSource, from: Long): Result<JsonArray> {
        calls++
        return if (fail) Result.failure(RuntimeException("mirror down")) else Result.success(JsonArray(emptyList()))
    }
}

private class FakeStore : MirrorStore {
    var replaced = 0
    override suspend fun replaceAll(tags: List<TagEntity>, assets: List<AssetEntity>, locations: List<LocationEntity>, projects: List<ProjectEntity>, openCheckouts: List<OpenCheckoutEntity>) { replaced++ }
}

private fun ok(id: String, status: String = "accepted") = EventResult(id, status, null, null)

class SyncEngineTest {
    private val outbox = FakeOutbox()
    private val status = FakeStatus()
    private val mirrorApi = FakeMirrorApi()
    private val store = FakeStore()
    private var now = 10_000_000_000L
    private val clock = object : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = Instant.ofEpochMilli(now)
        override fun millis(): Long = now
    }

    private suspend fun queue(n: Int, startAt: Int = 0) = (startAt until startAt + n).forEach { i ->
        outbox.insert(OutboxEventEntity("e$i", "placed_in_location", """{"id":"e$i","type":"placed_in_location"}""", occurredAt = i.toLong(), createdAt = i.toLong()))
    }

    private fun engine(events: FakeEvents, batch: Int = 100, mirrorEvery: Long = 300_000) =
        SyncEngine(outbox, events, MirrorSync(mirrorApi, store), status, clock, batch, mirrorEvery)

    @Test fun `accepted and duplicate events are marked sent`() = runTest {
        queue(2)
        val e = engine(FakeEvents { Result.success(listOf(ok("e0"), ok("e1", "duplicate"))) })
        val out = e.run() as SyncOutcome.Done
        assertEquals(2, out.pushed)
        assertEquals(OutboxState.SENT, outbox.state("e0").state); assertEquals(OutboxState.SENT, outbox.state("e1").state)
        assertEquals(now, status.status.value.lastPushAt)
    }

    @Test fun `a rejected event goes to the issues list with the reason and is never retried`() = runTest {
        queue(2)
        val api = FakeEvents { Result.success(listOf(ok("e0"), EventResult("e1", "rejected", "cycle detected", "23514"))) }
        val out = engine(api).run() as SyncOutcome.Done
        assertEquals(1, out.rejected)
        assertEquals(OutboxState.REJECTED, outbox.state("e1").state)
        assertEquals("cycle detected", outbox.state("e1").lastError)

        engine(api).run()                                    // second pass: nothing left to send
        assertEquals(1, api.calls.size)
    }

    @Test fun `a failed call keeps every event pending, records the error and skips the mirror`() = runTest {
        queue(3)
        val out = engine(FakeEvents { Result.failure(RuntimeException("timeout")) }).run()
        assertEquals(SyncOutcome.Retry("timeout"), out)
        assertTrue(outbox.rows.value.all { it.state == OutboxState.PENDING && it.attempts == 1 && it.lastError == "timeout" })
        assertEquals("timeout", status.status.value.lastError)
        assertEquals(0, mirrorApi.calls)
    }

    @Test fun `events go out oldest first in batches`() = runTest {
        queue(250)
        val api = FakeEvents { evs -> Result.success(evs.map { ok(it["id"]!!.jsonPrimitive.content) }) }
        val out = engine(api, batch = 100).run() as SyncOutcome.Done
        assertEquals(250, out.pushed)
        assertEquals(listOf(100, 100, 50), api.calls.map { it.size })
        assertEquals((0 until 250).map { "e$it" }, api.calls.flatten())
    }

    @Test fun `an event the server did not answer stays pending while the rest are sent`() = runTest {
        queue(3)
        val api = FakeEvents { Result.success(listOf(ok("e0"), ok("e2"))) }
        val out = engine(api).run()
        assertTrue(out is SyncOutcome.Retry)
        assertEquals(OutboxState.SENT, outbox.state("e0").state); assertEquals(OutboxState.SENT, outbox.state("e2").state)
        assertEquals(OutboxState.PENDING, outbox.state("e1").state); assertEquals(1, outbox.state("e1").attempts)
    }

    @Test fun `the offline copy is refreshed after a clean push, then not again until it is due`() = runTest {
        queue(1)
        val api = FakeEvents { Result.success(listOf(ok("e0"))) }
        val first = engine(api).run() as SyncOutcome.Done
        assertTrue(first.mirrorRefreshed); assertEquals(1, store.replaced)

        now += 60_000
        val second = engine(api).run() as SyncOutcome.Done
        assertTrue(!second.mirrorRefreshed); assertEquals(1, store.replaced)

        now += 300_000
        assertTrue((engine(api).run() as SyncOutcome.Done).mirrorRefreshed)
        assertEquals(2, store.replaced)
    }

    @Test fun `force refresh ignores the schedule`() = runTest {
        val api = FakeEvents { Result.success(emptyList()) }
        engine(api).run()
        assertTrue((engine(api).run(forceMirror = true) as SyncOutcome.Done).mirrorRefreshed)
        assertEquals(2, store.replaced)
    }

    @Test fun `nothing is sent when nothing is waiting`() = runTest {
        val api = FakeEvents { Result.success(emptyList()) }
        engine(api).run()
        assertTrue(api.calls.isEmpty())
        assertTrue(outbox.purged)
    }

    @Test fun `a mirror failure retries but does not undo what was sent`() = runTest {
        queue(1)
        mirrorApi.fail = true
        val out = engine(FakeEvents { Result.success(listOf(ok("e0"))) }).run()
        assertEquals(SyncOutcome.Retry("mirror down"), out)
        assertEquals(OutboxState.SENT, outbox.state("e0").state)
        assertEquals(0, store.replaced)
        assertEquals("mirror down", status.status.value.lastError)
    }

    @Test fun `the error is cleared after a good pass`() = runTest {
        queue(1)
        engine(FakeEvents { Result.failure(RuntimeException("boom")) }).run()
        assertEquals("boom", status.status.value.lastError)
        engine(FakeEvents { Result.success(listOf(ok("e0"))) }).run()
        assertNull(status.status.value.lastError)
    }

    @Test fun `event results parse from the server json`() {
        val parsed = EventResults.parse(JsonArray(listOf(
            buildJsonObject { put("id", JsonPrimitive("a")); put("status", JsonPrimitive("accepted")) },
            buildJsonObject { put("id", JsonPrimitive("b")); put("status", JsonPrimitive("rejected")); put("reason", JsonPrimitive("nope")); put("code", JsonPrimitive("42501")) },
            buildJsonObject { put("status", JsonPrimitive("accepted")) },
        )))
        assertEquals(2, parsed.size)
        assertTrue(parsed[0].accepted); assertTrue(parsed[1].rejected); assertEquals("42501", parsed[1].code)
    }
}
