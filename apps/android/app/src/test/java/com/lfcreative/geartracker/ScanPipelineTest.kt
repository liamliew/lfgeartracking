package com.lfcreative.geartracker

import com.lfcreative.geartracker.core.ScanInput
import com.lfcreative.geartracker.core.ScanSource
import com.lfcreative.geartracker.core.scan.DataWedgeIntentParser
import com.lfcreative.geartracker.core.scan.ScanDebouncer
import com.lfcreative.geartracker.core.scan.ScanInputBus
import com.lfcreative.geartracker.core.scan.ScanNormalizer
import com.lfcreative.geartracker.core.scan.ScanOutcome
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanNormalizerTest {
    @Test fun `barcode strips control characters and spaces`() {
        assertEquals("A000123", ScanNormalizer.barcode(" A000123\r\n"))
        assertEquals("110164", ScanNormalizer.barcode("110164\u0000"))
        assertNull(ScanNormalizer.barcode(" \r\n"))
    }

    @Test fun `nfc uid bytes become uppercase hex`() {
        val uid = byteArrayOf(0x04, 0x46, 0x88.toByte(), 0x0A, 0xBF.toByte(), 0x2A, 0x81.toByte())
        assertEquals("0446880ABF2A81", ScanNormalizer.nfcUid(uid))
        assertNull(ScanNormalizer.nfcUid(byteArrayOf()))
    }

    @Test fun `legacy dash separated ids normalise like the server does`() {
        assertEquals("0446880ABF2A81", ScanNormalizer.nfcFromText("04-46-88-0a-bf-2a-81"))
        assertEquals("04AABBCC", ScanNormalizer.nfcFromText("04:aa:bb:cc"))
        assertNull(ScanNormalizer.nfcFromText("ZZ"))
        assertNull(ScanNormalizer.nfcFromText("0446"))
    }
}

class ScanDebouncerTest {
    @Test fun `same value inside the window is ignored, after it is accepted`() {
        val d = ScanDebouncer(1_500)
        assertTrue(d.accept("A1", 0))
        assertFalse(d.accept("A1", 1_000))
        assertFalse(d.accept("A1", 1_499))
        assertTrue(d.accept("A1", 1_500))
    }

    @Test fun `different values are independent`() {
        val d = ScanDebouncer(1_500)
        assertTrue(d.accept("A1", 0))
        assertTrue(d.accept("A2", 100))
        assertFalse(d.accept("A1", 200))
    }

    @Test fun `window is measured from the last accepted scan, not from ignored repeats`() {
        val d = ScanDebouncer(1_500)
        assertTrue(d.accept("A1", 0))
        assertFalse(d.accept("A1", 1_400))
        assertTrue(d.accept("A1", 1_600))
    }
}

class DataWedgeIntentParserTest {
    @Test fun `parses the spec extras`() {
        val extras = mapOf(
            DataWedgeIntentParser.EXTRA_DATA to "A000123",
            DataWedgeIntentParser.EXTRA_LABEL_TYPE to "LABEL-TYPE-CODE128",
        )
        val input = DataWedgeIntentParser.parse({ extras[it] }, Instant.parse("2026-11-01T10:00:00Z"))!!
        assertEquals("A000123", input.value)
        assertEquals(ScanSource.DATAWEDGE, input.source)
        assertEquals("LABEL-TYPE-CODE128", input.symbology)
    }

    @Test fun `ignores broadcasts without data`() {
        assertNull(DataWedgeIntentParser.parse({ null }))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ScanInputBusTest {
    private fun scan(value: String, ms: Long, source: ScanSource = ScanSource.DATAWEDGE) =
        ScanInput(value, source, null, Instant.ofEpochMilli(ms))

    @Test fun `normalises, debounces and emits once`() = runTest(UnconfinedTestDispatcher()) {
        val bus = ScanInputBus()
        val received = mutableListOf<ScanInput>()
        val job = launch { bus.scans.toList(received) }

        assertEquals(ScanOutcome.ACCEPTED, bus.submit(scan("A000123\r\n", 0)))
        assertEquals(ScanOutcome.DEBOUNCED, bus.submit(scan("A000123", 500)))
        assertEquals(ScanOutcome.EMPTY, bus.submit(scan("  ", 600)))
        assertEquals(ScanOutcome.ACCEPTED, bus.submit(scan("A000123", 2_000)))

        assertEquals(listOf("A000123", "A000123"), received.map { it.value })
        job.cancel()
    }

    @Test fun `nfc text is canonicalised and invalid uids are dropped`() = runTest(UnconfinedTestDispatcher()) {
        val bus = ScanInputBus()
        val received = mutableListOf<ScanInput>()
        val job = launch { bus.scans.toList(received) }

        bus.submit(scan("04-46-88-0a-bf-2a-81", 0, ScanSource.NFC))
        assertEquals(ScanOutcome.EMPTY, bus.submit(scan("not hex", 10, ScanSource.NFC)))

        assertEquals(listOf("0446880ABF2A81"), received.map { it.value })
        job.cancel()
    }

    @Test fun `log keeps the newest entries first`() {
        val bus = ScanInputBus()
        bus.submit(scan("A1", 0)); bus.submit(scan("A2", 10))
        assertEquals(listOf("A2", "A1"), bus.log.value.map { it.input.value })
        bus.clearLog()
        assertTrue(bus.log.value.isEmpty())
    }
}
