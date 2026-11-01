package com.lfcreative.geartracker.core.scan

/** Turns raw reader output into the canonical tag value (see `tags.value` in docs/architecture.md). */
object ScanNormalizer {
    private val control = Regex("[\\u0000-\\u001F\\u007F]")
    private val separators = Regex("[\\s:\\-]")
    private val hex = Regex("^([0-9A-F]{2}){4,}$")

    /** Barcode text: strip control characters (CR/LF suffixes) and surrounding spaces. Null when nothing is left. */
    fun barcode(raw: String): String? = raw.replace(control, "").trim().takeIf { it.isNotEmpty() }

    /** NFC UID bytes -> uppercase hex without separators, e.g. `0446880ABF2A81`. */
    fun nfcUid(bytes: ByteArray): String? =
        if (bytes.isEmpty()) null else bytes.joinToString("") { "%02X".format(it.toInt() and 0xFF) }

    /** Typed or legacy text (`04-46-88-0a-bf-2a-81`) -> canonical NFC hex, or null if it is not a valid UID. */
    fun nfcFromText(text: String): String? =
        text.replace(separators, "").uppercase().takeIf { hex.matches(it) }
}
