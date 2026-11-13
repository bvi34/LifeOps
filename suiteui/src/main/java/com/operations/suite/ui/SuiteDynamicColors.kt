package com.operations.suite.ui

import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.toArgb
import com.operations.suitekit.SuiteSystemColors
import com.operations.suitekit.SuiteSystemPalette

/**
 * The Android half of the Wallpaper preset: reads the platform's Material You scheme (generated from
 * the wallpaper) and hands its seed colours to [SuiteSystemPalette], where the suite builds its own
 * scheme from them like any other preset's.
 *
 * Read fresh each time rather than cached: changing the wallpaper changes these colours, and the
 * activities that repaint after it ask again. Installed once per process by [SuiteAppearanceStore].
 */
internal object SuiteDynamicColors {

    fun install(context: Context) {
        if (SuiteSystemPalette.source != null) return
        val appContext = context.applicationContext
        SuiteSystemPalette.source = { dark ->
            val scheme = if (dark) dynamicDarkColorScheme(appContext) else dynamicLightColorScheme(appContext)
            scheme.toSystemColors()
        }
    }

    private fun ColorScheme.toSystemColors() = SuiteSystemColors(
        primary = primary.toArgb().toLong() and 0xFFFFFFFFL,
        secondary = secondary.toArgb().toLong() and 0xFFFFFFFFL,
        tertiary = tertiary.toArgb().toLong() and 0xFFFFFFFFL,
        background = background.toArgb().toLong() and 0xFFFFFFFFL,
        surface = surfaceContainer.toArgb().toLong() and 0xFFFFFFFFL,
        surfaceVariant = surfaceContainerHighest.toArgb().toLong() and 0xFFFFFFFFL
    )
}
