package com.advisor.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Advisor leans "counsel violet" so it reads as its own app next to LifeOps, Citation and Logistics.
private val LightColors = lightColorScheme(
    primary = Color(0xFF6D28D9),
    secondary = Color(0xFF7C3AED),
    tertiary = Color(0xFF0EA5E9),
    background = Color(0xFFF8F7FC),
    surface = Color(0xFFFFFFFF)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFC4B5FD),
    secondary = Color(0xFFA78BFA),
    tertiary = Color(0xFF7DD3FC),
    background = Color(0xFF141218),
    surface = Color(0xFF1C1A22)
)

@Composable
fun AdvisorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
