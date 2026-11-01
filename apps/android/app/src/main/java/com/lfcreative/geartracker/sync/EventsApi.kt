package com.lfcreative.geartracker.sync

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

interface EventsApi {
    /** Sends events; a failure means NOTHING is known about their fate (retry the same events later). */
    suspend fun apply(events: List<JsonObject>): Result<List<EventResult>>
}

@Singleton
class SupabaseEventsApi @Inject constructor(private val client: SupabaseClient) : EventsApi {
    override suspend fun apply(events: List<JsonObject>): Result<List<EventResult>> = try {
        val response = client.postgrest.rpc("apply_scan_events", buildJsonObject { put("p_events", JsonArray(events)) })
        Result.success(EventResults.parse(response.decodeAs<JsonArray>()))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
}
