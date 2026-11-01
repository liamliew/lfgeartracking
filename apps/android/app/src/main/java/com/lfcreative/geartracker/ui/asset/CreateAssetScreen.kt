package com.lfcreative.geartracker.ui.asset

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lfcreative.geartracker.asset.create.CreateAssetViewModel
import com.lfcreative.geartracker.asset.create.CreateUiState
import com.lfcreative.geartracker.asset.create.NamePattern
import com.lfcreative.geartracker.asset.create.Step
import com.lfcreative.geartracker.session.ResolvedTag
import com.lfcreative.geartracker.ui.scan.ScanInputEffect
import com.lfcreative.geartracker.ui.theme.MinTouch
import com.lfcreative.geartracker.ui.theme.primaryAction

private val StepTitle = mapOf(
    Step.TAG to "1. Tag", Step.TEMPLATE to "2. Type", Step.IDENTITY to "3. Details",
    Step.FIELDS to "4. Required info", Step.LOCATION to "5. Where it lives", Step.CONFIRM to "Confirm",
)

@Composable
fun CreateAssetScreen(onBack: () -> Unit, onOpenAsset: (String) -> Unit, viewModel: CreateAssetViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    ScanInputEffect(viewModel::submitScan)
    LaunchedEffect(ui.finishedAssetId) { ui.finishedAssetId?.let(onOpenAsset) }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
            IconButton(onClick = onBack, modifier = Modifier.heightIn(min = MinTouch)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Column {
                Text("New asset", style = MaterialTheme.typography.headlineSmall)
                Text(StepTitle[ui.step].orEmpty() + if (ui.sameAsLast) "  ·  same as last" else "", style = MaterialTheme.typography.bodyMedium)
            }
        }
        ui.lastCreated?.let { Text("Created $it (${ui.createdCount} this session)", modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.secondary, fontSize = 16.sp) }

        if (ui.loading) CircularProgressIndicator(Modifier.padding(24.dp))

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ExistingCard(ui, viewModel, onOpenAsset)
            when (ui.step) {
                Step.TAG -> TagStep(ui, viewModel)
                Step.TEMPLATE -> TemplateStep(ui, viewModel)
                Step.IDENTITY -> IdentityStep(ui, viewModel)
                Step.FIELDS -> FieldsStep(ui, viewModel)
                Step.LOCATION -> LocationStep(ui)
                Step.CONFIRM -> ConfirmStep(ui, viewModel)
            }
            ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 18.sp) }
        }

        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (ui.step != Step.TAG) OutlinedButton(onClick = viewModel::goBack, modifier = Modifier.weight(1f).primaryAction()) { Text("Back", fontSize = 18.sp) }
            if (ui.step != Step.CONFIRM) Button(onClick = viewModel::goNext, modifier = Modifier.weight(1f).primaryAction()) { Text("Next", fontSize = 18.sp) }
        }
    }
}

@Composable
private fun ExistingCard(ui: CreateUiState, vm: CreateAssetViewModel, onOpenAsset: (String) -> Unit) {
    val existing = ui.existing ?: return
    val (id, label) = when (existing) {
        is ResolvedTag.Item -> existing.id to "${existing.name} (${existing.code})"
        is ResolvedTag.Container -> existing.id to "${existing.name} (${existing.code})"
        else -> return
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("This tag already belongs to $label", color = MaterialTheme.colorScheme.error, fontSize = 20.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onOpenAsset(id) }, modifier = Modifier.primaryAction()) { Text("Open it") }
            OutlinedButton(onClick = vm::dismissExisting, modifier = Modifier.primaryAction()) { Text("Scan another") }
        }
    }
}

@Composable
private fun TagStep(ui: CreateUiState, vm: CreateAssetViewModel) {
    Text("Scan a barcode or tap an NFC tag", fontSize = 24.sp)
    ui.draft.tag?.takeIf { it.value.isNotEmpty() }?.let { Text("Tag: ${it.value}", fontSize = 20.sp) }
    if (ui.canSameAsLast) {
        Button(onClick = vm::useSameAsLast, modifier = Modifier.fillMaxWidth().primaryAction()) { Text("Same as last", fontSize = 20.sp) }
        Text("Fast path: scan the tag, scan the serial, then scan the tag again to create.", style = MaterialTheme.typography.bodySmall)
    }
    OutlinedButton(onClick = vm::generateCode, modifier = Modifier.fillMaxWidth().primaryAction()) { Text("Generate code, print label later") }
}

@Composable
private fun TemplateStep(ui: CreateUiState, vm: CreateAssetViewModel) {
    if (ui.canSameAsLast) Button(onClick = vm::useSameAsLast, modifier = Modifier.fillMaxWidth().primaryAction()) { Text("Same as last", fontSize = 20.sp) }
    if (ui.recentTemplates.isNotEmpty()) {
        Text("Recent", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ui.recentTemplates.forEach { t ->
                FilterChip(selected = ui.draft.templateId == t.id, onClick = { vm.chooseTemplate(t.id) }, label = { Text(t.name, fontSize = 18.sp) }, modifier = Modifier.heightIn(min = 56.dp))
            }
        }
    }
    if (ui.templates.isNotEmpty()) {
        Text("Templates", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ui.templates.forEach { t ->
                FilterChip(selected = ui.draft.templateId == t.id, onClick = { vm.chooseTemplate(t.id) }, label = { Text(t.name, fontSize = 18.sp) }, modifier = Modifier.heightIn(min = 56.dp))
            }
        }
    }
    Text("Category", style = MaterialTheme.typography.titleMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ui.categories.forEach { c ->
            FilterChip(selected = ui.draft.categoryId == c.id, onClick = { vm.chooseCategory(c.id) }, label = { Text(c.name, fontSize = 18.sp) }, modifier = Modifier.heightIn(min = 56.dp))
        }
    }
}

@Composable
private fun IdentityStep(ui: CreateUiState, vm: CreateAssetViewModel) {
    val d = ui.draft
    if (ui.sameAsLast) {
        Text(d.name.ifBlank { "Unnamed" }, fontSize = 24.sp)
        Text("Scan the serial number (or tap Next to skip)", fontSize = 20.sp)
    } else {
        OutlinedTextField(
            d.namePattern, { v -> vm.edit { it.copy(namePattern = v) } }, label = { Text("Name") },
            supportingText = { Text(if (NamePattern.isPattern(d.namePattern)) "Will be: ${d.name}" else "Use ${NamePattern.PLACEHOLDER} for a counter, e.g. SM58 #${NamePattern.PLACEHOLDER}") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(d.manufacturer, { v -> vm.edit { it.copy(manufacturer = v) } }, label = { Text("Manufacturer") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(d.model, { v -> vm.edit { it.copy(model = v) } }, label = { Text("Model") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Switch(d.isContainer == true, { v -> vm.edit { it.copy(isContainer = v) } }); Text("Is a container (case, bag, pouch)", fontSize = 18.sp)
        }
    }
    OutlinedTextField(d.serial, { v -> vm.edit { it.copy(serial = v) } }, label = { Text("Serial number") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    if (!ui.sameAsLast) {
        OutlinedButton(onClick = vm::scanSerial, modifier = Modifier.fillMaxWidth().primaryAction()) { Text(if (ui.scanningSerial) "Scan the serial now..." else "Scan serial") }
    }
}

@Composable
private fun FieldsStep(ui: CreateUiState, vm: CreateAssetViewModel) {
    ui.category?.fields.orEmpty().forEach { f ->
        OutlinedTextField(
            ui.draft.custom[f.key].orEmpty(), { v -> vm.edit { it.copy(custom = it.custom + (f.key to v)) } },
            label = { Text(f.label + if (f.required) " *" else "") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun LocationStep(ui: CreateUiState) {
    Text("Scan the location label where it lives", fontSize = 24.sp)
    Text(ui.draft.locationLabel?.let { "Location: $it" } ?: "No location chosen yet", fontSize = 20.sp)
    if (ui.draft.locationId != null) Text("Scan a different label to change it. Tap Next to keep it.", style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun ConfirmStep(ui: CreateUiState, vm: CreateAssetViewModel) {
    val d = ui.draft
    Text(d.name, fontSize = 26.sp)
    Text("Tag: ${d.tag?.value?.ifEmpty { null } ?: "generated code"}", fontSize = 18.sp)
    if (d.serial.isNotBlank()) Text("Serial: ${d.serial}", fontSize = 18.sp)
    listOfNotNull(ui.category?.name, d.manufacturer.ifBlank { null }, d.model.ifBlank { null }).takeIf { it.isNotEmpty() }?.let { Text(it.joinToString("  ·  "), fontSize = 18.sp) }
    Text("Location: ${d.locationLabel ?: "none"}", fontSize = 18.sp)
    if (ui.sameAsLast) Text("Scan the same tag again, or tap Create.", style = MaterialTheme.typography.bodyMedium)
    Button(onClick = { vm.create(andNext = false) }, enabled = !ui.saving, modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp)) {
        if (ui.saving) CircularProgressIndicator(Modifier.padding(4.dp)) else Text("Create", fontSize = 22.sp)
    }
    OutlinedButton(onClick = { vm.create(andNext = true) }, enabled = !ui.saving, modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp)) { Text("Create & next", fontSize = 22.sp) }
}
