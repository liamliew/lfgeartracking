package com.lfcreative.geartracker.ui.scan

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.lfcreative.geartracker.core.ScanInput
import com.lfcreative.geartracker.core.scan.DataWedgeIntentParser

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Put this in every scan screen. While the screen is RESUMED it listens for the scan broadcast and NFC tags;
 * on pause everything is released, so a screen that is not showing never consumes scans.
 *
 * The receiver is registered EXPORTED because DataWedge (another app) is the sender; `adb shell am broadcast`
 * uses the same action. Any app on the device could therefore also send scans, which is acceptable on managed,
 * single-purpose Zebra devices.
 */
@Composable
fun ScanInputEffect(onScan: (ScanInput) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnScan = rememberUpdatedState(onScan)

    DisposableEffect(lifecycleOwner, context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                DataWedgeIntentParser.parse({ key -> intent.getStringExtra(key) })?.let(currentOnScan.value)
            }
        }
        val nfc = NfcReader { currentOnScan.value(it) }
        var registered = false

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> if (!registered) {
                    ContextCompat.registerReceiver(
                        context, receiver, IntentFilter(DataWedgeIntentParser.ACTION_SCAN), ContextCompat.RECEIVER_EXPORTED,
                    )
                    context.findActivity()?.let(nfc::enable)
                    registered = true
                }
                Lifecycle.Event.ON_PAUSE -> if (registered) {
                    context.unregisterReceiver(receiver)
                    context.findActivity()?.let(nfc::disable)
                    registered = false
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (registered) {
                context.unregisterReceiver(receiver)
                context.findActivity()?.let(nfc::disable)
            }
        }
    }
}
