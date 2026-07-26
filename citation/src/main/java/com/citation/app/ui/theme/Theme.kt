package com.citation.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF4A5568),
    secondary = Color(0xFF718096),
    background = Color(0xFFFBF9F4), // warm paper
    surface = Color(0xFFFBF9F4)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA0AEC0),
    secondary = Color(0xFF718096),
    background = Color(0xFF1A1A1A),
    surface = Color(0xFF1A1A1A)
)

@Composable
fun CitationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
