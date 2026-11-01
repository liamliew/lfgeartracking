package com.lfcreative.geartracker.data.settings

import android.content.Context
import com.lfcreative.geartracker.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class AppSettings(
    /** Null means "use the build default" (BuildConfig.SUPABASE_URL). */
    val serverUrl: String? = null,
    /** Pack/Move target closes after this many idle seconds. */
    val idleTimeoutSec: Int = DEFAULT_IDLE_TIMEOUT_SEC,
    val soundEnabled: Boolean = true,
    val vibrationEnabled: Boolean = true,
) {
    companion object {
        const val DEFAULT_IDLE_TIMEOUT_SEC = 90
        val IDLE_TIMEOUT_CHOICES = listOf(60, 90, 120, 180)
    }
}

/** The settings a scan session needs; an interface so sessions can be tested without Android. */
interface SessionSettings {
    val idleTimeoutSec: Int
}

/** SharedPreferences-backed so the server URL can be read synchronously when the DI graph is built. */
@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext context: Context) : SessionSettings {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    override val idleTimeoutSec: Int get() = _settings.value.idleTimeoutSec

    val effectiveServerUrl: String get() = _settings.value.serverUrl ?: BuildConfig.SUPABASE_URL

    fun setServerUrl(url: String?) = write({ if (url == null) remove(KEY_URL) else putString(KEY_URL, url) }) { it.copy(serverUrl = url) }
    fun setIdleTimeoutSec(value: Int) = write({ putInt(KEY_IDLE, value) }) { it.copy(idleTimeoutSec = value) }
    fun setSoundEnabled(value: Boolean) = write({ putBoolean(KEY_SOUND, value) }) { it.copy(soundEnabled = value) }
    fun setVibrationEnabled(value: Boolean) = write({ putBoolean(KEY_VIBRATION, value) }) { it.copy(vibrationEnabled = value) }

    private fun write(edit: android.content.SharedPreferences.Editor.() -> Unit, update: (AppSettings) -> AppSettings) {
        prefs.edit().apply(edit).apply()
        _settings.update(update)
    }

    private fun read() = AppSettings(
        serverUrl = prefs.getString(KEY_URL, null),
        idleTimeoutSec = prefs.getInt(KEY_IDLE, AppSettings.DEFAULT_IDLE_TIMEOUT_SEC),
        soundEnabled = prefs.getBoolean(KEY_SOUND, true),
        vibrationEnabled = prefs.getBoolean(KEY_VIBRATION, true),
    )

    private companion object {
        const val KEY_URL = "server_url"
        const val KEY_IDLE = "idle_timeout_sec"
        const val KEY_SOUND = "sound"
        const val KEY_VIBRATION = "vibration"
    }
}
