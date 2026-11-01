package com.lfcreative.geartracker.data.outbox

import com.lfcreative.geartracker.data.local.OutboxDao
import com.lfcreative.geartracker.data.local.OutboxEventEntity
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Append-only queue of idempotent events. The id is generated here, on the device, so a retry after a
 * lost response can never create a duplicate server-side (apply_scan_events ignores known ids).
 * The sync worker (task 7) drains it.
 */
@Singleton
class OutboxRepository @Inject constructor(private val dao: OutboxDao) {
    val pendingCount: Flow<Int> = dao.pendingCount()
    val rejectedCount: Flow<Int> = dao.rejectedCount()

    /** [fields] are the event fields besides id/type/occurred_at (asset_id, to_location_id, payload, ...). */
    suspend fun enqueue(
        type: String,
        fields: JsonObject,
        occurredAt: Instant = Instant.now(),
        id: UUID = UUID.randomUUID(),
    ): UUID {
        val event = buildJsonObject {
            put("id", JsonPrimitive(id.toString()))
            put("type", JsonPrimitive(type))
            put("occurred_at", JsonPrimitive(occurredAt.toString()))
            fields.forEach { (k, v) -> put(k, v) }
        }
        dao.insert(
            OutboxEventEntity(
                id = id.toString(),
                type = type,
                payloadJson = event.toString(),
                occurredAt = occurredAt.toEpochMilli(),
                createdAt = System.currentTimeMillis(),
            ),
        )
        return id
    }
}
