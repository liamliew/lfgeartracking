package com.lfcreative.geartracker.asset

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.lfcreative.geartracker.core.ScanInput
import com.lfcreative.geartracker.core.ScanSource
import com.lfcreative.geartracker.core.scan.ScanInputBus
import com.lfcreative.geartracker.data.outbox.EventOutbox
import com.lfcreative.geartracker.session.Placement
import com.lfcreative.geartracker.session.ResolvedTag
import com.lfcreative.geartracker.session.TagResolving
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class QueueOutbox : EventOutbox {
    val events = mutableListOf<Pair<String, JsonObject>>()
    override suspend fun enqueue(type: String, fields: JsonObject, occurredAt: Instant, id: UUID): UUID { events += type to fields; return id }
}

private class StubApi(
    var rows: List<AssetSummary> = emptyList(),
    var detail: Result<AssetDetail?> = Result.success(null),
) : AssetApi {
    val queries = mutableListOf<String>()
    override suspend fun createAsset(request: JsonObject, scanned: String?) = CreateResult.Rejected("unused")
    override suspend fun search(query: String): Result<List<AssetSummary>> { queries += query; return Result.success(rows) }
    override suspend fun detail(id: String) = detail
    override suspend fun categories() = Result.success(emptyList<CategoryInfo>())
    override suspend fun templates() = Result.success(emptyList<TemplateInfo>())
}

@OptIn(ExperimentalCoroutinesApi::class)
class SearchAndDetailViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val bus = ScanInputBus()
    private val api = StubApi()
    private val outbox = QueueOutbox()
    private val known = mutableMapOf<String, ResolvedTag>()
    private var seq = 0L
    private val clock = object : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = Instant.parse("2026-11-01T10:00:00Z")
    }
    private val resolver = object : TagResolving { override suspend fun resolve(value: String) = known[value] ?: ResolvedTag.Unknown(value) }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.scan(value: String, source: ScanSource = ScanSource.DATAWEDGE) {
        seq += 10_000
        bus.submit(ScanInput(value, source, null, Instant.ofEpochMilli(seq)))
        runCurrent()
    }

    private val detail = AssetDetail(
        "a1", "A000001", "Mic", "available", false, null, null, null, null, null, false, null, emptyList(), null, null, null, emptyList(), 0, emptyList(),
    )

    // ---- Search ---------------------------------------------------------------------------------------------

    @Test fun `typing searches once after the pause, not on every key`() = runTest(dispatcher) {
        api.rows = listOf(AssetSummary("a1", "A000001", "Mic", "available", false, null))
        val vm = SearchViewModel(api, resolver, bus); runCurrent()
        vm.onQuery("m"); advanceTimeBy(100); vm.onQuery("mi"); advanceTimeBy(100); vm.onQuery("mic ")
        advanceTimeBy(400); runCurrent()
        assertEquals(listOf("mic"), api.queries)
        assertEquals(1, vm.ui.value.results.size)
        vm.viewModelScope.cancel()
    }

    @Test fun `no match explains itself and a blank query clears`() = runTest(dispatcher) {
        val vm = SearchViewModel(api, resolver, bus); runCurrent()
        vm.onQuery("zzz"); advanceTimeBy(400); runCurrent()
        assertEquals("No match for \"zzz\"", vm.ui.value.message)
        vm.onQuery(""); advanceTimeBy(400); runCurrent()
        assertTrue(vm.ui.value.results.isEmpty()); assertEquals(null, vm.ui.value.message)
        vm.viewModelScope.cancel()
    }

    @Test fun `scanning gear opens it, scanning a location or an unknown tag explains`() = runTest(dispatcher) {
        known["MIC"] = ResolvedTag.Item("MIC", "a1", "A000001", "Mic", Placement())
        known["BIN"] = ResolvedTag.Location("BIN", "l1", "L1", "Bin", path = "Savanna > Bin")
        val vm = SearchViewModel(api, resolver, bus)
        val opened = mutableListOf<String>()
        val job = launch { vm.open.toList(opened) }
        runCurrent()

        scan("MIC"); assertEquals(listOf("a1"), opened)
        scan("BIN"); assertEquals("Savanna > Bin is a location, not gear", vm.ui.value.message)
        scan("???"); assertEquals("Nothing is registered for ???", vm.ui.value.message)
        job.cancel(); vm.viewModelScope.cancel()
    }

    // ---- Asset detail ---------------------------------------------------------------------------------------

    private fun detailVm() = AssetDetailViewModel(SavedStateHandle(mapOf("id" to "a1")), api, outbox, bus, clock)

    @Test fun `loads the detail, or reports not found, or a connection problem`() = runTest(dispatcher) {
        api.detail = Result.success(detail)
        val ok = detailVm(); runCurrent()
        assertEquals("Mic", ok.ui.value.detail!!.name)

        api.detail = Result.success(null)
        val missing = detailVm(); runCurrent()
        assertTrue(missing.ui.value.notFound)

        api.detail = Result.failure(RuntimeException("offline"))
        val broken = detailVm(); runCurrent()
        assertTrue(broken.ui.value.error!!.contains("Cannot load"))
        listOf(ok, missing, broken).forEach { it.viewModelScope.cancel() }
    }

    @Test fun `an armed NFC tap becomes a tag_assigned event, once`() = runTest(dispatcher) {
        api.detail = Result.success(detail)
        val vm = detailVm(); runCurrent()
        scan("0446880ABF2A81", ScanSource.NFC)                       // not armed: ignored
        assertTrue(outbox.events.isEmpty())

        vm.armNfc(true)
        scan("A000099")                                              // a barcode does not count
        assertTrue(outbox.events.isEmpty())
        scan("0446880ABF2A82", ScanSource.NFC)

        val (type, fields) = outbox.events.single()
        assertEquals("tag_assigned", type)
        assertEquals("a1", fields["asset_id"]!!.jsonPrimitive.content)
        assertEquals("0446880ABF2A82", fields["payload"]!!.jsonObject["tag"]!!.jsonObject["value"]!!.jsonPrimitive.content)
        assertEquals("nfc", fields["payload"]!!.jsonObject["tag"]!!.jsonObject["kind"]!!.jsonPrimitive.content)
        assertFalse(vm.ui.value.armedForNfc)
        vm.viewModelScope.cancel()
    }

    @Test fun `reporting damage queues a status change to in_repair`() = runTest(dispatcher) {
        api.detail = Result.success(detail)
        val vm = detailVm(); runCurrent()
        vm.askReportDamage(true); assertTrue(vm.ui.value.confirmDamage)
        vm.reportDamage(); runCurrent()
        val (type, fields) = outbox.events.single()
        assertEquals("status_changed", type)
        assertEquals("in_repair", fields["payload"]!!.jsonObject["status"]!!.jsonPrimitive.content)
        assertFalse(vm.ui.value.confirmDamage)
        vm.viewModelScope.cancel()
    }
}
