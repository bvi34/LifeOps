package com.lifeops.app.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.lifeops.app.data.model.ThemePreset

private val Snow = Color(0xFFF9FAFB)
private val Ink = Color(0xFF111827)

private fun defaultDarkColors() = darkColorScheme(
    primary = Color(0xFFBB86FC),
    onPrimary = Color.Black,
    secondary = Color(0xFF03DAC6),
    onSecondary = Color.Black,
    tertiary = Color(0xFF3700B3),
    background = Color(0xFF121212),
    surface = Color(0xFF1E1E1E),
    surfaceVariant = Color(0xFF2A2A2A),
    onBackground = Snow,
    onSurface = Snow
)

private fun defaultLightColors() = lightColorScheme(
    primary = Color(0xFF6200EE),
    onPrimary = Color.White,
    secondary = Color(0xFF03DAC6),
    onSecondary = Color.Black,
    tertiary = Color(0xFF018786),
    onTertiary = Color.White,
    background = Color(0xFFF5F5F5),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE8E8E8),
    onBackground = Ink,
    onSurface = Ink
)

private fun beaconDarkColors() = darkColorScheme(
    primary = Color(0xFFC8B3E0),
    onPrimary = Color(0xFF1A0033),
    primaryContainer = Color(0xFF4A2B73),
    onPrimaryContainer = Color(0xFFE8D5F5),
    secondary = Color(0xFF9B72CF),
    onSecondary = Color(0xFF1A0033),
    tertiary = Color(0xFFFFB74D),
    onTertiary = Color(0xFF1A0900),
    background = Color(0xFF111827),
    surface = Color(0xFF1C2333),
    surfaceVariant = Color(0xFF253045),
    onBackground = Color(0xFFF9FAFB),
    onSurface = Color(0xFFF9FAFB),
    onSurfaceVariant = Color(0xFFCDD8EE)
)

private fun beaconLightColors() = lightColorScheme(
    primary = Color(0xFF3B1F5E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEDE8F5),
    onPrimaryContainer = Color(0xFF1A0033),
    secondary = Color(0xFF6B4C9A),
    onSecondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFFE65100),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFF9FAFB),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFEDE8F5),
    onBackground = Color(0xFF111827),
    onSurface = Color(0xFF111827),
    onSurfaceVariant = Color(0xFF3B2F4E)
)

private fun oceanDarkColors() = darkColorScheme(
    primary = Color(0xFF90CAF9),
    onPrimary = Color(0xFF001E36),
    primaryContainer = Color(0xFF004B77),
    onPrimaryContainer = Color(0xFFD1E4FF),
    secondary = Color(0xFF4FC3F7),
    onSecondary = Color(0xFF001F2B),
    tertiary = Color(0xFF80DEEA),
    onTertiary = Color(0xFF00191D),
    background = Color(0xFF0A1628),
    surface = Color(0xFF0D1B2A),
    surfaceVariant = Color(0xFF152035),
    onBackground = Snow,
    onSurface = Snow,
    onSurfaceVariant = Color(0xFFB0C8E0)
)

private fun oceanLightColors() = lightColorScheme(
    primary = Color(0xFF0277BD),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD1E4FF),
    onPrimaryContainer = Color(0xFF001E36),
    secondary = Color(0xFF0288D1),
    onSecondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFF00838F),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFF0F7FF),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFD1E4FF),
    onBackground = Ink,
    onSurface = Ink,
    onSurfaceVariant = Color(0xFF004B77)
)

private fun sunsetDarkColors() = darkColorScheme(
    primary = Color(0xFFFFAB40),
    onPrimary = Color(0xFF1A0900),
    primaryContainer = Color(0xFF7B3100),
    onPrimaryContainer = Color(0xFFFFDCC2),
    secondary = Color(0xFFFF7043),
    onSecondary = Color(0xFF1A0A00),
    tertiary = Color(0xFFFFCC02),
    onTertiary = Color(0xFF1A1100),
    background = Color(0xFF1A0F00),
    surface = Color(0xFF261500),
    surfaceVariant = Color(0xFF341C00),
    onBackground = Snow,
    onSurface = Snow,
    onSurfaceVariant = Color(0xFFE8CCAA)
)

private fun sunsetLightColors() = lightColorScheme(
    primary = Color(0xFFBF360C),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDCC2),
    onPrimaryContainer = Color(0xFF1A0900),
    secondary = Color(0xFFE65100),
    onSecondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFFF57F17),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFFFF8F0),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFFFECDC),
    onBackground = Ink,
    onSurface = Ink,
    onSurfaceVariant = Color(0xFF4A2000)
)

fun buildColorScheme(preset: ThemePreset, dark: Boolean): ColorScheme = when (preset) {
    ThemePreset.DEFAULT -> if (dark) defaultDarkColors() else defaultLightColors()
    ThemePreset.BEACON -> if (dark) beaconDarkColors() else beaconLightColors()
    ThemePreset.OCEAN -> if (dark) oceanDarkColors() else oceanLightColors()
    ThemePreset.SUNSET -> if (dark) sunsetDarkColors() else sunsetLightColors()
}

@Composable
fun LifeOpsTheme(
    preset: ThemePreset = ThemePreset.DEFAULT,
    darkMode: Boolean = true,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = buildColorScheme(preset, darkMode),
        typography = Typography(),
        content = content
    )
}
