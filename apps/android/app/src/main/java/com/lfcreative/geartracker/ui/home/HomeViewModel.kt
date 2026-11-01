package com.lfcreative.geartracker.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lfcreative.geartracker.data.auth.AuthRepository
import com.lfcreative.geartracker.data.auth.AuthState
import com.lfcreative.geartracker.data.local.OpenCheckoutDao
import com.lfcreative.geartracker.data.outbox.OutboxRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class HomeUiState(
    val email: String? = null,
    val pendingEvents: Int = 0,
    val rejectedEvents: Int = 0,
    val overdueCount: Int = 0,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    auth: AuthRepository,
    outbox: OutboxRepository,
    checkouts: OpenCheckoutDao,
) : ViewModel() {
    val state: StateFlow<HomeUiState> = combine(
        auth.state,
        outbox.pendingCount,
        outbox.rejectedCount,
        checkouts.overdueCount(System.currentTimeMillis()),
    ) { authState, pending, rejected, overdue ->
        HomeUiState(
            email = (authState as? AuthState.SignedIn)?.email,
            pendingEvents = pending,
            rejectedEvents = rejected,
            overdueCount = overdue,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())
}
