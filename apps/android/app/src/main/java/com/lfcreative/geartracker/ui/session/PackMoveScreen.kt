package com.lfcreative.geartracker.ui.session

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lfcreative.geartracker.feedback.CueColor
import com.lfcreative.geartracker.feedback.CueIcon
import com.lfcreative.geartracker.feedback.FeedbackCues
import com.lfcreative.geartracker.session.PackMoveMachine.Phase
import com.lfcreative.geartracker.session.PackMoveViewModel
import com.lfcreative.geartracker.session.ResolvedTag
import com.lfcreative.geartracker.ui.scan.ScanInputEffect
import com.lfcreative.geartracker.ui.theme.MinTouch
import com.lfcreative.geartracker.ui.theme.primaryAction

private fun CueColor.color() = when (this) {
    CueColor.GREEN -> Color(0xFF1B5E20)
    CueColor.BLUE -> Color(0xFF0D47A1)
    CueColor.GREY -> Color(0xFF455A64)
    CueColor.AMBER -> Color(0xFF8A5300)
    CueColor.RED -> Color(0xFFB00020)
}

private fun CueIcon.vector(): ImageVector = when (this) {
    CueIcon.CHECK -> Icons.Default.Check
    CueIcon.TARGET -> Icons.Default.LocationOn
    CueIcon.SWITCH -> Icons.Default.Refresh
    CueIcon.CLOSE -> Icons.Default.Close
    CueIcon.REPEAT -> Icons.Default.Notifications
    CueIcon.QUESTION -> Icons.Default.Info
    CueIcon.ERROR -> Icons.Default.Warning
    CueIcon.UNDO -> Icons.AutoMirrored.Filled.ArrowBack
    CueIcon.CLOCK -> Icons.Default.DateRange
}

@Composable
fun PackMoveScreen(onBack: () -> Unit, viewModel: PackMoveViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val machine = ui.machine
    val phase = machine.phase

    ScanInputEffect(viewModel::submitScan)
    // Back first closes an open target, then leaves the screen.
    BackHandler(enabled = phase is Phase.InTarget) { viewModel.onDone() }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
            IconButton(onClick = onBack, modifier = Modifier.heightIn(min = MinTouch)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Pack / Move", style = MaterialTheme.typography.headlineSmall)
        }

        // The target banner is always visible: icon, name, path, count and the idle countdown.
        TargetBanner(phase, ui.remainingMs)

        ui.lastFeedback?.let { kind ->
            val cue = FeedbackCues.forKind(kind)
            Row(
                Modifier.fillMaxWidth().background(cue.color.color()).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(cue.icon.vector(), contentDescription = null, tint = Color.White, modifier = Modifier.size(32.dp))
                Column {
                    Text(cue.label, color = Color.White, fontSize = 22.sp, style = MaterialTheme.typography.titleLarge)
                    machine.message?.let { Text(it, color = Color.White, fontSize = 16.sp) }
                }
            }
        }

        machine.nestOffer?.let { offer ->
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Put ${offer.from.name} inside ${offer.to.name} instead?", modifier = Modifier.weight(1f), fontSize = 18.sp)
                Button(onClick = viewModel::onNest, modifier = Modifier.heightIn(min = MinTouch)) { Text("Nest") }
            }
        }

        val rows: List<ResolvedTag.Item> = when (phase) {
            Phase.Idle -> emptyList()
            is Phase.Basket -> phase.items
            is Phase.InTarget -> phase.placed.asReversed()
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (rows.isEmpty()) {
                Text(
                    when (phase) {
                        Phase.Idle -> "Scan items to build a basket, or scan a container or location to start putting items in it."
                        is Phase.Basket -> "Scan a container or location to place the basket."
                        is Phase.InTarget -> "Scan items to put them in ${phase.target.name}."
                    },
                    modifier = Modifier.padding(24.dp),
                    fontSize = 18.sp,
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(rows, key = { it.id }) { item ->
                        Column(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text(item.name, fontSize = 22.sp)
                            Text(item.code, style = MaterialTheme.typography.bodyMedium)
                        }
                        HorizontalDivider()
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = viewModel::onUndo, enabled = machine.undo.isNotEmpty(), modifier = Modifier.weight(1f).primaryAction()) {
                Text("Undo", fontSize = 18.sp)
            }
            when (phase) {
                is Phase.Basket -> Button(onClick = viewModel::onClearBasket, modifier = Modifier.weight(1f).primaryAction()) { Text("Clear", fontSize = 18.sp) }
                is Phase.InTarget -> Button(onClick = viewModel::onDone, modifier = Modifier.weight(1f).primaryAction()) { Text("Done", fontSize = 18.sp) }
                Phase.Idle -> Unit
            }
        }
    }

    ui.decisionFor?.let { value ->
        AlertDialog(
            onDismissRequest = viewModel::dismissDecision,
            title = { Text("Unknown tag") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(value, fontSize = 20.sp)
                    Text("Creating or assigning gear from here arrives with the New Asset update.", style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = { TextButton(onClick = viewModel::dismissDecision) { Text("Ignore") } },
        )
    }
}

@Composable
private fun TargetBanner(phase: Phase, remainingMs: Long?) {
    val (title, subtitle, count) = when (phase) {
        Phase.Idle -> Triple("No target", "Scan a container or location", null)
        is Phase.Basket -> Triple("Basket: ${phase.items.size} item(s)", "Scan a container or location to place them", null)
        is Phase.InTarget -> {
            val t = phase.target
            val path = (t as? ResolvedTag.Location)?.path ?: t.code
            Triple(t.name, path, phase.placed.size)
        }
    }
    val active = phase is Phase.InTarget
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Default.LocationOn, contentDescription = null, modifier = Modifier.size(32.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 24.sp, style = MaterialTheme.typography.titleLarge)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium)
        }
        if (count != null) Text("$count", fontSize = 28.sp)
        // Countdown ring for the last 10 s before the idle timeout closes the target.
        if (remainingMs != null && remainingMs <= 10_000) {
            CircularProgressIndicator(progress = { remainingMs / 10_000f }, color = Color(0xFF8A5300), modifier = Modifier.size(40.dp))
        }
    }
}
