package com.lfcreative.geartracker.ui.settings

import android.content.Context
import android.nfc.NfcAdapter
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lfcreative.geartracker.BuildConfig
import com.lfcreative.geartracker.core.ScanInput
import com.lfcreative.geartracker.core.ServerUrl
import com.lfcreative.geartracker.core.scan.ScanInputBus
import com.lfcreative.geartracker.core.scan.ScanLogEntry
import com.lfcreative.geartracker.data.auth.AuthRepository
import com.lfcreative.geartracker.data.auth.AuthState
import com.lfcreative.geartracker.data.local.AssetDao
import com.lfcreative.geartracker.data.local.LocationDao
import com.lfcreative.geartracker.data.local.TagDao
import com.lfcreative.geartracker.data.outbox.OutboxRepository
import com.lfcreative.geartracker.data.settings.AppSettings
import com.lfcreative.geartracker.data.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val effectiveServerUrl: String = "",
    val serverUrlInput: String = "",
    val serverUrlMessage: String? = null,
    val email: String? = null,
    val pendingEvents: Int = 0,
    val rejectedEvents: Int = 0,
    val cachedTags: Int = 0,
    val cachedAssets: Int = 0,
    val cachedLocations: Int = 0,
    val nfcStatus: String = "",
    val scanLog: List<ScanLogEntry> = emptyList(),
    val anonKeyConfigured: Boolean = true,
    val versionName: String = BuildConfig.VERSION_NAME,
    val versionCode: Int = BuildConfig.VERSION_CODE,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val auth: AuthRepository,
    private val bus: ScanInputBus,
    outbox: OutboxRepository,
    tags: TagDao,
    assets: AssetDao,
    locations: LocationDao,
) : ViewModel() {

    private val urlEdit = MutableStateFlow<Pair<String, String?>?>(null) // (input, message)

    private data class Counts(val pending: Int, val rejected: Int, val tags: Int, val assets: Int, val locations: Int)

    private val counts = combine(
        outbox.pendingCount, outbox.rejectedCount, tags.count(), assets.count(), locations.count(),
    ) { p, r, t, a, l -> Counts(p, r, t, a, l) }

    val state: StateFlow<SettingsUiState> = combine(
        settingsRepository.settings, auth.state, counts, urlEdit, bus.log,
    ) { settings, authState, c, edit, scanLog ->
        SettingsUiState(
            settings = settings,
            effectiveServerUrl = settingsRepository.effectiveServerUrl,
            serverUrlInput = edit?.first ?: settings.serverUrl.orEmpty(),
            serverUrlMessage = edit?.second,
            email = (authState as? AuthState.SignedIn)?.email,
            pendingEvents = c.pending,
            rejectedEvents = c.rejected,
            cachedTags = c.tags,
            cachedAssets = c.assets,
            cachedLocations = c.locations,
            nfcStatus = nfcStatus(),
            scanLog = scanLog,
            anonKeyConfigured = BuildConfig.SUPABASE_ANON_KEY.isNotBlank(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun onServerUrlInput(value: String) = urlEdit.update { value to null }

    fun saveServerUrl() {
        val input = urlEdit.value?.first ?: state.value.serverUrlInput
        if (input.isBlank()) {
            settingsRepository.setServerUrl(null)
            urlEdit.value = null
            urlEdit.update { "" to "Using the build default. Restart the app to apply." }
            return
        }
        ServerUrl.normalize(input, allowCleartext = BuildConfig.DEBUG).fold(
            onSuccess = {
                settingsRepository.setServerUrl(it)
                urlEdit.value = it to "Saved. Restart the app to apply."
            },
            onFailure = { urlEdit.value = input to it.message },
        )
    }

    fun submitScan(input: ScanInput) {
        bus.submit(input)
    }

    fun clearScanLog() = bus.clearLog()

    fun setIdleTimeout(seconds: Int) = settingsRepository.setIdleTimeoutSec(seconds)
    fun setSound(enabled: Boolean) = settingsRepository.setSoundEnabled(enabled)
    fun setVibration(enabled: Boolean) = settingsRepository.setVibrationEnabled(enabled)
    fun signOut() { viewModelScope.launch { auth.signOut() } }

    private fun nfcStatus(): String {
        val adapter = NfcAdapter.getDefaultAdapter(context) ?: return "Not available on this device"
        return if (adapter.isEnabled) "Enabled" else "Turned off in system settings"
    }
}
