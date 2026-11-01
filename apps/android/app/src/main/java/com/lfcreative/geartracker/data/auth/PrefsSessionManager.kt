package com.lfcreative.geartracker.data.auth

import android.content.Context
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.serialization.json.Json

/**
 * Persists the Supabase session in app-private SharedPreferences (backup is disabled in the manifest),
 * so we do not depend on the library's platform default.
 */
class PrefsSessionManager(context: Context) : SessionManager {
    private val prefs = context.getSharedPreferences("auth_session", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun saveSession(session: UserSession) {
        prefs.edit().putString(KEY, json.encodeToString(UserSession.serializer(), session)).apply()
    }

    override suspend fun loadSession(): UserSession? {
        val raw = prefs.getString(KEY, null) ?: return null
        return try {
            json.decodeFromString(UserSession.serializer(), raw)
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun deleteSession() {
        prefs.edit().remove(KEY).apply()
    }

    private companion object {
        const val KEY = "session"
    }
}
