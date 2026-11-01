package com.lfcreative.geartracker.data.auth

import kotlinx.coroutines.flow.Flow

sealed interface AuthState {
    data object Loading : AuthState
    data object SignedOut : AuthState
    data class SignedIn(val email: String?) : AuthState
}

interface AuthRepository {
    val state: Flow<AuthState>
    suspend fun signIn(email: String, password: String): Result<Unit>
    suspend fun signOut(): Result<Unit>
}
