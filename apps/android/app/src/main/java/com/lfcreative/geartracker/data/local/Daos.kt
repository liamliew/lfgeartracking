package com.lfcreative.geartracker.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TagDao {
    @Query("DELETE FROM tags")
    suspend fun clear()

    @Query("SELECT * FROM tags WHERE value = :value LIMIT 1")
    suspend fun find(value: String): TagEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<TagEntity>)

    @Query("SELECT COUNT(*) FROM tags")
    fun count(): Flow<Int>
}

@Dao
interface AssetDao {
    @Query("DELETE FROM assets")
    suspend fun clear()

    @Query("SELECT * FROM assets WHERE id = :id LIMIT 1")
    suspend fun find(id: String): AssetEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<AssetEntity>)

    @Query("SELECT COUNT(*) FROM assets")
    fun count(): Flow<Int>
}

@Dao
interface LocationDao {
    @Query("DELETE FROM locations")
    suspend fun clear()

    @Query("SELECT * FROM locations WHERE id = :id LIMIT 1")
    suspend fun find(id: String): LocationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<LocationEntity>)

    @Query("SELECT COUNT(*) FROM locations")
    fun count(): Flow<Int>
}

@Dao
interface ProjectDao {
    @Query("DELETE FROM projects")
    suspend fun clear()

    @Query("SELECT * FROM projects ORDER BY name")
    fun all(): Flow<List<ProjectEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<ProjectEntity>)
}

@Dao
interface OpenCheckoutDao {
    @Query("DELETE FROM open_checkouts")
    suspend fun clear()

    @Query("SELECT * FROM open_checkouts WHERE assetId = :assetId LIMIT 1")
    suspend fun forAsset(assetId: String): OpenCheckoutEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<OpenCheckoutEntity>)

    @Query("SELECT COUNT(*) FROM open_checkouts WHERE dueAt IS NOT NULL AND dueAt < :nowMillis")
    fun overdueCount(nowMillis: Long): Flow<Int>
}

@Dao
interface OutboxDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(event: OutboxEventEntity): Long

    @Query("SELECT * FROM outbox_events WHERE state = 'PENDING' ORDER BY occurredAt, createdAt LIMIT :limit")
    suspend fun pending(limit: Int): List<OutboxEventEntity>

    @Query("SELECT COUNT(*) FROM outbox_events WHERE state = 'PENDING'")
    fun pendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM outbox_events WHERE state = 'REJECTED'")
    fun rejectedCount(): Flow<Int>

    @Query("UPDATE outbox_events SET state = 'SENT' WHERE id IN (:ids)")
    suspend fun markSent(ids: List<String>)

    @Query("UPDATE outbox_events SET state = 'REJECTED', lastError = :error WHERE id = :id")
    suspend fun markRejected(id: String, error: String)

    @Query("UPDATE outbox_events SET attempts = attempts + 1, lastError = :error WHERE id IN (:ids)")
    suspend fun recordFailure(ids: List<String>, error: String)

    @Query("SELECT * FROM outbox_events WHERE state = 'REJECTED' ORDER BY occurredAt DESC")
    fun rejected(): Flow<List<OutboxEventEntity>>

    @Query("DELETE FROM outbox_events WHERE state = 'REJECTED'")
    suspend fun clearRejected()

    /** Sent events are only kept briefly, as a debugging aid. */
    @Query("DELETE FROM outbox_events WHERE state = 'SENT' AND createdAt < :beforeMillis")
    suspend fun purgeSent(beforeMillis: Long)
}
