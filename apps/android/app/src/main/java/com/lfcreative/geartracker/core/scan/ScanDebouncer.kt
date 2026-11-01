package com.lfcreative.geartracker.core.scan

/** Ignores the same value scanned again within [windowMs] of the last accepted scan. Thread safe. */
class ScanDebouncer(private val windowMs: Long = DEFAULT_WINDOW_MS) {
    private val lastAccepted = HashMap<String, Long>()

    @Synchronized
    fun accept(value: String, nowMs: Long): Boolean {
        val last = lastAccepted[value]
        if (last != null && nowMs >= last && nowMs - last < windowMs) return false
        lastAccepted[value] = nowMs
        if (lastAccepted.size > MAX_TRACKED) lastAccepted.entries.removeAll { nowMs - it.value >= windowMs }
        return true
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 1_500L
        private const val MAX_TRACKED = 64
    }
}
