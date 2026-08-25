package com.people.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// People leans a warm indigo — the directory the rest of the suite refers to, so it reads as its own
// app next to LifeOps, Citation, Logistics, Advisor and Health.
private val LightColors = lightColorScheme(
    primary = Color(0xFF5A5ABF),
    secondary = Color(0xFF7C7CD4),
    tertiary = Color(0xFFB7791F),
    background = Color(0xFFF8F8FC),
    surface = Color(0xFFFFFFFF)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA3A3EE),
    secondary = Color(0xFFC3C3F5),
    tertiary = Color(0xFFF6AD55),
    background = Color(0xFF15151C),
    surface = Color(0xFF1D1D26)
)

@Composable
fun PeopleTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
