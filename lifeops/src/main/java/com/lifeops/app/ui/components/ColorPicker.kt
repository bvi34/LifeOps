package com.lifeops.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.lifeops.app.ui.theme.parseColor
import com.lifeops.app.util.GrowthColor
import com.lifeops.app.util.aspectColorPalette

/** Normalise any input to a clean 6-digit "#RRGGBB" string (also fixes 3-digit / invalid input). */
private fun normalizeHex(hex: String): String {
    val (r, g, b) = GrowthColor.hexToRgb(hex)
    return GrowthColor.rgbToHex(r, g, b)
}

/**
 * A tappable colour field: shows the current colour swatch + hex, and opens a visual
 * [ColorPickerDialog] when tapped. Replaces hand-typed hex entry. Always emits a valid
 * "#RRGGBB" string through [onColorChange].
 */
@Composable
fun ColorPickerField(
    label: String,
    color: String,
    onColorChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var showDialog by remember { mutableStateOf(false) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .clickable { showDialog = true }
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(parseColor(color))
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), CircleShape)
        )
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            normalizeHex(color),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }

    if (showDialog) {
        ColorPickerDialog(
            initial = color,
            onConfirm = { onColorChange(it); showDialog = false },
            onDismiss = { showDialog = false }
        )
    }
}

/**
 * Visual colour picker: quick-pick palette swatches plus hue / saturation / lightness sliders
 * with a live preview. No hex typing required. Built on the pure [GrowthColor] HSL helpers.
 */
@Composable
fun ColorPickerDialog(
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val initialHsl = remember(initial) { GrowthColor.hexToHsl(initial) }
    var hue by remember(initial) { mutableStateOf(initialHsl.h.toFloat()) }
    var sat by remember(initial) { mutableStateOf(initialHsl.s.toFloat()) }
    var light by remember(initial) { mutableStateOf(initialHsl.l.toFloat()) }
    // Source of truth for the chosen colour. Sliders recompute it from HSL; swatch picks set it
    // exactly so preset hexes round-trip unchanged.
    var hex by remember(initial) { mutableStateOf(normalizeHex(initial)) }

    fun recompute() {
        hex = GrowthColor.hslToHex(hue.toDouble(), sat.toDouble(), light.toDouble())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pick a color") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Live preview
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(parseColor(hex)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        hex,
                        style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                        color = if (light > 0.55f) Color.Black.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.9f)
                    )
                }

                // Quick-pick palette
                ColorSwatchRow(selected = hex, onSelect = { picked ->
                    val h = GrowthColor.hexToHsl(picked)
                    hue = h.h.toFloat(); sat = h.s.toFloat(); light = h.l.toFloat()
                    hex = normalizeHex(picked)
                })

                SliderLabel("Hue")
                HueGradientBar()
                Slider(
                    value = hue,
                    onValueChange = { hue = it; recompute() },
                    valueRange = 0f..360f
                )

                SliderLabel("Saturation")
                Slider(
                    value = sat,
                    onValueChange = { sat = it; recompute() },
                    valueRange = 0f..1f
                )

                SliderLabel("Lightness")
                Slider(
                    value = light,
                    onValueChange = { light = it; recompute() },
                    valueRange = 0f..1f
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(hex) }) { Text("Select") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun SliderLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    )
}

@Composable
private fun HueGradientBar() {
    val hueColors = listOf(
        Color(0xFFFF0000), Color(0xFFFFFF00), Color(0xFF00FF00),
        Color(0xFF00FFFF), Color(0xFF0000FF), Color(0xFFFF00FF), Color(0xFFFF0000)
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Brush.horizontalGradient(hueColors))
    )
}

@Composable
private fun ColorSwatchRow(selected: String, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        aspectColorPalette.chunked(6).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { swatch ->
                    val isSelected = swatch.equals(selected, ignoreCase = true)
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(parseColor(swatch))
                            .border(
                                width = if (isSelected) 3.dp else 1.dp,
                                color = if (isSelected) MaterialTheme.colorScheme.onSurface
                                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                shape = CircleShape
                            )
                            .clickable { onSelect(swatch) }
                    )
                }
            }
        }
    }
}
