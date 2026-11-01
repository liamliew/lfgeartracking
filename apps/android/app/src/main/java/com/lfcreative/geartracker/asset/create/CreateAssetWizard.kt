package com.lfcreative.geartracker.asset.create

import com.lfcreative.geartracker.asset.CategoryInfo
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The create-asset wizard as pure logic (steps, validation, "Same as last", "Create & next", fast path, request).
 * Photos are not part of the wizard yet: they need the Storage bucket and a camera flow (follow-up task).
 */
enum class Step { TAG, TEMPLATE, IDENTITY, FIELDS, LOCATION, CONFIRM }

enum class TagMode { BARCODE, NFC, GENERATE }

data class TagInput(val value: String, val mode: TagMode) {
    companion object { val Generate = TagInput("", TagMode.GENERATE) }
}

@Serializable
data class Remembered(
    val categoryId: String? = null,
    val templateId: String? = null,
    val namePattern: String = "",
    val nextNumber: Int = 1,
    val manufacturer: String = "",
    val model: String = "",
    val isContainer: Boolean? = null,
    val custom: Map<String, String> = emptyMap(),
    val locationId: String? = null,
    val locationLabel: String? = null,
    val recentTemplateIds: List<String> = emptyList(),
)

data class Draft(
    val tag: TagInput? = null,
    val categoryId: String? = null,
    val templateId: String? = null,
    /** May contain `{n}`: "SM58 #{n}" -> "SM58 #1", "SM58 #2", ... */
    val namePattern: String = "",
    val number: Int = 1,
    val serial: String = "",
    val manufacturer: String = "",
    val model: String = "",
    val isContainer: Boolean? = null,
    val custom: Map<String, String> = emptyMap(),
    val locationId: String? = null,
    val locationLabel: String? = null,
) {
    val name: String get() = NamePattern.render(namePattern, number)
}

object NamePattern {
    const val PLACEHOLDER = "{n}"
    fun render(pattern: String, n: Int): String = pattern.replace(PLACEHOLDER, n.toString()).trim()
    fun isPattern(text: String) = text.contains(PLACEHOLDER)
}

object WizardLogic {
    /** Steps that apply to this draft. FIELDS only appears when the category has required custom fields. */
    fun steps(category: CategoryInfo?, sameAsLast: Boolean): List<Step> =
        if (sameAsLast) listOf(Step.TAG, Step.IDENTITY, Step.CONFIRM) // fast path: tag, serial, create
        else buildList {
            add(Step.TAG); add(Step.TEMPLATE); add(Step.IDENTITY)
            if (category?.fields?.any { it.required } == true) add(Step.FIELDS)
            add(Step.LOCATION); add(Step.CONFIRM)
        }

    fun next(current: Step, category: CategoryInfo?, sameAsLast: Boolean): Step {
        val list = steps(category, sameAsLast)
        return list.getOrNull(list.indexOf(current) + 1) ?: Step.CONFIRM
    }

    fun previous(current: Step, category: CategoryInfo?, sameAsLast: Boolean): Step {
        val list = steps(category, sameAsLast)
        return list.getOrNull(list.indexOf(current) - 1) ?: Step.TAG
    }

    /** Null when the step is complete, otherwise the reason it is not. */
    fun validate(step: Step, draft: Draft, category: CategoryInfo?): String? = when (step) {
        Step.TAG -> if (draft.tag == null) "Scan a barcode, tap an NFC tag, or choose Generate code" else null
        Step.TEMPLATE -> null
        Step.IDENTITY -> if (draft.name.isBlank()) "Enter a name" else null
        Step.FIELDS -> category?.fields.orEmpty()
            .firstOrNull { it.required && draft.custom[it.key].isNullOrBlank() }?.let { "${it.label} is required" }
        Step.LOCATION -> if (draft.locationId == null) "Scan the location where it lives" else null
        Step.CONFIRM -> steps(category, false).filter { it != Step.CONFIRM }.firstNotNullOfOrNull { validate(it, draft, category) }
    }

    /** "Same as last": everything from the previous asset except its tag and serial; the name counter moves on. */
    fun sameAsLast(r: Remembered) = Draft(
        categoryId = r.categoryId, templateId = r.templateId, namePattern = r.namePattern, number = r.nextNumber,
        manufacturer = r.manufacturer, model = r.model, isContainer = r.isContainer, custom = r.custom,
        locationId = r.locationId, locationLabel = r.locationLabel,
    )

    /** "Create & next": keep everything except the tag and serial, advance the counter, and loop to the tag step. */
    fun afterCreate(d: Draft) = d.copy(tag = null, serial = "", number = d.number + 1)

    fun remember(d: Draft, previous: Remembered?): Remembered = Remembered(
        categoryId = d.categoryId, templateId = d.templateId, namePattern = d.namePattern, nextNumber = d.number + 1,
        manufacturer = d.manufacturer, model = d.model, isContainer = d.isContainer, custom = d.custom,
        locationId = d.locationId, locationLabel = d.locationLabel,
        recentTemplateIds = (listOfNotNull(d.templateId) + previous?.recentTemplateIds.orEmpty()).distinct().take(5),
    )

    /** The create_asset RPC argument (docs/architecture.md). [id] is client generated so a retry cannot duplicate. */
    fun request(d: Draft, id: String, nowIso: String, deviceId: String? = null): JsonObject = buildJsonObject {
        put("id", JsonPrimitive(id))
        put("name", JsonPrimitive(d.name))
        d.categoryId?.let { put("category_id", JsonPrimitive(it)) }
        d.manufacturer.trim().takeIf { it.isNotEmpty() }?.let { put("manufacturer", JsonPrimitive(it)) }
        d.model.trim().takeIf { it.isNotEmpty() }?.let { put("model", JsonPrimitive(it)) }
        d.serial.trim().takeIf { it.isNotEmpty() }?.let { put("serial_number", JsonPrimitive(it)) }
        d.isContainer?.let { put("is_container", JsonPrimitive(it)) }
        d.tag?.takeIf { it.mode != TagMode.GENERATE }?.let { t ->
            put("tag", buildJsonObject {
                put("value", JsonPrimitive(t.value))
                put("kind", JsonPrimitive(if (t.mode == TagMode.NFC) "nfc" else "barcode"))
            })
        }
        d.locationId?.let { put("location_id", JsonPrimitive(it)) }
        val custom = d.custom.filterValues { it.isNotBlank() }
        if (custom.isNotEmpty()) put("custom", buildJsonObject { custom.forEach { (k, v) -> put(k, JsonPrimitive(v)) } })
        put("occurred_at", JsonPrimitive(nowIso))
        deviceId?.let { put("device_id", JsonPrimitive(it)) }
    }
}
