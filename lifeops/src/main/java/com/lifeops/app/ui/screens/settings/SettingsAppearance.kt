@file:OptIn(ExperimentalMaterial3Api::class)

package com.lifeops.app.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.lifeops.app.data.model.CustomPalette
import com.lifeops.app.data.model.ThemePreset
import com.lifeops.app.ui.theme.parseColor
import com.operations.suite.ui.pickers.SuiteColorField

/**
 * What the app looks like: the theme, the shipped presets, and the custom palette you can
 * build instead of one.
 */

@Composable
internal fun ThemeSection(
    selectedPreset: ThemePreset,
    isDarkMode: Boolean,
    customPalette: CustomPalette,
    onPresetSelect: (ThemePreset) -> Unit,
    onDarkModeToggle: (Boolean) -> Unit,
    onCustomPaletteChange: (CustomPalette) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (isDarkMode) Icons.Default.DarkMode else Icons.Default.LightMode,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    if (isDarkMode) "Dark mode" else "Light mode",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Switch(checked = isDarkMode, onCheckedChange = onDarkModeToggle)
            }
            HorizontalDivider()
            Text(
                "Color preset",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ThemePreset.entries.forEach { preset ->
                    val swatches = if (preset == ThemePreset.CUSTOM)
                        Triple(parseColor(customPalette.primary), parseColor(customPalette.secondary), parseColor(customPalette.tertiary))
                    else
                        presetSwatches[preset]
                    PresetCard(
                        preset = preset,
                        isSelected = selectedPreset == preset,
                        swatches = swatches,
                        onClick = { onPresetSelect(preset) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            if (selectedPreset == ThemePreset.CUSTOM) {
                HorizontalDivider()
                Text(
                    "Custom colors",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                CustomPaletteEditor(palette = customPalette, onChange = onCustomPaletteChange)
            }
        }
    }
}

@Composable
private fun CustomPaletteEditor(palette: CustomPalette, onChange: (CustomPalette) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ColorRow("Primary",    palette.primary)    { onChange(palette.copy(primary    = it)) }
        ColorRow("Secondary",  palette.secondary)  { onChange(palette.copy(secondary  = it)) }
        ColorRow("Tertiary",   palette.tertiary)   { onChange(palette.copy(tertiary   = it)) }
        ColorRow("Dark BG",    palette.darkBackground)  { onChange(palette.copy(darkBackground  = it)) }
        ColorRow("Light BG",   palette.lightBackground) { onChange(palette.copy(lightBackground = it)) }
    }
}

@Composable
private fun ColorRow(label: String, hexValue: String, onValidHex: (String) -> Unit) {
    SuiteColorField(label = label, color = hexValue, onColorChange = onValidHex)
}

@Composable
private fun PresetCard(
    preset: ThemePreset,
    isSelected: Boolean,
    swatches: Triple<Color, Color, Color>?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (swatches == null) return
    OutlinedCard(
        onClick = onClick,
        modifier = modifier,
        border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                 else BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Box(modifier = Modifier.size(14.dp).background(swatches.first, shape = CircleShape))
                Box(modifier = Modifier.size(14.dp).background(swatches.second, shape = CircleShape))
                Box(modifier = Modifier.size(14.dp).background(swatches.third, shape = CircleShape))
            }
            Text(
                preset.displayName,
                style = MaterialTheme.typography.labelSmall,
                color = if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface
            )
            if (isSelected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(12.dp)
                )
            } else {
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
internal fun ColorSwatchPicker(selectedColor: String, onSelect: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        com.lifeops.app.util.aspectColorPalette.forEach { hex ->
            val isSelected = hex.equals(selectedColor, ignoreCase = true)
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(parseColor(hex), shape = CircleShape)
                    .then(
                        if (isSelected)
                            Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, shape = CircleShape)
                        else Modifier
                    )
                    .clickable { onSelect(hex) }
            ) {}
        }
    }
}
