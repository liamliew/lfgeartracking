package com.lfcreative.geartracker.session

import androidx.lifecycle.viewModelScope
import com.lfcreative.geartracker.core.ScanInput
import com.lfcreative.geartracker.core.ScanSource
import com.lfcreative.geartracker.core.scan.ScanInputBus
import com.lfcreative.geartracker.data.outbox.EventOutbox
import com.lfcreative.geartracker.data.settings.SessionSettings
import com.lfcreative.geartracker.feedback.FeedbackSink
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class FakeClock(var ms: Long) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
    override fun instant(): Instant = Instant.ofEpochMilli(ms)
    override fun millis(): Long = ms
}

private class RecordingOutbox : EventOutbox {
    data class Entry(val type: String, val fields: JsonObject, val at: Instant)
    val entries = mutableListOf<Entry>()
    override suspend fun enqueue(type: String, fields: JsonObject, occurredAt: Instant, id: UUID): UUID {
        entries += Entry(type, fields, occurredAt)
        return id
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PackMoveViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val clock = FakeClock(1_000_000)
    private val outbox = RecordingOutbox()
    private val cues = mutableListOf<FeedbackKind>()
    private val bus = ScanInputBus()
    private val tags = mutableMapOf<String, ResolvedTag>()
    private var seq = 0L

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm(timeoutSec: Int = 90) = PackMoveViewModel(
        bus,
        object : TagResolving { override suspend fun resolve(value: String) = tags[value] ?: ResolvedTag.Unknown(value) },
        outbox,
        FeedbackSink { cues += it },
        object : SessionSettings { override val idleTimeoutSec = timeoutSec },
        clock,
    )

    /** Each scan is far apart in "time" so the 1.5 s debounce never hides a deliberate re-scan. */
    private fun TestScope.scan(value: String) {
        seq += 10_000
        bus.submit(ScanInput(value, ScanSource.DATAWEDGE, null, Instant.ofEpochMilli(seq)))
        runCurrent()
    }

    private val mic = ResolvedTag.Item("MIC", "i1", "A000001", "Mic", Placement(locationId = "l-old"))
    private val cable = ResolvedTag.Item("CABLE", "i2", "A000002", "Cable", Placement(locationId = "l-old"))
    private val caseA = ResolvedTag.Container("CASEA", "cA", "C000001", "Case A")
    private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.content

    @Test fun `basket commit writes one outbox event per item, with session fields`() = runTest(dispatcher) {
        tags += mapOf("MIC" to mic, "CABLE" to cable, "CASEA" to caseA)
        val vm = vm()
        runCurrent()
        scan("MIC"); scan("CABLE"); scan("CASEA")

        assertEquals(listOf("placed_in_container", "placed_in_container"), outbox.entries.map { it.type })
        assertEquals(listOf("i1", "i2"), outbox.entries.map { it.fields.str("asset_id") })
        assertEquals("cA", outbox.entries[0].fields.str("to_asset_id"))
        assertEquals("pack", outbox.entries[0].fields.str("session_mode"))
        assertEquals(1, outbox.entries.map { it.fields.str("session_id") }.toSet().size)
        assertEquals(listOf(FeedbackKind.ACCEPTED, FeedbackKind.ACCEPTED, FeedbackKind.ACCEPTED), cues)
        vm.viewModelScope.cancel()
    }

    @Test fun `occurredAt is strictly increasing even when the clock does not move`() = runTest(dispatcher) {
        tags += mapOf("MIC" to mic, "CABLE" to cable, "CASEA" to caseA)
        val vm = vm()
        runCurrent()
        scan("CASEA"); scan("MIC"); scan("CABLE")
        val times = outbox.entries.map { it.at }
        assertEquals(2, times.size)
        assertTrue(times[0].isBefore(times[1]))
        vm.viewModelScope.cancel()
    }

    @Test fun `undo is written after the move it reverses`() = runTest(dispatcher) {
        tags += mapOf("MIC" to mic, "CASEA" to caseA)
        val vm = vm()
        runCurrent()
        scan("CASEA"); scan("MIC")
        vm.onUndo(); runCurrent()

        assertEquals(listOf("placed_in_container", "placed_in_location"), outbox.entries.map { it.type })
        assertEquals("l-old", outbox.entries[1].fields.str("to_location_id"))
        assertTrue(outbox.entries[0].at.isBefore(outbox.entries[1].at))
        vm.viewModelScope.cancel()
    }

    @Test fun `unknown tag asks for a decision and writes nothing`() = runTest(dispatcher) {
        val vm = vm()
        runCurrent()
        scan("MYSTERY")
        assertEquals("MYSTERY", vm.ui.value.decisionFor)
        assertEquals(listOf(FeedbackKind.NEEDS_DECISION), cues)
        assertTrue(outbox.entries.isEmpty())
        vm.dismissDecision()
        assertNull(vm.ui.value.decisionFor)
        vm.viewModelScope.cancel()
    }

    @Test fun `the open target closes itself after the idle timeout`() = runTest(dispatcher) {
        tags += "CASEA" to caseA
        val vm = vm(timeoutSec = 90)
        runCurrent()
        scan("CASEA")
        assertTrue(vm.ui.value.machine.phase is PackMoveMachine.Phase.InTarget)

        clock.ms += 91_000
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(vm.ui.value.machine.phase is PackMoveMachine.Phase.Idle)
        assertTrue(cues.contains(FeedbackKind.TARGET_CLOSED))
        vm.viewModelScope.cancel()
    }

    @Test fun `the idle timeout comes from settings`() = runTest(dispatcher) {
        tags += "CASEA" to caseA
        val vm = vm(timeoutSec = 30)
        runCurrent()
        scan("CASEA")
        clock.ms += 31_000
        advanceTimeBy(1_000); runCurrent()
        assertTrue(vm.ui.value.machine.phase is PackMoveMachine.Phase.Idle)
        vm.viewModelScope.cancel()
    }
}
