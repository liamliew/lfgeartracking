package com.lfcreative.geartracker.ui.asset

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lfcreative.geartracker.asset.AssetDetail
import com.lfcreative.geartracker.asset.AssetDetailViewModel
import com.lfcreative.geartracker.core.ScanInput
import com.lfcreative.geartracker.ui.scan.ScanInputEffect
import com.lfcreative.geartracker.ui.theme.MinTouch
import com.lfcreative.geartracker.ui.theme.primaryAction

@Composable
fun AssetDetailScreen(onBack: () -> Unit, submitScan: (ScanInput) -> Unit, viewModel: AssetDetailViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    ScanInputEffect(submitScan)

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
            IconButton(onClick = onBack, modifier = Modifier.heightIn(min = MinTouch)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Asset", style = MaterialTheme.typography.headlineSmall)
        }
        when {
            ui.loading -> CircularProgressIndicator(Modifier.padding(24.dp))
            ui.error != null -> Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(ui.error!!, color = MaterialTheme.colorScheme.error, fontSize = 18.sp)
                Button(onClick = viewModel::load, modifier = Modifier.primaryAction()) { Text("Retry") }
            }
            ui.notFound -> Text("This asset was not found.", modifier = Modifier.padding(24.dp), fontSize = 18.sp)
            ui.detail != null -> DetailBody(ui.detail!!, ui.armedForNfc, ui.message, viewModel)
        }
    }

    if (ui.confirmDamage) {
        AlertDialog(
            onDismissRequest = { viewModel.askReportDamage(false) },
            title = { Text("Report damage?") },
            text = { Text("The asset is marked In repair. A manager can change this later.") },
            confirmButton = { TextButton(onClick = viewModel::reportDamage) { Text("Report") } },
            dismissButton = { TextButton(onClick = { viewModel.askReportDamage(false) }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun DetailBody(d: AssetDetail, armed: Boolean, message: String?, vm: AssetDetailViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(d.name, fontSize = 28.sp, style = MaterialTheme.typography.headlineSmall)
        Text("${d.code}${if (d.isContainer) "  ·  container" else ""}", style = MaterialTheme.typography.titleMedium)
        Text("Status: ${d.status.replace('_', ' ')}${if (d.retired) " (retired)" else ""}", fontSize = 20.sp)
        d.openCheckout?.let { Text("Out: $it", fontSize = 18.sp, color = MaterialTheme.colorScheme.error) }
        Text("Where: ${d.breadcrumb ?: "not placed"}", fontSize = 18.sp)
        d.lastSeenAt?.let { Text("Last seen: ${it.take(16).replace('T', ' ')}${d.lastSeenBy?.let { by -> " by $by" }.orEmpty()}") }
        listOfNotNull(d.category, d.manufacturer, d.model, d.serialNumber?.let { "SN $it" }).takeIf { it.isNotEmpty() }
            ?.let { Text(it.joinToString("  ·  ")) }
        d.notes?.let { Text(it) }
        Text("Tags: ${d.tags.joinToString(", ")}")
        if (d.photoCount > 0) Text("${d.photoCount} photo(s) (viewing arrives in a later update)")

        message?.let { Text(it, color = MaterialTheme.colorScheme.secondary, fontSize = 16.sp) }

        // Quick actions. Move / Check out / Print label arrive with their own updates.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = { vm.armNfc(!armed) }, modifier = Modifier.weight(1f).primaryAction()) { Text(if (armed) "Cancel NFC" else "Add NFC tag") }
            OutlinedButton(onClick = { vm.askReportDamage(true) }, modifier = Modifier.weight(1f).primaryAction()) { Text("Report damage") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            listOf("Move", "Check out", "Print label").forEach {
                OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.weight(1f).heightIn(min = MinTouch)) { Text(it, fontSize = 13.sp) }
            }
        }

        HorizontalDivider()
        Text("Timeline", style = MaterialTheme.typography.titleLarge)
        if (d.timeline.isEmpty()) Text("No history yet")
        d.timeline.forEach { e ->
            Column(Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text(e.type.replace('_', ' '), fontSize = 18.sp)
                Text(
                    listOfNotNull(e.occurredAt.take(16).replace('T', ' '), e.userName, e.toLocation?.let { "to $it" }, e.toAsset?.let { "in $it" }).joinToString("  ·  "),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
