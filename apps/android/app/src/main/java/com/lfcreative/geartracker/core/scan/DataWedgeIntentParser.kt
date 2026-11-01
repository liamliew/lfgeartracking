package com.lfcreative.geartracker.core.scan

import com.lfcreative.geartracker.core.ScanInput
import com.lfcreative.geartracker.core.ScanSource
import java.time.Instant

/**
 * Reads the scan broadcast. The extra names are the ones in our spec and in the adb simulate-scan command
 * (docs/plan.md, CLAUDE.md). The real profile that makes DataWedge send this broadcast is configured in part 2.
 */
object DataWedgeIntentParser {
    const val ACTION_SCAN = "com.lfcreative.geartracker.SCAN"
    const val EXTRA_DATA = "com.symbol.datawedge.data_string"
    const val EXTRA_LABEL_TYPE = "com.symbol.datawedge.label_type"
    const val EXTRA_SOURCE = "com.symbol.datawedge.source"

    fun parse(extra: (String) -> String?, now: Instant = Instant.now()): ScanInput? {
        val data = extra(EXTRA_DATA) ?: return null
        return ScanInput(value = data, source = ScanSource.DATAWEDGE, symbology = extra(EXTRA_LABEL_TYPE), ts = now)
    }
}
