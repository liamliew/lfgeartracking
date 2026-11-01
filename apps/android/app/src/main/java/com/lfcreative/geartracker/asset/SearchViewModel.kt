package com.lfcreative.geartracker.asset

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lfcreative.geartracker.core.scan.ScanInputBus
import com.lfcreative.geartracker.session.ResolvedTag
import com.lfcreative.geartracker.session.TagResolving
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SearchUiState(
    val query: String = "",
    val loading: Boolean = false,
    val results: List<AssetSummary> = emptyList(),
    val message: String? = null,
)

@OptIn(FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val api: AssetApi,
    private val resolver: TagResolving,
    private val bus: ScanInputBus,
) : ViewModel() {
    private val _ui = MutableStateFlow(SearchUiState())
    val ui: StateFlow<SearchUiState> = _ui.asStateFlow()

    private val _open = Channel<String>(Channel.BUFFERED)
    /** Asset ids the screen should open (a scan that matched gear). */
    val open = _open.receiveAsFlow()

    private val queries = MutableStateFlow("")

    init {
        viewModelScope.launch {
            queries.debounce(DEBOUNCE_MS).distinctUntilChanged().collect { runSearch(it.trim()) }
        }
        viewModelScope.launch { bus.scans.collect { openScanned(it.value) } }
    }

    fun onQuery(text: String) {
        _ui.update { it.copy(query = text, message = null) }
        queries.value = text
    }

    private suspend fun runSearch(q: String) {
        if (q.isEmpty()) {
            _ui.update { it.copy(results = emptyList(), loading = false, message = null) }
            return
        }
        _ui.update { it.copy(loading = true) }
        api.search(q).fold(
            onSuccess = { rows -> _ui.update { it.copy(loading = false, results = rows, message = if (rows.isEmpty()) "No match for \"$q\"" else null) } },
            onFailure = { _ui.update { s -> s.copy(loading = false, message = "Cannot search. Check the connection.") } },
        )
    }

    private suspend fun openScanned(value: String) {
        when (val tag = resolver.resolve(value)) {
            is ResolvedTag.Item -> _open.send(tag.id)
            is ResolvedTag.Container -> _open.send(tag.id)
            is ResolvedTag.Location -> _ui.update { it.copy(message = "${tag.path} is a location, not gear") }
            is ResolvedTag.Unknown -> _ui.update { it.copy(message = "Nothing is registered for $value") }
        }
    }

    private companion object { const val DEBOUNCE_MS = 300L }
}
