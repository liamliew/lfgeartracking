package com.lfcreative.geartracker.ui.theme

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// High contrast on purpose: rugged handhelds are used outdoors and in warehouses.
private val Navy = Color(0xFF0B2545)
private val Scheme = lightColorScheme(
    primary = Navy,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E4FF),
    onPrimaryContainer = Navy,
    secondary = Color(0xFF1B5E20),
    background = Color.White,
    onBackground = Color(0xFF111111),
    surface = Color.White,
    onSurface = Color(0xFF111111),
    surfaceVariant = Color(0xFFEFF1F5),
    onSurfaceVariant = Color(0xFF222222),
    error = Color(0xFFB00020),
)

@Composable
fun GearTrackerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}

/** Touch targets are at least 48 dp; primary actions are 64 dp (glove friendly). */
val MinTouch = 48.dp
val PrimaryActionHeight = 64.dp
fun Modifier.primaryAction(): Modifier = this.heightIn(min = PrimaryActionHeight).defaultMinSize(minWidth = MinTouch)
