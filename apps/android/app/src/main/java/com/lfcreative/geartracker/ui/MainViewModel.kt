package com.lfcreative.geartracker.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lfcreative.geartracker.data.auth.AuthRepository
import com.lfcreative.geartracker.data.auth.AuthState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class MainViewModel @Inject constructor(auth: AuthRepository) : ViewModel() {
    val authState: StateFlow<AuthState> =
        auth.state.stateIn(viewModelScope, SharingStarted.Eagerly, AuthState.Loading)
}
