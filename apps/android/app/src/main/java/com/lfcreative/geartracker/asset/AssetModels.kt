package com.lfcreative.geartracker.asset

import com.lfcreative.geartracker.session.ResolvedTag
import kotlinx.serialization.json.JsonObject

data class FieldDef(val key: String, val label: String, val type: String = "text", val required: Boolean = false)

data class CategoryInfo(
    val id: String,
    val name: String,
    val defaultIsContainer: Boolean = false,
    val fields: List<FieldDef> = emptyList(),
)

data class TemplateInfo(val id: String, val name: String, val categoryId: String?, val defaults: JsonObject = JsonObject(emptyMap()))

data class AssetSummary(
    val id: String,
    val code: String,
    val name: String,
    val status: String,
    val isContainer: Boolean,
    val serialNumber: String?,
)

data class PlaceRef(val id: String, val label: String)

data class TimelineEntry(
    val id: String,
    val type: String,
    val occurredAt: String,
    val userName: String?,
    val toLocation: String?,
    val toAsset: String?,
)

data class AssetDetail(
    val id: String,
    val code: String,
    val name: String,
    val status: String,
    val isContainer: Boolean,
    val category: String?,
    val manufacturer: String?,
    val model: String?,
    val serialNumber: String?,
    val notes: String?,
    val retired: Boolean,
    val locationPath: String?,
    /** Containers above the asset, outermost first. */
    val containerChain: List<String>,
    val lastSeenAt: String?,
    val lastSeenBy: String?,
    val openCheckout: String?,
    val tags: List<String>,
    val photoCount: Int,
    val timeline: List<TimelineEntry>,
) {
    /** "Savanna > Storage 1 > Bin 1A > Case A > Pouch": where it is, including the container chain. */
    val breadcrumb: String?
        get() = (listOfNotNull(locationPath) + containerChain).joinToString(" > ").ifBlank { null }
}

sealed interface CreateResult {
    data class Created(val assetId: String, val code: String, val name: String) : CreateResult
    data class Duplicate(val assetId: String, val code: String, val name: String) : CreateResult
    /** The scanned tag already belongs to something: show that instead of creating. */
    data class TagInUse(val existing: ResolvedTag) : CreateResult
    data class Rejected(val reason: String) : CreateResult
    /** Could not reach the server; nothing was created. */
    data class Offline(val message: String) : CreateResult
}
