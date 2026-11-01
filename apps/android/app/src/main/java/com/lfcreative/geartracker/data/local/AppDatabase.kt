package com.lfcreative.geartracker.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        TagEntity::class, AssetEntity::class, LocationEntity::class, ProjectEntity::class,
        OpenCheckoutEntity::class, OutboxEventEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tags(): TagDao
    abstract fun assets(): AssetDao
    abstract fun locations(): LocationDao
    abstract fun projects(): ProjectDao
    abstract fun openCheckouts(): OpenCheckoutDao
    abstract fun outbox(): OutboxDao
}
