package com.lfcreative.geartracker.asset

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lfcreative.geartracker.core.ScanSource
import com.lfcreative.geartracker.core.scan.ScanInputBus
import com.lfcreative.geartracker.data.outbox.EventOutbox
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

data class DetailUiState(
    val loading: Boolean = true,
    val detail: AssetDetail? = null,
    val notFound: Boolean = false,
    val error: String? = null,
    /** The next NFC tap is saved as a new tag of this asset. */
    val armedForNfc: Boolean = false,
    val confirmDamage: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class AssetDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val api: AssetApi,
    private val outbox: EventOutbox,
    private val bus: ScanInputBus,
    private val clock: Clock,
) : ViewModel() {
    private val assetId: String = checkNotNull(savedStateHandle["id"])
    private val _ui = MutableStateFlow(DetailUiState())
    val ui: StateFlow<DetailUiState> = _ui.asStateFlow()

    init {
        load()
        viewModelScope.launch {
            bus.scans.collect { scan ->
                if (_ui.value.armedForNfc && scan.source == ScanSource.NFC) assignNfc(scan.value)
            }
        }
    }

    fun load() {
        _ui.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            api.detail(assetId).fold(
                onSuccess = { d -> _ui.update { it.copy(loading = false, detail = d, notFound = d == null) } },
                onFailure = { _ui.update { s -> s.copy(loading = false, error = "Cannot load this asset. Check the connection.") } },
            )
        }
    }

    fun armNfc(on: Boolean) = _ui.update { it.copy(armedForNfc = on, message = if (on) "Tap an NFC tag on the device" else null) }
    fun askReportDamage(show: Boolean) = _ui.update { it.copy(confirmDamage = show) }

    private fun assignNfc(uid: String) {
        _ui.update { it.copy(armedForNfc = false, message = "Tag $uid queued; it is saved when the device syncs") }
        viewModelScope.launch {
            outbox.enqueue(
                "tag_assigned",
                buildJsonObject {
                    put("asset_id", JsonPrimitive(assetId))
                    put("payload", buildJsonObject { put("tag", buildJsonObject { put("value", JsonPrimitive(uid)); put("kind", JsonPrimitive("nfc")) }) })
                },
                clock.instant(),
            )
        }
    }

    fun reportDamage() {
        _ui.update { it.copy(confirmDamage = false, message = "Damage report queued; it is saved when the device syncs") }
        viewModelScope.launch {
            outbox.enqueue(
                "status_changed",
                buildJsonObject {
                    put("asset_id", JsonPrimitive(assetId))
                    put("payload", buildJsonObject { put("status", JsonPrimitive("in_repair")) })
                },
                clock.instant(),
            )
        }
    }
}
