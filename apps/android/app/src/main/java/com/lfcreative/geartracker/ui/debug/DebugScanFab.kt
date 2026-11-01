package com.lfcreative.geartracker.ui.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lfcreative.geartracker.core.ScanInput
import com.lfcreative.geartracker.core.ScanSource
import java.time.Instant

/** Debug builds only: type or pick a code and send it through the same flow as a real scan. */
private val Presets = listOf("A000001", "C000001", "L00001", "110164", "04-46-88-0A-BF-2A-81")

@Composable
fun DebugScanFab(onScan: (ScanInput) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var asNfc by remember { mutableStateOf(false) }
    val recents = remember { mutableStateListOf<String>() }

    ExtendedFloatingActionButton(onClick = { open = true }, modifier = modifier.heightIn(min = 56.dp)) { Text("Scan") }

    if (open) {
        fun send(value: String) {
            if (value.isBlank()) return
            onScan(ScanInput(value, if (asNfc) ScanSource.NFC else ScanSource.DEBUG, "DEBUG", Instant.now()))
            recents.remove(value); recents.add(0, value)
            while (recents.size > 6) recents.removeAt(recents.lastIndex)
        }
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("Debug scan") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(text, { text = it }, label = { Text("Code") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Switch(asNfc, { asNfc = it }); Text("Send as NFC")
                    }
                    Text("Presets / recent")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (Presets + recents).distinct().forEach { code ->
                            AssistChip(onClick = { send(code) }, label = { Text(code) })
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { send(text) }) { Text("Send") } },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Close") } },
        )
    }
}
