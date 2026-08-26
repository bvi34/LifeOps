package com.operations.suite.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.operations.suitekit.SuiteAppearance
import com.operations.suitekit.SuiteThemes
import com.operations.suitekit.SuiteWallpapers
import com.operations.suitekit.WallpaperLayer
import com.operations.suitekit.WallpaperShape
import com.operations.suitekit.WallpaperSpec
import kotlin.math.max

/**
 * The Android half of the launcher's backdrop: a [WallpaperSpec] — decided entirely in :suitekit —
 * turned into Compose brushes. No colour, geometry or fallback is chosen here; this file is the
 * same kind of thin adapter [SuiteTheme] is for colour schemes.
 *
 * One consequence worth keeping: because the picker's preview swatches call the very same modifier,
 * a card in settings is not an impression of a design, it *is* the design at a smaller size.
 */

/** Paint [spec] behind this element: the base, then each layer in order, then the dim veil. */
fun Modifier.suiteWallpaper(spec: WallpaperSpec): Modifier = drawWithCache {
    // Brushes are rebuilt only when the spec or the size changes — not on every frame of a scroll.
    val brushes = spec.layers.map { it.toBrush(size) }
    val base = Color(spec.base)
    val veil = Color(spec.veil)
    onDrawBehind {
        drawRect(base)
        brushes.forEach { drawRect(it) }
        if (veil.alpha > 0f) drawRect(veil)
    }
}

/** The wallpaper the sandbox home screen is currently wearing, resolved from the live appearance. */
@Composable
fun rememberSuiteWallpaper(): WallpaperSpec {
    val store = SuiteAppearanceStore.get(LocalContext.current)
    val appearance by store.state.collectAsState()
    return rememberSuiteWallpaper(appearance)
}

/** The stateless form — used by the settings preview, which paints an appearance not yet saved. */
@Composable
fun rememberSuiteWallpaper(appearance: SuiteAppearance): WallpaperSpec = remember(appearance) {
    SuiteWallpapers.spec(appearance, SuiteThemes.scheme(appearance, appId = null))
}

/** Fractions of the box become pixels here, and nowhere else. */
private fun WallpaperLayer.toBrush(size: Size): Brush {
    val stops = stops.map { it.position to Color(it.color) }.toTypedArray()
    return when (shape) {
        WallpaperShape.LINEAR -> Brush.linearGradient(
            colorStops = stops,
            start = Offset(startX * size.width, startY * size.height),
            end = Offset(endX * size.width, endY * size.height)
        )
        WallpaperShape.RADIAL -> Brush.radialGradient(
            colorStops = stops,
            center = Offset(centerX * size.width, centerY * size.height),
            // Against the longer side, so a glow keeps its shape on a tall phone and a short one.
            radius = (radius * max(size.width, size.height)).coerceAtLeast(1f)
        )
    }
}

/** The colour text and glyphs must be written in to stay readable on [WallpaperSpec]. */
val WallpaperSpec.inkColor: Color get() = Color(ink)
