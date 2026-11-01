package com.lfcreative.geartracker.core.scan

import com.lfcreative.geartracker.core.ScanInput
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class ScanOutcome { ACCEPTED, DEBOUNCED, EMPTY }

data class ScanLogEntry(val input: ScanInput, val outcome: ScanOutcome)

/**
 * The ONE flow every input feeds (DataWedge, NFC, debug panel, manual entry): normalise -> debounce -> emit.
 * Sessions (task 5) collect [scans]. [log] feeds the Diagnostics screen.
 */
@Singleton
class ScanInputBus @Inject constructor() {
    private val debouncer = ScanDebouncer()
    private val _scans = MutableSharedFlow<ScanInput>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val scans: SharedFlow<ScanInput> = _scans.asSharedFlow()

    private val _log = MutableStateFlow<List<ScanLogEntry>>(emptyList())
    val log: StateFlow<List<ScanLogEntry>> = _log.asStateFlow()

    /** Safe to call from any thread (NFC callbacks arrive off the main thread). */
    fun submit(input: ScanInput): ScanOutcome {
        val value = when (input.source) {
            com.lfcreative.geartracker.core.ScanSource.NFC -> ScanNormalizer.nfcFromText(input.value)
            else -> ScanNormalizer.barcode(input.value)
        }
        val outcome = when {
            value == null -> ScanOutcome.EMPTY
            !debouncer.accept(value, input.ts.toEpochMilli()) -> ScanOutcome.DEBOUNCED
            else -> ScanOutcome.ACCEPTED
        }
        _log.update { (listOf(ScanLogEntry(input, outcome)) + it).take(LOG_SIZE) }
        if (outcome == ScanOutcome.ACCEPTED && value != null) _scans.tryEmit(input.copy(value = value))
        return outcome
    }

    fun clearLog() = _log.update { emptyList() }

    fun submitNow(value: String, source: com.lfcreative.geartracker.core.ScanSource, symbology: String? = null) =
        submit(ScanInput(value, source, symbology, Instant.now()))

    private companion object {
        const val LOG_SIZE = 30
    }
}
