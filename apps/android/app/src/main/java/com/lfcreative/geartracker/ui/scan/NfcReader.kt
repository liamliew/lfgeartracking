package com.lfcreative.geartracker.ui.scan

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import com.lfcreative.geartracker.core.ScanInput
import com.lfcreative.geartracker.core.ScanSource
import com.lfcreative.geartracker.core.scan.ScanNormalizer
import java.time.Instant

/**
 * NFC is not a DataWedge input: we use standard reader mode while a scan screen is resumed.
 * Identity is Tag.getId() as uppercase hex; we never read MIFARE Classic sectors.
 */
class NfcReader(private val onScan: (ScanInput) -> Unit) {
    private val flags = NfcAdapter.FLAG_READER_NFC_A or
        NfcAdapter.FLAG_READER_NFC_B or
        NfcAdapter.FLAG_READER_NFC_F or
        NfcAdapter.FLAG_READER_NFC_V or
        NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
        NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS

    /** Returns false when the device has no NFC adapter or it is switched off. */
    fun enable(activity: Activity): Boolean {
        val adapter = NfcAdapter.getDefaultAdapter(activity) ?: return false
        if (!adapter.isEnabled) return false
        adapter.enableReaderMode(activity, { tag: Tag -> deliver(tag) }, flags, null)
        return true
    }

    fun disable(activity: Activity) {
        NfcAdapter.getDefaultAdapter(activity)?.disableReaderMode(activity)
    }

    // The callback does not run on the main thread, so only thread-safe work happens here.
    private fun deliver(tag: Tag) {
        val uid = ScanNormalizer.nfcUid(tag.id) ?: return
        onScan(ScanInput(uid, ScanSource.NFC, tag.techList.firstOrNull()?.substringAfterLast('.'), Instant.now()))
    }
}
