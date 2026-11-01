package com.lfcreative.geartracker.data.auth

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.exceptions.RestException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SignInFailure(message: String, cause: Throwable? = null) : Exception(message, cause)

@Singleton
class SupabaseAuthRepository @Inject constructor(private val client: SupabaseClient) : AuthRepository {

    override val state: Flow<AuthState> = client.auth.sessionStatus.map { status ->
        when (status) {
            is SessionStatus.Authenticated -> AuthState.SignedIn(status.session.user?.email)
            // Offline-first: a failed token refresh (no network) keeps the cached session usable.
            is SessionStatus.RefreshFailure -> AuthState.SignedIn(client.auth.currentUserOrNull()?.email)
            is SessionStatus.NotAuthenticated -> AuthState.SignedOut
            else -> AuthState.Loading
        }
    }

    override suspend fun signIn(email: String, password: String): Result<Unit> = try {
        client.auth.signInWith(Email) {
            this.email = email.trim()
            this.password = password
        }
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: RestException) {
        Result.failure(SignInFailure("Invalid email or password", e))
    } catch (e: Exception) {
        Result.failure(SignInFailure("Cannot reach the server. Check the connection and server URL.", e))
    }

    override suspend fun signOut(): Result<Unit> = try {
        client.auth.signOut()
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
}
