package com.lfcreative.geartracker.asset

import com.lfcreative.geartracker.session.ResolveTagJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** Pure parsers for the server's JSON (docs/architecture.md). Tolerant: missing fields become null/empty. */
object AssetJson {
    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
    private fun JsonElement?.arr(): JsonArray? = this as? JsonArray
    private fun JsonElement?.bool(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull

    fun categories(rows: JsonArray): List<CategoryInfo> = rows.mapNotNull { row ->
        val o = row.obj() ?: return@mapNotNull null
        val fields = o["field_schema"].obj()?.get("fields").arr()?.mapNotNull { f ->
            val fo = f.obj() ?: return@mapNotNull null
            val key = fo["key"].str() ?: return@mapNotNull null
            FieldDef(key, fo["label"].str() ?: key, fo["type"].str() ?: "text", fo["required"].bool() ?: false)
        }.orEmpty()
        CategoryInfo(o["id"].str() ?: return@mapNotNull null, o["name"].str().orEmpty(), o["default_is_container"].bool() ?: false, fields)
    }

    fun templates(rows: JsonArray): List<TemplateInfo> = rows.mapNotNull { row ->
        val o = row.obj() ?: return@mapNotNull null
        TemplateInfo(o["id"].str() ?: return@mapNotNull null, o["name"].str().orEmpty(), o["category_id"].str(), o["defaults"].obj() ?: JsonObject(emptyMap()))
    }

    fun summaries(rows: JsonArray): List<AssetSummary> = rows.mapNotNull { row ->
        val o = row.obj() ?: return@mapNotNull null
        AssetSummary(
            o["id"].str() ?: return@mapNotNull null, o["asset_code"].str().orEmpty(), o["name"].str().orEmpty(),
            o["status"].str() ?: "available", o["is_container"].bool() ?: false, o["serial_number"].str(),
        )
    }

    fun createResult(scanned: String?, json: JsonObject): CreateResult {
        val status = json["status"].str()
        val asset = json["asset"].obj()
        val id = asset?.get("id").str()
        return when (status) {
            "created" -> CreateResult.Created(id ?: return CreateResult.Rejected("bad response"), json["asset_code"].str().orEmpty(), asset?.get("name").str().orEmpty())
            "duplicate" -> CreateResult.Duplicate(id ?: return CreateResult.Rejected("bad response"), asset?.get("asset_code").str().orEmpty(), asset?.get("name").str().orEmpty())
            "rejected" -> when (val reason = json["reason"].str() ?: "rejected") {
                "tag_in_use" -> {
                    val existing = json["existing"].obj()
                    if (existing != null) CreateResult.TagInUse(ResolveTagJson.parse(scanned.orEmpty(), existing)) else CreateResult.Rejected(reason)
                }
                else -> CreateResult.Rejected(reason)
            }
            else -> CreateResult.Rejected("unexpected response")
        }
    }

    fun detail(json: JsonObject): AssetDetail? {
        if (json["found"].bool() != true) return null
        val a = json["asset"].obj() ?: return null
        val seen = json["last_seen"].obj()
        val checkout = json["open_checkout"].obj()
        return AssetDetail(
            id = a["id"].str() ?: return null,
            code = a["code"].str().orEmpty(),
            name = a["name"].str().orEmpty(),
            status = a["status"].str() ?: "available",
            isContainer = a["is_container"].bool() ?: false,
            category = a["category"].obj()?.get("name").str(),
            manufacturer = a["manufacturer"].str(),
            model = a["model"].str(),
            serialNumber = a["serial_number"].str(),
            notes = a["notes"].str(),
            retired = a["retired_at"].str() != null,
            locationPath = json["location"].obj()?.get("path").str(),
            containerChain = json["container_chain"].arr()?.mapNotNull { it.obj()?.get("name").str() }.orEmpty(),
            lastSeenAt = seen?.get("at").str(),
            lastSeenBy = seen?.get("by").str(),
            openCheckout = checkout?.let { c ->
                val who = c["project_name"].str() ?: c["reason"].str() ?: "checked out"
                val due = c["due_at"].str()?.let { " (due ${it.take(10)})" }.orEmpty()
                who + due
            },
            tags = json["tags"].arr()?.mapNotNull { t -> t.obj()?.takeIf { it["retired"].bool() != true }?.get("value").str() }.orEmpty(),
            photoCount = json["photos"].arr()?.size ?: 0,
            timeline = json["events"].arr()?.mapNotNull { e ->
                val o = e.obj() ?: return@mapNotNull null
                TimelineEntry(
                    o["id"].str() ?: return@mapNotNull null, o["type"].str().orEmpty(), o["occurred_at"].str().orEmpty(), o["user_name"].str(),
                    o["to_location"].obj()?.get("path").str(), o["to_asset"].obj()?.get("name").str(),
                )
            }.orEmpty(),
        )
    }

    @Suppress("unused") private fun JsonElement?.int(): Int? = (this as? JsonPrimitive)?.intOrNull
}
