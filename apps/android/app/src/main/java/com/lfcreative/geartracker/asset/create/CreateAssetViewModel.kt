package com.lfcreative.geartracker.asset.create

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lfcreative.geartracker.asset.AssetApi
import com.lfcreative.geartracker.asset.CategoryInfo
import com.lfcreative.geartracker.asset.CreateResult
import com.lfcreative.geartracker.asset.TemplateInfo
import com.lfcreative.geartracker.core.ScanInput
import com.lfcreative.geartracker.core.ScanSource
import com.lfcreative.geartracker.core.scan.ScanInputBus
import com.lfcreative.geartracker.session.ResolvedTag
import com.lfcreative.geartracker.session.TagResolving
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

data class CreateUiState(
    val loading: Boolean = true,
    val categories: List<CategoryInfo> = emptyList(),
    val templates: List<TemplateInfo> = emptyList(),
    val recentTemplates: List<TemplateInfo> = emptyList(),
    val draft: Draft = Draft(),
    val step: Step = Step.TAG,
    val sameAsLast: Boolean = false,
    val canSameAsLast: Boolean = false,
    /** The next scan fills the serial number instead of being ignored. */
    val scanningSerial: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    /** The scanned tag already belongs to this asset/container: show it instead of creating. */
    val existing: ResolvedTag? = null,
    val lastCreated: String? = null,
    val createdCount: Int = 0,
    /** Set when the wizard is finished (single create): the screen opens this asset. */
    val finishedAssetId: String? = null,
) {
    val category: CategoryInfo? get() = categories.firstOrNull { it.id == draft.categoryId }
}

@HiltViewModel
class CreateAssetViewModel @Inject constructor(
    private val bus: ScanInputBus,
    private val resolver: TagResolving,
    private val api: AssetApi,
    private val memory: WizardMemory,
    private val clock: Clock,
) : ViewModel() {

    private val _ui = MutableStateFlow(CreateUiState(canSameAsLast = memory.load() != null))
    val ui: StateFlow<CreateUiState> = _ui.asStateFlow()
    private var remembered: Remembered? = memory.load()

    /** Stable for one draft, so a retry after a lost response cannot create a second asset. */
    private var pendingId: String = UUID.randomUUID().toString()

    init {
        viewModelScope.launch {
            val cats = api.categories().getOrDefault(emptyList())
            val temps = api.templates().getOrDefault(emptyList())
            _ui.update { it.copy(loading = false, categories = cats, templates = temps, recentTemplates = recent(temps)) }
        }
        viewModelScope.launch { bus.scans.collect { onScan(it) } }
        remembered?.let { r ->
            // Default location for a brand new draft is the last one used.
            _ui.update { it.copy(draft = it.draft.copy(locationId = r.locationId, locationLabel = r.locationLabel)) }
        }
    }

    fun submitScan(input: ScanInput) {
        bus.submit(input)
    }

    // ---- scans ----------------------------------------------------------------------------------------------

    private suspend fun onScan(input: ScanInput) {
        val s = _ui.value
        when (s.step) {
            Step.TAG -> {
                when (val tag = resolver.resolve(input.value)) {
                    is ResolvedTag.Item, is ResolvedTag.Container -> _ui.update { it.copy(existing = tag, error = null) }
                    is ResolvedTag.Location -> _ui.update { it.copy(error = "That is a location label, not gear") }
                    is ResolvedTag.Unknown -> setTag(TagInput(input.value, if (input.source == ScanSource.NFC) TagMode.NFC else TagMode.BARCODE))
                }
            }
            Step.IDENTITY -> if (s.scanningSerial || s.sameAsLast) {
                _ui.update { it.copy(draft = it.draft.copy(serial = input.value), scanningSerial = false, error = null) }
                if (s.sameAsLast) goNext()
            }
            Step.LOCATION -> when (val tag = resolver.resolve(input.value)) {
                is ResolvedTag.Location -> _ui.update {
                    it.copy(draft = it.draft.copy(locationId = tag.id, locationLabel = tag.path), error = null)
                }
                else -> _ui.update { it.copy(error = "Scan a location label") }
            }
            // The third trigger pull of the fast path: scanning the same tag again creates the asset.
            Step.CONFIRM -> if (s.sameAsLast && s.draft.tag?.value == input.value) create(andNext = true)
            else -> Unit
        }
    }

    private fun setTag(tag: TagInput) {
        _ui.update { it.copy(draft = it.draft.copy(tag = tag), existing = null, error = null) }
        goNext()
    }

    // ---- edits ----------------------------------------------------------------------------------------------

    fun generateCode() = setTag(TagInput.Generate)
    fun dismissExisting() = _ui.update { it.copy(existing = null) }
    fun scanSerial() = _ui.update { it.copy(scanningSerial = true) }

    fun chooseCategory(id: String?) = _ui.update {
        val cat = it.categories.firstOrNull { c -> c.id == id }
        it.copy(draft = it.draft.copy(categoryId = id, isContainer = it.draft.isContainer ?: cat?.defaultIsContainer), error = null)
    }

    /** Applies a template's defaults (category, name pattern, maker, model, container flag, custom values). */
    fun chooseTemplate(id: String) = _ui.update { s ->
        val t = s.templates.firstOrNull { it.id == id } ?: return@update s
        val d = t.defaults
        fun str(k: String) = (d[k] as? JsonPrimitive)?.contentOrNull
        val custom = (d["custom"] as? kotlinx.serialization.json.JsonObject)?.mapValues { (_, v) -> (v as? JsonPrimitive)?.contentOrNull.orEmpty() }.orEmpty()
        s.copy(
            draft = s.draft.copy(
                templateId = t.id, categoryId = t.categoryId ?: s.draft.categoryId,
                namePattern = str("name_pattern") ?: s.draft.namePattern,
                manufacturer = str("manufacturer") ?: s.draft.manufacturer, model = str("model") ?: s.draft.model,
                isContainer = (d["is_container"] as? JsonPrimitive)?.booleanOrNull ?: s.draft.isContainer,
                custom = s.draft.custom + custom,
            ),
            error = null,
        )
    }

    fun edit(transform: (Draft) -> Draft) = _ui.update { it.copy(draft = transform(it.draft), error = null) }

    fun useSameAsLast() {
        val r = remembered ?: return
        _ui.update { it.copy(draft = WizardLogic.sameAsLast(r), sameAsLast = true, step = Step.TAG, error = null, existing = null) }
    }

    // ---- navigation -----------------------------------------------------------------------------------------

    fun goNext() {
        val s = _ui.value
        val problem = WizardLogic.validate(s.step, s.draft, s.category)
        if (problem != null) {
            _ui.update { it.copy(error = problem) }
            return
        }
        _ui.update { it.copy(step = WizardLogic.next(it.step, it.category, it.sameAsLast), error = null) }
    }

    fun goBack() = _ui.update { it.copy(step = WizardLogic.previous(it.step, it.category, it.sameAsLast), error = null) }

    // ---- create ---------------------------------------------------------------------------------------------

    fun create(andNext: Boolean) {
        val s = _ui.value
        if (s.saving) return
        val problem = WizardLogic.validate(Step.CONFIRM, s.draft, s.category)
        if (problem != null) {
            _ui.update { it.copy(error = problem) }
            return
        }
        _ui.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            val request = WizardLogic.request(s.draft, pendingId, clock.instant().toString())
            when (val result = api.createAsset(request, s.draft.tag?.value)) {
                is CreateResult.Created -> onCreated(s, result.assetId, "${result.code} ${result.name}", andNext)
                is CreateResult.Duplicate -> onCreated(s, result.assetId, "${result.code} ${result.name}", andNext)
                is CreateResult.TagInUse -> _ui.update { it.copy(saving = false, existing = result.existing) }
                is CreateResult.Rejected -> _ui.update { it.copy(saving = false, error = friendly(result.reason)) }
                is CreateResult.Offline -> _ui.update { it.copy(saving = false, error = result.message) }
            }
        }
    }

    private fun onCreated(before: CreateUiState, assetId: String, label: String, andNext: Boolean) {
        remembered = WizardLogic.remember(before.draft, remembered).also(memory::save)
        pendingId = UUID.randomUUID().toString()
        _ui.update {
            if (andNext) {
                it.copy(
                    saving = false, draft = WizardLogic.afterCreate(before.draft), step = Step.TAG, lastCreated = label,
                    createdCount = it.createdCount + 1, canSameAsLast = true, recentTemplates = recent(it.templates),
                )
            } else {
                it.copy(saving = false, lastCreated = label, createdCount = it.createdCount + 1, canSameAsLast = true, finishedAssetId = assetId)
            }
        }
    }

    private fun recent(templates: List<TemplateInfo>): List<TemplateInfo> =
        remembered?.recentTemplateIds.orEmpty().mapNotNull { id -> templates.firstOrNull { it.id == id } }

    private fun friendly(reason: String) = when (reason) {
        "location_not_found" -> "That location no longer exists. Scan it again."
        "asset_code_in_use" -> "That code is already used by another asset."
        else -> "The server refused it: $reason"
    }
}
