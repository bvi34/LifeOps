@file:OptIn(ExperimentalMaterial3Api::class)

package com.operations.sandbox.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.operations.suite.ui.SuiteAppearanceStore
import com.operations.suite.ui.pickers.SuiteColorField
import com.operations.suite.ui.suiteWallpaper
import com.operations.suitekit.SuiteAppearance
import com.operations.suitekit.SuiteScheme
import com.operations.suitekit.SuiteThemes
import com.operations.suitekit.SuiteWallpaper
import com.operations.suitekit.SuiteWallpapers
import com.operations.suitekit.WallpaperAngle
import com.operations.suitekit.WallpaperDesign
import com.operations.suitekit.WallpaperStyle
import kotlin.math.roundToInt

/**
 * The wallpaper behind that grid — a shipped design, your own gradient, or the suite's own
 * colours — and how far it is dimmed.
 */

/**
 * The launcher's backdrop.
 *
 * The home screen is the one screen in the suite that belongs to nobody's app, so it is the one
 * screen worth decorating: a shelf of shipped designs, a Custom entry with the same four styles the
 * shipped ones are built from, and a dim that quietens any of them behind the clock. Nothing here
 * touches a hosted app — an app's look is still the preset, the mode and its accent.
 *
 * Every card paints itself with the same modifier the home screen uses, so a preview is the design
 * at swatch size rather than an artist's impression of it.
 */
@Composable
internal fun WallpaperSection(appearance: SuiteAppearance, store: SuiteAppearanceStore) {
    val wallpaper = appearance.wallpaper
    // The shell's own scheme — what the THEME design mixes itself from.
    val scheme = remember(appearance.preset, appearance.darkMode, appearance.palette) {
        SuiteThemes.scheme(appearance, appId = null)
    }

    SectionCard(
        title = "Home wallpaper",
        subtitle = "The backdrop behind the app tiles. This is the launcher's alone — the apps " +
            "keep the theme above."
    ) {
        SuiteWallpapers.designs.chunked(WALLPAPER_COLUMNS).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                row.forEach { design ->
                    WallpaperCard(
                        design = design,
                        selected = wallpaper.design == design,
                        wallpaper = wallpaper,
                        scheme = scheme,
                        onClick = { store.updateWallpaper { it.copy(design = design) } },
                        modifier = Modifier.weight(1f)
                    )
                }
                repeat(WALLPAPER_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }

        Text(
            wallpaper.design.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (wallpaper.design == WallpaperDesign.CUSTOM) {
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(10.dp))
            CustomWallpaperEditor(wallpaper) { updated -> store.wallpaper = updated }
        }

        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(Modifier.height(10.dp))
        DimSlider(wallpaper.dim) { dim -> store.updateWallpaper { it.copy(dim = dim) } }

        TextButton(onClick = { store.resetWallpaper() }) {
            Text("Back to the theme's own wallpaper")
        }
    }
}

/** One design: what it actually paints, at swatch size, with its name under it. */
@Composable
private fun WallpaperCard(
    design: WallpaperDesign,
    selected: Boolean,
    wallpaper: SuiteWallpaper,
    scheme: SuiteScheme,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val preview = remember(design, wallpaper, scheme) {
        SuiteWallpapers.preview(design, wallpaper, scheme)
    }
    Column(
        modifier = modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .clip(RoundedCornerShape(12.dp))
                .suiteWallpaper(preview)
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(12.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    // The ink the design resolved to, so the tick is readable on its own swatch.
                    tint = Color(preview.ink),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            design.displayName,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface,
            maxLines = 1
        )
    }
}

/** The Custom design's controls: which style, which way it runs, and the colours it runs between. */
@Composable
private fun CustomWallpaperEditor(wallpaper: SuiteWallpaper, onChange: (SuiteWallpaper) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Style",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        // The chips are a shelf, not a grid: on a narrow phone they scroll rather than wrap into
        // an uneven second row.
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            WallpaperStyle.entries.forEach { style ->
                FilterChip(
                    selected = wallpaper.style == style,
                    onClick = { onChange(wallpaper.copy(style = style)) },
                    label = { Text(style.displayName, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }

        // Direction only means something for a gradient; the glows have their own geometry.
        if (wallpaper.style == WallpaperStyle.LINEAR) {
            Text(
                "Direction",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                WallpaperAngle.entries.forEach { angle ->
                    FilterChip(
                        selected = wallpaper.angle == angle,
                        onClick = { onChange(wallpaper.copy(angle = angle)) },
                        label = { Text(angle.displayName, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }
        }

        SuiteColorField(
            label = when (wallpaper.style) {
                WallpaperStyle.LINEAR -> "From"
                else -> "Background"
            },
            color = wallpaper.startColor,
            onColorChange = { hex -> onChange(wallpaper.copy(startColor = hex)) }
        )

        if (wallpaper.style == WallpaperStyle.LINEAR || wallpaper.style == WallpaperStyle.AURORA) {
            SuiteColorField(
                label = if (wallpaper.style == WallpaperStyle.LINEAR) "To" else "Second light",
                color = wallpaper.endColor,
                onColorChange = { hex -> onChange(wallpaper.copy(endColor = hex)) }
            )
        }

        if (wallpaper.style == WallpaperStyle.RADIAL || wallpaper.style == WallpaperStyle.AURORA) {
            SuiteColorField(
                label = if (wallpaper.style == WallpaperStyle.RADIAL) "Glow" else "First light",
                color = wallpaper.glowColor,
                onColorChange = { hex -> onChange(wallpaper.copy(glowColor = hex)) }
            )
        }
    }
}

/**
 * How far back the wallpaper is pushed. Any design can be quietened this way, which is what keeps
 * "a picture I like" and "a clock I can read" from being the same decision.
 */
@Composable
private fun DimSlider(dim: Float, onChange: (Float) -> Unit) {
    // The thumb follows the finger locally and the choice is written once it settles: this value is
    // saved to preferences and re-resolves every swatch on the screen, which is not a thing to do
    // sixty times a second while a finger is moving.
    var travelling by remember(dim) { mutableFloatStateOf(dim) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Dim",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(44.dp)
        )
        Slider(
            value = travelling,
            onValueChange = { travelling = it },
            onValueChangeFinished = { onChange(travelling) },
            valueRange = 0f..SuiteWallpaper.MAX_DIM,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "${(travelling * 100).roundToInt()}%",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(44.dp)
        )
    }
}
