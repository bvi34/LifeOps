@file:OptIn(ExperimentalMaterial3Api::class)

package com.operations.sandbox.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.operations.backupkit.AppId
import com.operations.suite.ui.SuiteAppearanceStore
import com.operations.suite.ui.accentArgb
import com.operations.suite.ui.pickers.SuiteColorField
import com.operations.suitekit.SuiteAppearance
import com.operations.suitekit.SuiteApps
import com.operations.suitekit.SuiteColors
import com.operations.suitekit.SuiteIconPaint
import com.operations.suitekit.SuitePalette
import com.operations.suitekit.SuitePreset
import com.operations.suitekit.SuiteThemes

/**
 * What the whole suite looks like: the shared preset, light or dark, and the accent each app
 * is given inside it.
 */

@Composable
internal fun AppearanceTab(
    appearance: SuiteAppearance,
    store: SuiteAppearanceStore,
    focusedApp: AppId?
) {
    SectionCard(
        title = "Suite theme",
        subtitle = "One look for the whole suite. Every app follows this — there is no per-app theme."
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (appearance.darkMode) Icons.Filled.DarkMode else Icons.Filled.LightMode,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                if (appearance.darkMode) "Dark mode" else "Light mode",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = appearance.darkMode,
                onCheckedChange = { store.darkMode = it }
            )
        }

        Spacer(Modifier.height(12.dp))
        Text(
            "Colour preset",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SuitePreset.entries.forEach { preset ->
                PresetCard(
                    preset = preset,
                    selected = appearance.preset == preset,
                    appearance = appearance,
                    onClick = { store.preset = preset },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        if (appearance.preset == SuitePreset.CUSTOM) {
            Spacer(Modifier.height(14.dp))
            HorizontalDivider()
            Spacer(Modifier.height(10.dp))
            Text(
                "Custom colours",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Spacer(Modifier.height(8.dp))
            CustomPaletteEditor(appearance.palette) { store.palette = it }
        }
    }

    WallpaperSection(appearance, store)

    HomeScreenSection(appearance, store)

    SectionCard(
        title = "App colours",
        subtitle = "Each app's identity. Home icons always wear it; the switch decides whether the " +
            "app's own screens are tinted with it too."
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Tint each app with its colour",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = appearance.appAccentsEnabled,
                onCheckedChange = { store.appAccentsEnabled = it }
            )
        }

        // The one rule that needs stating out loud, because it is the only place two deliberate
        // choices can disagree: an app arrived with icon colours of its own, and it has also been
        // repainted here.
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Sandbox always wins", style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (appearance.sandboxWins)
                        "A colour you pick here beats an app's own icon colours."
                    else
                        "An app that brings its own icon colours keeps them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = appearance.sandboxWins,
                onCheckedChange = { store.sandboxWins = it }
            )
        }
        Spacer(Modifier.height(4.dp))

        SuiteApps.all.forEach { info ->
            // `key` gives each row its own identity, so the open/closed state below belongs to the
            // app it was opened on rather than to the position it happens to sit in.
            key(info.appId) {
                AppAccentRow(
                    appId = info.appId,
                    label = info.label,
                    tagline = info.tagline,
                    appearance = appearance,
                    startExpanded = focusedApp == info.appId,
                    onPick = { hex -> store.setAccent(info.appId, hex) },
                    onReset = { store.resetAccent(info.appId) },
                    onPaint = { paint -> store.setIconPaint(info.appId, paint) },
                    onResetPaint = { store.resetIconPaint(info.appId) }
                )
            }
        }

        Spacer(Modifier.height(4.dp))
        TextButton(onClick = { store.resetAllAppColors() }) {
            Text("Reset every app to its original colours")
        }
    }
}

/** One app's colour: a swatch to see it by, and — when opened — the ways to change it. */
@Composable
private fun AppAccentRow(
    appId: AppId,
    label: String,
    tagline: String,
    appearance: SuiteAppearance,
    startExpanded: Boolean,
    onPick: (String) -> Unit,
    onReset: () -> Unit,
    onPaint: (SuiteIconPaint) -> Unit,
    onResetPaint: () -> Unit
) {
    var expanded by rememberSaveable(startExpanded) { mutableStateOf(startExpanded) }
    val argb = appearance.accentArgb(appId)

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { expanded = !expanded }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppGlyph(appId = appId, argb = argb, size = 32.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyLarge)
                Text(
                    tagline,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                SuiteColors.toHex(argb),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (expanded) {
            // Line the swatches up under the label, not under the glyph: 32dp mark + 12dp gap.
            Column(Modifier.padding(start = 44.dp, bottom = 8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SUGGESTED_ACCENTS.forEach { suggestion ->
                        Swatch(
                            argb = suggestion,
                            selected = suggestion == argb,
                            onClick = { onPick(SuiteColors.toHex(suggestion)) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SuiteColorField(
                        label = "Custom",
                        color = SuiteColors.toHex(argb),
                        onColorChange = onPick,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = onReset) { Text("Reset") }
                }

                Spacer(Modifier.height(10.dp))
                HorizontalDivider()
                Spacer(Modifier.height(6.dp))
                IconColorsEditor(
                    appId = appId,
                    label = label,
                    appearance = appearance,
                    onPaint = onPaint,
                    onReset = onResetPaint
                )
            }
        }
    }
}

/**
 * The second half of an app's identity: the two colours its *mark* is drawn in, rather than the one
 * its tile is tinted with. This is the customization LifeOps arrived with — a purple dial and an
 * amber checkmark instead of a single-hue drawing — offered to every app.
 *
 * The two roles are the same in every mark ([com.operations.suite.ui.SuiteGlyphs]): `Line` is the
 * structure that holds the drawing up, `Highlight` the one element it exists to show. An app given
 * no pair of its own starts from the suite's secondary and tertiary for it, which is the mapping
 * LifeOps' own icon uses, so switching this on lands somewhere deliberate rather than somewhere
 * random.
 */
@Composable
private fun IconColorsEditor(
    appId: AppId,
    label: String,
    appearance: SuiteAppearance,
    onPaint: (SuiteIconPaint) -> Unit,
    onReset: () -> Unit
) {
    val paint = appearance.iconPaintFor(appId)
    val shipped = SuiteApps.of(appId).iconColors != null
    val overridden = shipped && !appearance.hasCustomIconColors(appId) &&
        appearance.sandboxWins && appearance.hasCustomAccent(appId)

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Its own icon colours", style = MaterialTheme.typography.bodyMedium)
                Text(
                    when {
                        overridden ->
                            "$label ships with its own, but you repainted it and the sandbox wins."
                        paint.enabled -> "Drawn in these two, not tinted with the colour above."
                        else -> "Off — the mark is tinted with the colour above."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = paint.enabled,
                onCheckedChange = { on -> onPaint(paint.copy(enabled = on)) }
            )
        }

        if (paint.enabled) {
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SuiteColorField(
                    label = "Line",
                    color = paint.line,
                    onColorChange = { hex -> onPaint(paint.copy(line = hex, enabled = true)) }
                )
                SuiteColorField(
                    label = "Highlight",
                    color = paint.highlight,
                    onColorChange = { hex -> onPaint(paint.copy(highlight = hex, enabled = true)) }
                )
            }
        }

        if (appearance.hasCustomIconColors(appId)) {
            TextButton(onClick = onReset) {
                Text(if (shipped) "Back to its shipped icon colours" else "Back to a tinted mark")
            }
        }
    }
}

/** The palette the accent picker offers before anyone reaches for a hex code. */
private val SUGGESTED_ACCENTS: List<Long> = listOf(
    0xFF6200EEL, // purple
    0xFF2C7A7BL, // teal
    0xFF5A5ABFL, // indigo
    0xFF2F855AL, // green
    0xFF4A5568L, // slate
    0xFF9333EAL, // violet
    0xFFB7791FL, // amber
    0xFFC53030L  // red
)

@Composable
private fun Swatch(argb: Long, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(Color(argb))
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                shape = CircleShape
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = Color(SuiteColors.contrastOn(argb)),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun CustomPaletteEditor(palette: SuitePalette, onChange: (SuitePalette) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SuiteColorField("Primary", palette.primary, { onChange(palette.copy(primary = it)) })
        SuiteColorField("Secondary", palette.secondary, { onChange(palette.copy(secondary = it)) })
        SuiteColorField("Tertiary", palette.tertiary, { onChange(palette.copy(tertiary = it)) })
        SuiteColorField("Dark background", palette.darkBackground, { onChange(palette.copy(darkBackground = it)) })
        SuiteColorField("Light background", palette.lightBackground, { onChange(palette.copy(lightBackground = it)) })
    }
}

@Composable
private fun PresetCard(
    preset: SuitePreset,
    selected: Boolean,
    appearance: SuiteAppearance,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Each card previews the scheme it would actually apply — resolved by the same code that paints
    // the apps, so what a preset looks like here is what the suite becomes.
    val preview = remember(preset, appearance.darkMode, appearance.palette) {
        SuiteThemes.scheme(preset, appearance.darkMode, appearance.palette)
    }
    OutlinedCard(
        onClick = onClick,
        modifier = modifier,
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
        )
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                listOf(preview.primary, preview.secondary, preview.tertiary).forEach { colour ->
                    Box(Modifier.size(12.dp).clip(CircleShape).background(Color(colour)))
                }
            }
            Text(
                preset.displayName,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
        }
    }
}
