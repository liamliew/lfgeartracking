package com.lfcreative.geartracker.session

import com.lfcreative.geartracker.data.local.AssetDao
import com.lfcreative.geartracker.data.local.LocationDao
import com.lfcreative.geartracker.data.local.TagDao
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** A place that may know what a scanned value is. Returns null when it cannot answer (not cached / offline). */
interface TagSource {
    suspend fun resolve(value: String): ResolvedTag?
}

interface TagResolving {
    suspend fun resolve(value: String): ResolvedTag
}

/** Room first (works offline), then the server's resolve_tag RPC, otherwise Unknown. */
@Singleton
class TagResolver @Inject constructor(
    private val local: RoomTagSource,
    private val remote: RemoteTagSource,
) : TagResolving {
    override suspend fun resolve(value: String): ResolvedTag = resolveFirst(value, listOf(local, remote))
}

/** First source that knows the value wins; nobody knows it -> Unknown. */
suspend fun resolveFirst(value: String, sources: List<TagSource>): ResolvedTag {
    for (source in sources) source.resolve(value)?.let { return it }
    return ResolvedTag.Unknown(value)
}

@Singleton
class RoomTagSource @Inject constructor(
    private val tags: TagDao,
    private val assets: AssetDao,
    private val locations: LocationDao,
) : TagSource {
    override suspend fun resolve(value: String): ResolvedTag? {
        val tag = tags.find(value) ?: return null
        tag.locationId?.let { id ->
            val l = locations.find(id) ?: return null
            return ResolvedTag.Location(value, l.id, l.code, l.name, locationAncestors(l.parentId), l.path, l.archived)
        }
        val a = tag.assetId?.let { assets.find(it) } ?: return null
        val placement = Placement(locationId = a.parentLocationId, containerId = a.parentAssetId)
        return if (a.isContainer) {
            ResolvedTag.Container(value, a.id, a.assetCode, a.name, containerAncestors(a.parentAssetId), placement, a.retired)
        } else {
            ResolvedTag.Item(value, a.id, a.assetCode, a.name, placement, a.retired)
        }
    }

    private suspend fun containerAncestors(start: String?): Set<String> {
        val out = LinkedHashSet<String>()
        var next = start
        while (next != null && out.size < MAX_DEPTH && out.add(next)) next = assets.find(next)?.parentAssetId
        return out
    }

    private suspend fun locationAncestors(start: String?): Set<String> {
        val out = LinkedHashSet<String>()
        var next = start
        while (next != null && out.size < MAX_DEPTH && out.add(next)) next = locations.find(next)?.parentId
        return out
    }

    private companion object { const val MAX_DEPTH = 12 }
}

@Singleton
class RemoteTagSource @Inject constructor(private val client: SupabaseClient) : TagSource {
    override suspend fun resolve(value: String): ResolvedTag? = try {
        val result = client.postgrest.rpc("resolve_tag", buildJsonObject { put("p_value", value) })
        ResolveTagJson.parse(value, result.decodeAs<JsonObject>())
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null // offline or server error: the caller falls back to Unknown
    }
}

/** Parses the resolve_tag RPC result (see docs/architecture.md). Pure, so it is unit tested. */
object ResolveTagJson {
    fun parse(scanned: String, json: JsonObject): ResolvedTag {
        fun str(key: String) = (json[key] as? JsonPrimitive)?.contentOrNull
        val ancestors = (json["ancestor_ids"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet().orEmpty()
        val placementObj = json["placement"] as? JsonObject
        val placement = Placement(
            locationId = (placementObj?.get("location_id") as? JsonPrimitive)?.contentOrNull,
            containerId = (placementObj?.get("container_id") as? JsonPrimitive)?.contentOrNull,
        )
        val id = str("id")
        return when (str("kind")) {
            "location" -> ResolvedTag.Location(
                scanned, id ?: return ResolvedTag.Unknown(scanned), str("code").orEmpty(), str("name").orEmpty(),
                ancestors, str("path") ?: str("name").orEmpty(), json["archived"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
            "container" -> ResolvedTag.Container(
                scanned, id ?: return ResolvedTag.Unknown(scanned), str("code").orEmpty(), str("name").orEmpty(),
                ancestors, placement, json["retired"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
            "asset" -> ResolvedTag.Item(
                scanned, id ?: return ResolvedTag.Unknown(scanned), str("code").orEmpty(), str("name").orEmpty(),
                placement, json["retired"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
            else -> ResolvedTag.Unknown(scanned)
        }
    }
}
