package com.lfcreative.geartracker.sync

import androidx.room.withTransaction
import com.lfcreative.geartracker.data.local.AppDatabase
import com.lfcreative.geartracker.data.local.AssetEntity
import com.lfcreative.geartracker.data.local.LocationEntity
import com.lfcreative.geartracker.data.local.OpenCheckoutEntity
import com.lfcreative.geartracker.data.local.ProjectEntity
import com.lfcreative.geartracker.data.local.TagEntity
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import java.time.OffsetDateTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

enum class MirrorSource { TAGS, ASSETS, LOCATIONS, PROJECTS, OPEN_CHECKOUTS }

/** Reads one page (up to [PAGE_SIZE] rows, ordered by a stable key) of a server table. */
interface MirrorApi {
    suspend fun page(source: MirrorSource, from: Long): Result<JsonArray>
}

/** Replaces the local copy atomically: either everything is swapped or nothing is. */
interface MirrorStore {
    suspend fun replaceAll(
        tags: List<TagEntity>, assets: List<AssetEntity>, locations: List<LocationEntity>,
        projects: List<ProjectEntity>, openCheckouts: List<OpenCheckoutEntity>,
    )
}

const val PAGE_SIZE = 1000L

/** Pure mapping from server rows to Room entities. Tolerant of missing values; skips unusable rows. */
object MirrorJson {
    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    private fun JsonElement?.bool(): Boolean = (this as? JsonPrimitive)?.booleanOrNull ?: false
    private fun JsonElement?.millis(): Long? = str()?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }

    fun tags(rows: List<JsonObject>) = rows.mapNotNull { o ->
        if (o["retired_at"].str() != null) return@mapNotNull null
        val value = o["value"].str() ?: return@mapNotNull null
        val asset = o["asset_id"].str(); val location = o["location_id"].str()
        if ((asset == null) == (location == null)) return@mapNotNull null
        TagEntity(value, o["kind"].str() ?: "barcode", asset, location)
    }

    fun assets(rows: List<JsonObject>) = rows.mapNotNull { o ->
        AssetEntity(
            id = o["id"].str() ?: return@mapNotNull null, assetCode = o["asset_code"].str().orEmpty(), name = o["name"].str().orEmpty(),
            status = o["status"].str() ?: "available", isContainer = o["is_container"].bool(),
            parentLocationId = o["parent_location_id"].str(), parentAssetId = o["parent_asset_id"].str(),
            retired = o["retired_at"].str() != null, lastSeenAt = o["last_seen_at"].millis(),
        )
    }

    fun locations(rows: List<JsonObject>) = rows.mapNotNull { o ->
        LocationEntity(
            o["id"].str() ?: return@mapNotNull null, o["code"].str().orEmpty(), o["name"].str().orEmpty(), o["kind"].str() ?: "other",
            o["parent_id"].str(), o["path_cache"].str() ?: o["name"].str().orEmpty(), o["archived_at"].str() != null,
        )
    }

    fun projects(rows: List<JsonObject>) = rows.mapNotNull { o ->
        ProjectEntity(o["id"].str() ?: return@mapNotNull null, o["code"].str().orEmpty(), o["name"].str().orEmpty(), o["starts_on"].str(), o["ends_on"].str())
    }

    fun openCheckouts(rows: List<JsonObject>) = rows.mapNotNull { o ->
        OpenCheckoutEntity(
            o["asset_id"].str() ?: return@mapNotNull null, o["checkout_id"].str() ?: return@mapNotNull null,
            o["project_id"].str(), o["reason"].str(), o["due_at"].millis(), o["checked_out_at"].millis() ?: 0L,
        )
    }
}

/**
 * Pulls the server tables the device needs offline. All five are read completely BEFORE anything is written,
 * so a failure half-way leaves the old copy untouched.
 */
class MirrorSync(private val api: MirrorApi, private val store: MirrorStore) {
    suspend fun refresh(): Result<Unit> {
        val tags = readAll(MirrorSource.TAGS).getOrElse { return Result.failure(it) }
        val assets = readAll(MirrorSource.ASSETS).getOrElse { return Result.failure(it) }
        val locations = readAll(MirrorSource.LOCATIONS).getOrElse { return Result.failure(it) }
        val projects = readAll(MirrorSource.PROJECTS).getOrElse { return Result.failure(it) }
        val checkouts = readAll(MirrorSource.OPEN_CHECKOUTS).getOrElse { return Result.failure(it) }
        store.replaceAll(
            MirrorJson.tags(tags), MirrorJson.assets(assets), MirrorJson.locations(locations),
            MirrorJson.projects(projects), MirrorJson.openCheckouts(checkouts),
        )
        return Result.success(Unit)
    }

    private suspend fun readAll(source: MirrorSource): Result<List<JsonObject>> {
        val out = ArrayList<JsonObject>()
        var from = 0L
        while (true) {
            val page = api.page(source, from).getOrElse { return Result.failure(it) }
            page.forEach { (it as? JsonObject)?.let(out::add) }
            if (page.size < PAGE_SIZE) return Result.success(out)
            from += PAGE_SIZE
        }
    }
}

@Singleton
class SupabaseMirrorApi @Inject constructor(private val client: SupabaseClient) : MirrorApi {
    override suspend fun page(source: MirrorSource, from: Long): Result<JsonArray> = try {
        val to = from + PAGE_SIZE - 1
        val result = when (source) {
            MirrorSource.TAGS -> client.postgrest.from("tags").select(Columns.list("value", "kind", "asset_id", "location_id", "retired_at")) {
                order("value", Order.ASCENDING); range(from, to)
            }
            MirrorSource.ASSETS -> client.postgrest.from("assets").select(
                Columns.list("id", "asset_code", "name", "status", "is_container", "parent_location_id", "parent_asset_id", "retired_at", "last_seen_at"),
            ) { order("id", Order.ASCENDING); range(from, to) }
            MirrorSource.LOCATIONS -> client.postgrest.from("locations").select(
                Columns.list("id", "code", "name", "kind", "parent_id", "path_cache", "archived_at"),
            ) { order("id", Order.ASCENDING); range(from, to) }
            MirrorSource.PROJECTS -> client.postgrest.from("projects").select(Columns.list("id", "code", "name", "starts_on", "ends_on")) {
                order("id", Order.ASCENDING); range(from, to)
            }
            MirrorSource.OPEN_CHECKOUTS -> client.postgrest.rpc("get_open_checkouts", buildJsonObject {}) { range(from, to) }
        }
        Result.success(result.decodeAs<JsonArray>())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
}

@Singleton
class RoomMirrorStore @Inject constructor(private val db: AppDatabase) : MirrorStore {
    override suspend fun replaceAll(
        tags: List<TagEntity>, assets: List<AssetEntity>, locations: List<LocationEntity>,
        projects: List<ProjectEntity>, openCheckouts: List<OpenCheckoutEntity>,
    ) = db.withTransaction {
        db.tags().clear(); db.assets().clear(); db.locations().clear(); db.projects().clear(); db.openCheckouts().clear()
        db.tags().upsertAll(tags); db.assets().upsertAll(assets); db.locations().upsertAll(locations)
        db.projects().upsertAll(projects); db.openCheckouts().upsertAll(openCheckouts)
    }
}
