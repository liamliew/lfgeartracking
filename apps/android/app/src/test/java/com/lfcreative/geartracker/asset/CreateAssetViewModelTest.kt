package com.lfcreative.geartracker.asset

import androidx.lifecycle.viewModelScope
import com.lfcreative.geartracker.asset.create.CreateAssetViewModel
import com.lfcreative.geartracker.asset.create.Remembered
import com.lfcreative.geartracker.asset.create.Step
import com.lfcreative.geartracker.asset.create.TagMode
import com.lfcreative.geartracker.asset.create.WizardMemory
import com.lfcreative.geartracker.core.ScanInput
import com.lfcreative.geartracker.core.ScanSource
import com.lfcreative.geartracker.core.scan.ScanInputBus
import com.lfcreative.geartracker.session.Placement
import com.lfcreative.geartracker.session.ResolvedTag
import com.lfcreative.geartracker.session.TagResolving
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class FakeApi : AssetApi {
    val requests = mutableListOf<JsonObject>()
    val results = ArrayDeque<CreateResult>()
    var categories = listOf(CategoryInfo("cat1", "Audio"), CategoryInfo("cat2", "Cases", defaultIsContainer = true))
    var templates = listOf(
        TemplateInfo("t1", "Mic", "cat1", buildJsonObject {
            put("name_pattern", JsonPrimitive("SM58 #{n}")); put("manufacturer", JsonPrimitive("Shure")); put("model", JsonPrimitive("SM58"))
        }),
    )
    override suspend fun createAsset(request: JsonObject, scanned: String?): CreateResult {
        requests += request
        return results.removeFirstOrNull() ?: CreateResult.Created("id-${requests.size}", "A00000${requests.size}", request["name"]!!.jsonPrimitive.content)
    }
    override suspend fun search(query: String) = Result.success(emptyList<AssetSummary>())
    override suspend fun detail(id: String) = Result.success<AssetDetail?>(null)
    override suspend fun categories() = Result.success(categories)
    override suspend fun templates() = Result.success(templates)
}

private class FakeMemory(var value: Remembered? = null) : WizardMemory {
    override fun load() = value
    override fun save(value: Remembered) { this.value = value }
}

@OptIn(ExperimentalCoroutinesApi::class)
class CreateAssetViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val api = FakeApi()
    private val memory = FakeMemory()
    private val bus = ScanInputBus()
    private val known = mutableMapOf<String, ResolvedTag>()
    private var seq = 0L
    private val clock = object : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = Instant.parse("2026-11-01T10:00:00Z")
    }
    private val bin = ResolvedTag.Location("BIN", "l1", "L00001", "Bin 1A", path = "Savanna > Bin 1A")

    @Before fun setUp() { Dispatchers.setMain(dispatcher); known["BIN"] = bin }
    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm() = CreateAssetViewModel(
        bus, object : TagResolving { override suspend fun resolve(value: String) = known[value] ?: ResolvedTag.Unknown(value) },
        api, memory, clock,
    )

    private fun TestScope.scan(value: String, source: ScanSource = ScanSource.DATAWEDGE) {
        seq += 10_000
        bus.submit(ScanInput(value, source, null, Instant.ofEpochMilli(seq)))
        runCurrent()
    }

    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.content

    /** Walks the full flow to the CONFIRM step. */
    private fun TestScope.fillAll(vm: CreateAssetViewModel, tag: String = "110164") {
        scan(tag)                                  // TAG -> TEMPLATE
        vm.chooseTemplate("t1"); vm.goNext()       // -> IDENTITY
        vm.goNext()                                // name pattern from template -> LOCATION
        scan("BIN")                                // location
        vm.goNext()                                // -> CONFIRM
        runCurrent()
    }

    @Test fun `scanning an unknown barcode sets the tag and moves on`() = runTest(dispatcher) {
        val vm = vm(); runCurrent()
        scan("110164")
        assertEquals("110164", vm.ui.value.draft.tag!!.value)
        assertEquals(TagMode.BARCODE, vm.ui.value.draft.tag!!.mode)
        assertEquals(Step.TEMPLATE, vm.ui.value.step)
        vm.viewModelScope.cancel()
    }

    @Test fun `an NFC tap is stored as an nfc tag`() = runTest(dispatcher) {
        val vm = vm(); runCurrent()
        scan("0446880ABF2A81", ScanSource.NFC)
        assertEquals(TagMode.NFC, vm.ui.value.draft.tag!!.mode)
        vm.viewModelScope.cancel()
    }

    @Test fun `a tag that already belongs to gear shows that gear instead`() = runTest(dispatcher) {
        known["999"] = ResolvedTag.Item("999", "a9", "A000009", "Old mic", Placement())
        val vm = vm(); runCurrent()
        scan("999")
        assertNull(vm.ui.value.draft.tag)
        assertEquals(Step.TAG, vm.ui.value.step)
        assertEquals("a9", (vm.ui.value.existing as ResolvedTag.Item).id)
        vm.viewModelScope.cancel()
    }

    @Test fun `generate code skips scanning`() = runTest(dispatcher) {
        val vm = vm(); runCurrent()
        vm.generateCode()
        assertEquals(TagMode.GENERATE, vm.ui.value.draft.tag!!.mode)
        assertEquals(Step.TEMPLATE, vm.ui.value.step)
        vm.viewModelScope.cancel()
    }

    @Test fun `a template fills category, name pattern and maker`() = runTest(dispatcher) {
        val vm = vm(); runCurrent()
        vm.chooseTemplate("t1")
        val d = vm.ui.value.draft
        assertEquals("cat1", d.categoryId); assertEquals("SM58 #{n}", d.namePattern); assertEquals("Shure", d.manufacturer)
        vm.viewModelScope.cancel()
    }

    @Test fun `the next step is refused with a reason until the current one is complete`() = runTest(dispatcher) {
        val vm = vm(); runCurrent()
        vm.goNext()
        assertEquals(Step.TAG, vm.ui.value.step)
        assertTrue(vm.ui.value.error!!.contains("Scan a barcode"))
        vm.viewModelScope.cancel()
    }

    @Test fun `create sends the full request, remembers it, and finishes`() = runTest(dispatcher) {
        val vm = vm(); runCurrent()
        fillAll(vm)
        assertEquals(Step.CONFIRM, vm.ui.value.step)
        vm.create(andNext = false); runCurrent()

        val req = api.requests.single()
        assertEquals("SM58 #1", req.str("name")); assertEquals("l1", req.str("location_id"))
        assertEquals("110164", req["tag"]!!.jsonObject.str("value")); assertEquals("barcode", req["tag"]!!.jsonObject.str("kind"))
        assertEquals("id-1", vm.ui.value.finishedAssetId)
        assertEquals("SM58 #{n}", memory.value!!.namePattern)
        assertEquals(2, memory.value!!.nextNumber)
        assertEquals(listOf("t1"), memory.value!!.recentTemplateIds)
        vm.viewModelScope.cancel()
    }

    @Test fun `create and next loops to the tag step with the counter advanced`() = runTest(dispatcher) {
        val vm = vm(); runCurrent()
        fillAll(vm)
        vm.create(andNext = true); runCurrent()
        val s = vm.ui.value
        assertEquals(Step.TAG, s.step); assertNull(s.draft.tag); assertEquals("SM58 #2", s.draft.name)
        assertEquals("l1", s.draft.locationId); assertEquals(1, s.createdCount); assertNull(s.finishedAssetId)
        vm.viewModelScope.cancel()
    }

    @Test fun `a failed attempt retries with the same id so it cannot create twice`() = runTest(dispatcher) {
        val vm = vm(); runCurrent()
        fillAll(vm)
        api.results += CreateResult.Offline("No connection")
        vm.create(andNext = false); runCurrent()
        assertEquals("No connection", vm.ui.value.error)
        assertTrue(!vm.ui.value.saving)
        vm.create(andNext = false); runCurrent()
        assertEquals(2, api.requests.size)
        assertEquals(api.requests[0].str("id"), api.requests[1].str("id"))
        vm.viewModelScope.cancel()
    }

    @Test fun `a new asset after a success gets a new id`() = runTest(dispatcher) {
        val vm = vm(); runCurrent()
        fillAll(vm); vm.create(andNext = true); runCurrent()
        fillAll(vm, "110165"); vm.create(andNext = true); runCurrent()
        assertNotNull(api.requests[0].str("id"))
        assertTrue(api.requests[0].str("id") != api.requests[1].str("id"))
        vm.viewModelScope.cancel()
    }

    @Test fun `server says the tag is in use - show the existing asset`() = runTest(dispatcher) {
        val vm = vm(); runCurrent()
        fillAll(vm)
        api.results += CreateResult.TagInUse(ResolvedTag.Item("110164", "a9", "A000009", "Old mic", Placement()))
        vm.create(andNext = false); runCurrent()
        assertEquals("a9", (vm.ui.value.existing as ResolvedTag.Item).id)
        assertNull(vm.ui.value.finishedAssetId)
        vm.viewModelScope.cancel()
    }

    @Test fun `fast path - tag, serial, then the same tag again creates`() = runTest(dispatcher) {
        memory.value = Remembered(
            categoryId = "cat1", templateId = "t1", namePattern = "SM58 #{n}", nextNumber = 5, manufacturer = "Shure",
            locationId = "l1", locationLabel = "Savanna > Bin 1A",
        )
        val vm = vm(); runCurrent()
        assertTrue(vm.ui.value.canSameAsLast)
        vm.useSameAsLast()
        scan("110200")                                 // pull 1: tag
        assertEquals(Step.IDENTITY, vm.ui.value.step)
        scan("SN-777")                                 // pull 2: serial
        assertEquals(Step.CONFIRM, vm.ui.value.step)
        scan("110200")                                 // pull 3: same tag confirms

        val req = api.requests.single()
        assertEquals("SM58 #5", req.str("name")); assertEquals("SN-777", req.str("serial_number"))
        assertEquals("Shure", req.str("manufacturer")); assertEquals("l1", req.str("location_id"))
        assertEquals(Step.TAG, vm.ui.value.step)       // looped for the next one
        assertEquals("SM58 #6", vm.ui.value.draft.name)
        vm.viewModelScope.cancel()
    }

    @Test fun `scanning a different tag on the confirm step does not create`() = runTest(dispatcher) {
        memory.value = Remembered(namePattern = "Cable", locationId = "l1")
        val vm = vm(); runCurrent()
        vm.useSameAsLast()
        scan("110200"); scan("SN-1")
        scan("110999")
        assertTrue(api.requests.isEmpty())
        vm.viewModelScope.cancel()
    }

    @Test fun `the location step only accepts location labels`() = runTest(dispatcher) {
        known["MIC"] = ResolvedTag.Item("MIC", "a1", "A1", "Mic", Placement())
        val vm = vm(); runCurrent()
        vm.generateCode(); vm.edit { it.copy(namePattern = "X") }; vm.goNext(); vm.goNext()
        assertEquals(Step.LOCATION, vm.ui.value.step)
        scan("MIC")
        assertNull(vm.ui.value.draft.locationId)
        assertEquals("Scan a location label", vm.ui.value.error)
        scan("BIN")
        assertEquals("l1", vm.ui.value.draft.locationId)
        vm.viewModelScope.cancel()
    }
}
