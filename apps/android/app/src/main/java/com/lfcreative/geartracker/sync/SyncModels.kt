package com.lfcreative.geartracker.sync

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** One entry of the apply_scan_events response (docs/architecture.md). */
data class EventResult(val id: String, val status: String, val reason: String?, val code: String?) {
    val accepted get() = status == "accepted" || status == "duplicate"
    val rejected get() = status == "rejected"
}

sealed interface SyncOutcome {
    data class Done(val pushed: Int, val rejected: Int, val mirrorRefreshed: Boolean) : SyncOutcome
    /** Something transient went wrong (network, server). WorkManager retries with backoff. */
    data class Retry(val error: String) : SyncOutcome
}

object EventResults {
    fun parse(array: JsonArray): List<EventResult> = array.mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        fun str(key: String) = (o[key] as? JsonPrimitive)?.contentOrNull
        EventResult(str("id") ?: return@mapNotNull null, str("status").orEmpty(), str("reason"), str("code"))
    }

    @Suppress("unused")
    fun element(e: JsonElement?) = e
}
