package com.lfcreative.geartracker.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// Room mirror of the server tables the device needs offline (see docs/architecture.md).

@Entity(tableName = "tags", indices = [Index("assetId"), Index("locationId")])
data class TagEntity(
    @PrimaryKey val value: String,
    val kind: String,
    val assetId: String?,
    val locationId: String?,
)

@Entity(tableName = "assets", indices = [Index("assetCode", unique = true), Index("parentAssetId"), Index("parentLocationId")])
data class AssetEntity(
    @PrimaryKey val id: String,
    val assetCode: String,
    val name: String,
    val status: String,
    val isContainer: Boolean,
    val parentLocationId: String?,
    val parentAssetId: String?,
    val retired: Boolean,
    val lastSeenAt: Long?,
)

@Entity(tableName = "locations", indices = [Index("parentId")])
data class LocationEntity(
    @PrimaryKey val id: String,
    val code: String,
    val name: String,
    val kind: String,
    val parentId: String?,
    val path: String,
    val archived: Boolean,
)

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val code: String,
    val name: String,
    val startsOn: String?,
    val endsOn: String?,
)

/** One open checkout line (an asset that is currently out). */
@Entity(tableName = "open_checkouts", indices = [Index("checkoutId")])
data class OpenCheckoutEntity(
    @PrimaryKey val assetId: String,
    val checkoutId: String,
    val projectId: String?,
    val reason: String?,
    val dueAt: Long?,
    val checkedOutAt: Long,
)

enum class OutboxState { PENDING, SENT, REJECTED }

/** Idempotent client events waiting to be sent through apply_scan_events. */
@Entity(tableName = "outbox_events", indices = [Index("state", "occurredAt")])
data class OutboxEventEntity(
    @PrimaryKey val id: String,
    val type: String,
    /** The full event JSON exactly as sent to apply_scan_events. */
    val payloadJson: String,
    val occurredAt: Long,
    val createdAt: Long,
    val state: OutboxState = OutboxState.PENDING,
    val attempts: Int = 0,
    val lastError: String? = null,
)
