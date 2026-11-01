package com.lfcreative.geartracker.ui.settings

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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.text.KeyboardOptions
import com.lfcreative.geartracker.data.settings.AppSettings
import com.lfcreative.geartracker.ui.theme.MinTouch
import com.lfcreative.geartracker.ui.theme.primaryAction

@Composable
fun SettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.heightIn(min = MinTouch)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Settings and diagnostics", style = MaterialTheme.typography.headlineSmall)
        }

        Section("Server")
        OutlinedTextField(
            value = s.serverUrlInput,
            onValueChange = viewModel::onServerUrlInput,
            label = { Text("Server URL (blank = build default)") },
            supportingText = { Text("In use: ${s.effectiveServerUrl}") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        s.serverUrlMessage?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Button(onClick = viewModel::saveServerUrl, modifier = Modifier.fillMaxWidth().primaryAction()) { Text("Save server URL", fontSize = 18.sp) }
        if (!s.anonKeyConfigured) {
            Text(
                "No Supabase key in this build. Add supabase.anonKey to apps/android/local.properties and rebuild.",
                color = MaterialTheme.colorScheme.error,
            )
        }

        HorizontalDivider()
        Section("Scanning")
        Text("Close the active target after (seconds idle)")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppSettings.IDLE_TIMEOUT_CHOICES.forEach { seconds ->
                FilterChip(
                    selected = s.settings.idleTimeoutSec == seconds,
                    onClick = { viewModel.setIdleTimeout(seconds) },
                    label = { Text("$seconds") },
                    modifier = Modifier.heightIn(min = MinTouch),
                )
            }
        }
        ToggleRow("Sound", s.settings.soundEnabled, viewModel::setSound)
        ToggleRow("Vibration", s.settings.vibrationEnabled, viewModel::setVibration)

        HorizontalDivider()
        Section("Diagnostics")
        Info("Version", "${s.versionName} (${s.versionCode})")
        Info("Signed in as", s.email ?: "-")
        Info("Waiting to sync", s.pendingEvents.toString())
        Info("Sync issues", s.rejectedEvents.toString())
        Info("Cached tags / assets / locations", "${s.cachedTags} / ${s.cachedAssets} / ${s.cachedLocations}")
        Info("NFC", s.nfcStatus)
        Info("DataWedge", "Not checked yet (added with the scanner integration)")

        HorizontalDivider()
        Button(
            onClick = viewModel::signOut,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth().primaryAction(),
        ) { Text("Sign out", fontSize = 18.sp) }
    }
}

@Composable
private fun Section(title: String) = Text(title, style = MaterialTheme.typography.titleLarge)

@Composable
private fun Info(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, fontSize = 18.sp)
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, fontSize = 18.sp)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
