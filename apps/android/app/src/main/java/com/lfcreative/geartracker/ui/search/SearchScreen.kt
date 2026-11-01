package com.lfcreative.geartracker.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.lfcreative.geartracker.asset.SearchViewModel
import com.lfcreative.geartracker.core.ScanInput
import com.lfcreative.geartracker.ui.scan.ScanInputEffect
import com.lfcreative.geartracker.ui.theme.MinTouch

@Composable
fun SearchScreen(onBack: () -> Unit, onOpenAsset: (String) -> Unit, submitScan: (ScanInput) -> Unit, viewModel: SearchViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    ScanInputEffect(submitScan)
    LaunchedEffect(Unit) { viewModel.open.collect(onOpenAsset) }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
            IconButton(onClick = onBack, modifier = Modifier.heightIn(min = MinTouch)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Search", style = MaterialTheme.typography.headlineSmall)
        }
        OutlinedTextField(
            value = ui.query, onValueChange = viewModel::onQuery, singleLine = true,
            label = { Text("Name, code, serial - or scan a tag") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        if (ui.loading) CircularProgressIndicator(Modifier.padding(16.dp))
        ui.message?.let { Text(it, modifier = Modifier.padding(16.dp), fontSize = 18.sp) }
        LazyColumn(Modifier.fillMaxSize()) {
            items(ui.results, key = { it.id }) { a ->
                Column(
                    Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable { onOpenAsset(a.id) }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(a.name, fontSize = 22.sp)
                    Text(
                        buildString {
                            append(a.code); append("  ·  "); append(a.status.replace('_', ' '))
                            if (a.isContainer) append("  ·  container")
                            a.serialNumber?.let { append("  ·  SN $it") }
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                HorizontalDivider()
            }
        }
    }
}
