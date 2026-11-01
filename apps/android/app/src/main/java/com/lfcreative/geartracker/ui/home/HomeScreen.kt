package com.lfcreative.geartracker.ui.home

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
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lfcreative.geartracker.ui.theme.MinTouch

private data class Tile(val title: String, val subtitle: String, val icon: ImageVector, val enabled: Boolean = false)

private val Tiles = listOf(
    Tile("Pack / Move", "Containers and storage", Icons.Default.ShoppingCart, enabled = true),
    Tile("Check In", "Return gear", Icons.Default.CheckCircle),
    Tile("Check Out", "Send gear out", Icons.AutoMirrored.Filled.ExitToApp),
    Tile("Update", "Mark last seen", Icons.Default.LocationOn),
    Tile("Audit", "Count a location", Icons.Default.Search),
    Tile("New Asset", "Add gear", Icons.Default.Add, enabled = true),
)

@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit,
    onOpenPackMove: () -> Unit,
    onOpenCreate: () -> Unit,
    onOpenSearch: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Gear Tracker", style = MaterialTheme.typography.headlineMedium)
                Text(state.email ?: "Signed in", style = MaterialTheme.typography.bodyMedium)
            }
            IconButton(onClick = onOpenSettings, modifier = Modifier.heightIn(min = MinTouch)) {
                Icon(Icons.Default.Settings, contentDescription = "Settings and diagnostics")
            }
        }

        // Sync status chip: always colour + icon + text.
        val syncText = when {
            state.rejectedEvents > 0 -> "${state.rejectedEvents} sync issue(s)"
            state.pendingEvents > 0 -> "${state.pendingEvents} waiting to sync"
            else -> "All synced"
        }
        AssistChip(
            onClick = onOpenSettings,
            label = { Text(syncText, fontSize = 16.sp) },
            leadingIcon = {
                Icon(if (state.rejectedEvents > 0) Icons.Default.Warning else Icons.Default.Refresh, contentDescription = null)
            },
            modifier = Modifier.heightIn(min = MinTouch),
        )

        // Search / scan: opens Search, where a scan jumps straight to the asset.
        Card(onClick = onOpenSearch, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.Search, contentDescription = null)
                Text("Search or scan an asset", fontSize = 20.sp)
            }
        }

        // Two columns of large tiles. Sessions are enabled as their milestones land.
        Tiles.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { tile -> TileCard(tile, onClick = when (tile.title) { "Pack / Move" -> onOpenPackMove; "New Asset" -> onOpenCreate; else -> ({}) }, Modifier.weight(1f)) }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (state.overdueCount > 0) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.Warning, contentDescription = null)
                Text("Overdue check-outs: ${state.overdueCount}", fontSize = 20.sp)
            }
        }
    }
}

@Composable
private fun TileCard(tile: Tile, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        onClick = onClick,
        enabled = tile.enabled, // Other sessions arrive in later tasks; the tile shows where they will live.
        modifier = modifier.heightIn(min = 112.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(tile.icon, contentDescription = null)
            Text(tile.title, fontSize = 22.sp, style = MaterialTheme.typography.titleLarge)
            Text(if (tile.enabled) tile.subtitle else "${tile.subtitle} - coming soon", style = MaterialTheme.typography.bodySmall)
        }
    }
}
