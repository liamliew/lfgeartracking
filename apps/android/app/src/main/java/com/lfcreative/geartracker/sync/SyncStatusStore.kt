package com.lfcreative.geartracker.sync

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SyncStatus(val lastPushAt: Long = 0, val lastMirrorAt: Long = 0, val lastError: String? = null)

/** When we last synced and what went wrong, kept across restarts for the Diagnostics screen. */
interface SyncStatusStore {
    val status: StateFlow<SyncStatus>
    fun update(transform: (SyncStatus) -> SyncStatus)
}

@Singleton
class PrefsSyncStatusStore @Inject constructor(@ApplicationContext context: Context) : SyncStatusStore {
    private val prefs = context.getSharedPreferences("sync_status", Context.MODE_PRIVATE)
    private val _status = MutableStateFlow(
        SyncStatus(prefs.getLong("push", 0), prefs.getLong("mirror", 0), prefs.getString("error", null)),
    )
    override val status: StateFlow<SyncStatus> = _status.asStateFlow()

    @Synchronized
    override fun update(transform: (SyncStatus) -> SyncStatus) {
        val next = transform(_status.value)
        prefs.edit().putLong("push", next.lastPushAt).putLong("mirror", next.lastMirrorAt).putString("error", next.lastError).apply()
        _status.value = next
    }
}
