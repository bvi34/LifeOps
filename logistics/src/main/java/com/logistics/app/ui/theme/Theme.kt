package com.logistics.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Logistics leans "stockroom green" so it reads as its own app next to LifeOps and Citation.
private val LightColors = lightColorScheme(
    primary = Color(0xFF2F855A),
    secondary = Color(0xFF38A169),
    tertiary = Color(0xFFDD6B20),
    background = Color(0xFFF7FAF7),
    surface = Color(0xFFFFFFFF)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF68D391),
    secondary = Color(0xFF9AE6B4),
    tertiary = Color(0xFFF6AD55),
    background = Color(0xFF14181A),
    surface = Color(0xFF1C2124)
)

@Composable
fun LogisticsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
