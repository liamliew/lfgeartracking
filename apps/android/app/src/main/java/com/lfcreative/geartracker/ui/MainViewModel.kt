package com.lfcreative.geartracker.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lfcreative.geartracker.core.ScanInput
import com.lfcreative.geartracker.core.scan.ScanInputBus
import com.lfcreative.geartracker.data.auth.AuthRepository
import com.lfcreative.geartracker.data.auth.AuthState
import com.lfcreative.geartracker.sync.SyncTrigger
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class MainViewModel @Inject constructor(auth: AuthRepository, private val bus: ScanInputBus, sync: SyncTrigger) : ViewModel() {
    init {
        // A fresh sign-in drains anything queued earlier and refreshes the offline copy.
        auth.state.filterIsInstance<AuthState.SignedIn>().distinctUntilChanged().onEach { sync.requestSync() }.launchIn(viewModelScope)
    }

    fun submitScan(input: ScanInput) {
        bus.submit(input)
    }

    val authState: StateFlow<AuthState> =
        auth.state.stateIn(viewModelScope, SharingStarted.Eagerly, AuthState.Loading)
}
