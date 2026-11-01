package com.lfcreative.geartracker.sync

import com.lfcreative.geartracker.data.local.OutboxDao
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * One sync pass: push the outbox (oldest first, in batches), then, only when nothing is left waiting,
 * refresh the offline copy. Pulling while local events are unsent would overwrite them with stale server data.
 *
 * Fate of each event:
 *  - accepted / duplicate  -> SENT (kept briefly, then purged)
 *  - rejected              -> REJECTED with the server's reason: it never retries, it waits in "Sync issues"
 *  - not mentioned / the whole call failed -> stays PENDING, attempts + error recorded, retried with backoff
 */
class SyncEngine(
    private val outbox: OutboxDao,
    private val events: EventsApi,
    private val mirror: MirrorSync,
    private val status: SyncStatusStore,
    private val clock: Clock,
    private val batchSize: Int = BATCH_SIZE,
    private val mirrorEveryMs: Long = MIRROR_EVERY_MS,
) {
    @Inject constructor(
        outbox: OutboxDao, events: EventsApi, mirror: MirrorSyncFactory, status: SyncStatusStore, clock: Clock,
    ) : this(outbox, events, mirror.mirrorSync, status, clock, BATCH_SIZE, MIRROR_EVERY_MS)

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun run(forceMirror: Boolean = false): SyncOutcome {
        var pushed = 0
        var rejected = 0
        repeat(MAX_BATCHES) {
            val batch = outbox.pending(batchSize)
            if (batch.isEmpty()) return pullIfDue(pushed, rejected, forceMirror)

            val payloads = batch.map { json.parseToJsonElement(it.payloadJson).jsonObject }
            val response = events.apply(payloads).getOrElse { e ->
                return fail(batch.map { it.id }, e.message ?: "Cannot reach the server")
            }
            val byId = response.associateBy { it.id }

            val sent = ArrayList<String>()
            val unanswered = ArrayList<String>()
            for (event in batch) {
                val r = byId[event.id]
                when {
                    r == null -> unanswered += event.id
                    r.accepted -> sent += event.id
                    r.rejected -> { outbox.markRejected(event.id, r.reason ?: "Rejected by the server"); rejected++ }
                    else -> unanswered += event.id
                }
            }
            if (sent.isNotEmpty()) outbox.markSent(sent)
            pushed += sent.size
            // An event the server did not answer is NOT lost or dropped: it stays pending and is retried.
            if (unanswered.isNotEmpty()) return fail(unanswered, "The server did not answer for ${unanswered.size} event(s)")
            status.update { it.copy(lastPushAt = clock.millis(), lastError = null) }
        }
        return SyncOutcome.Retry("Too many events waiting; continuing shortly")
    }

    private suspend fun pullIfDue(pushed: Int, rejected: Int, force: Boolean): SyncOutcome {
        outbox.purgeSent(clock.millis() - KEEP_SENT_MS)
        val now = clock.millis()
        val due = force || now - status.status.value.lastMirrorAt >= mirrorEveryMs
        if (!due) return SyncOutcome.Done(pushed, rejected, mirrorRefreshed = false)
        val refreshed = mirror.refresh()
        return if (refreshed.isSuccess) {
            status.update { it.copy(lastPushAt = if (pushed > 0) now else it.lastPushAt, lastMirrorAt = now, lastError = null) }
            SyncOutcome.Done(pushed, rejected, mirrorRefreshed = true)
        } else {
            val message = refreshed.exceptionOrNull()?.message ?: "Could not refresh the offline copy"
            status.update { it.copy(lastError = message) }
            SyncOutcome.Retry(message)
        }
    }

    private suspend fun fail(ids: List<String>, message: String): SyncOutcome.Retry {
        outbox.recordFailure(ids, message)
        status.update { it.copy(lastError = message) }
        return SyncOutcome.Retry(message)
    }

    companion object {
        const val BATCH_SIZE = 100
        const val MAX_BATCHES = 50
        const val MIRROR_EVERY_MS = 5 * 60_000L
        const val KEEP_SENT_MS = 7 * 24 * 3_600_000L
    }
}

/** Anything that wants a sync soon (e.g. after queueing an event) asks through this. */
interface SyncTrigger {
    fun requestSync()
}

@Singleton
class MirrorSyncFactory @Inject constructor(api: MirrorApi, store: MirrorStore) {
    val mirrorSync = MirrorSync(api, store)
}
