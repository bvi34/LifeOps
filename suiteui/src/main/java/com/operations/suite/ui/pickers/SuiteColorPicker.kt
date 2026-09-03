package com.operations.suite.ui.pickers

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.operations.suitekit.SuiteColors
import com.operations.suitekit.SuiteHsl
import com.operations.suitekit.SuiteSwatches

/**
 * The suite's colour picker — the only one. Every app that lets somebody choose a colour (an aspect
 * ring, a person's dot, an asset's tag, the sandbox's own accent) opens this.
 *
 * It is a **visual** picker on purpose: a curated palette for the nine times out of ten where any
 * distinct colour will do, and hue / saturation / lightness sliders under it for the tenth. Nobody
 * types a hex code. The value still travels as `#RRGGBB` text, because that is what the apps store
 * and what the backup format carries — but [onColorChange] is only ever handed a clean, opaque one,
 * whatever state the field was in when the dialog opened.
 */

/** A stored `#RRGGBB` string as a Compose colour, falling back rather than throwing. */
fun suiteColor(hex: String?): Color = Color(SuiteColors.parseHex(hex).toInt())

/**
 * A tappable colour row: the current swatch, a label, and the hex it resolves to. Tapping opens
 * [SuiteColorPickerDialog].
 */
@Composable
fun SuiteColorField(
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
                .background(suiteColor(color))
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), CircleShape)
        )
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            SuiteHsl.normalizeHex(color),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }

    if (showDialog) {
        SuiteColorPickerDialog(
            initial = color,
            onConfirm = { onColorChange(it); showDialog = false },
            onDismiss = { showDialog = false }
        )
    }
}

/**
 * The picker itself: palette swatches, hue / saturation / lightness sliders, and a live preview of
 * what the sliders currently say.
 *
 * A swatch tap sets the hex *exactly* rather than round-tripping it through the sliders, so a
 * palette colour stored today still matches the same palette colour stored a year ago — the HSL
 * conversion is lossy in the last bit and a drifting `#43A047` is a colour that stops looking chosen.
 */
@Composable
fun SuiteColorPickerDialog(
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    title: String = "Pick a color",
    swatches: List<String> = SuiteSwatches.PALETTE
) {
    val initialHsl = remember(initial) { SuiteHsl.hexToHsl(initial) }
    var hue by remember(initial) { mutableStateOf(initialHsl.h.toFloat()) }
    var sat by remember(initial) { mutableStateOf(initialHsl.s.toFloat()) }
    var light by remember(initial) { mutableStateOf(initialHsl.l.toFloat()) }
    var hex by remember(initial) { mutableStateOf(SuiteHsl.normalizeHex(initial)) }

    fun recompute() {
        hex = SuiteHsl.hslToHex(hue.toDouble(), sat.toDouble(), light.toDouble())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(suiteColor(hex)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        hex,
                        style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                        // Read off the preview colour itself, not the lightness slider: a swatch tap
                        // sets the hex without moving the sliders.
                        color = Color(SuiteColors.contrastOn(SuiteColors.parseHex(hex)).toInt())
                    )
                }

                SuiteSwatchGrid(
                    swatches = swatches,
                    selected = hex,
                    onSelect = { picked ->
                        val h = SuiteHsl.hexToHsl(picked)
                        hue = h.h.toFloat(); sat = h.s.toFloat(); light = h.l.toFloat()
                        hex = SuiteHsl.normalizeHex(picked)
                    }
                )

                SliderLabel("Hue")
                HueGradientBar()
                Slider(value = hue, onValueChange = { hue = it; recompute() }, valueRange = 0f..360f)

                SliderLabel("Saturation")
                Slider(value = sat, onValueChange = { sat = it; recompute() }, valueRange = 0f..1f)

                SliderLabel("Lightness")
                Slider(value = light, onValueChange = { light = it; recompute() }, valueRange = 0f..1f)
            }
        },
        confirmButton = { Button(onClick = { onConfirm(hex) }) { Text("Select") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * The palette on its own, for a screen that offers colours inline rather than behind a dialog.
 */
@Composable
fun SuiteSwatchGrid(
    swatches: List<String> = SuiteSwatches.PALETTE,
    selected: String? = null,
    perRow: Int = 6,
    onSelect: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        swatches.chunked(perRow).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { swatch ->
                    val isSelected = selected != null && swatch.equals(selected, ignoreCase = true)
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(suiteColor(swatch))
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
