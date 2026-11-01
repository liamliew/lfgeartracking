package com.lfcreative.geartracker.core

import java.time.Instant

enum class ScanSource { DATAWEDGE, NFC, DEBUG, MANUAL }

/**
 * Every input (DataWedge, NFC, debug panel, manual entry) emits this into ONE flow.
 * Wired up in task 4; defined now so the rest of the app can depend on it.
 */
data class ScanInput(
    val value: String,
    val source: ScanSource,
    val symbology: String? = null,
    val ts: Instant = Instant.now(),
)
