package com.lfcreative.geartracker.asset.create

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json

/** Remembers the last created asset's choices on the device ("Same as last", recent templates, last location). */
interface WizardMemory {
    fun load(): Remembered?
    fun save(value: Remembered)
}

@Singleton
class PrefsWizardMemory @Inject constructor(@ApplicationContext context: Context) : WizardMemory {
    private val prefs = context.getSharedPreferences("create_asset", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    override fun load(): Remembered? = prefs.getString(KEY, null)?.let {
        try { json.decodeFromString(Remembered.serializer(), it) } catch (_: Exception) { null }
    }

    override fun save(value: Remembered) {
        prefs.edit().putString(KEY, json.encodeToString(Remembered.serializer(), value)).apply()
    }

    private companion object { const val KEY = "remembered" }
}
