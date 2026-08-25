package com.health.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Health leans a calm clinical teal so it reads as its own app next to LifeOps, Citation and
// Logistics — and keeps red for one job only: a reading that wants attention.
private val LightColors = lightColorScheme(
    primary = Color(0xFF2C7A7B),
    secondary = Color(0xFF319795),
    tertiary = Color(0xFFC05621),
    error = Color(0xFFC53030),
    background = Color(0xFFF5FAFA),
    surface = Color(0xFFFFFFFF)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4FD1C5),
    secondary = Color(0xFF81E6D9),
    tertiary = Color(0xFFF6AD55),
    error = Color(0xFFFC8181),
    background = Color(0xFF11191A),
    surface = Color(0xFF1A2325)
)

@Composable
fun HealthTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
