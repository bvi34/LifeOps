package com.utilities.app.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.operations.suite.ui.pickers.SuiteColorField
import com.operations.suitekit.SuiteColors
import com.utilities.app.look.UtilityLook
import com.utilities.app.look.UtilityPalette
import com.utilities.app.look.UtilityTheme
import com.utilities.app.look.UtilityTypeface
import com.utilities.app.look.toComposeColor
import kotlin.math.roundToInt

/**
 * The display sheet, and the reason Citation was named in the original ask.
 *
 * Citation's reader settings are the best screen in this suite, and not because there are a lot of
 * switches on it — because every one of them is a decision somebody defended out loud, and because
 * the four presets are covers for a page you can still take apart underneath them. That combination
 * is what makes it feel like a setting rather than a theme picker: you start with Sepia, you find it
 * a shade too yellow, and there is a slider right there.
 *
 * This is that screen, for the surfaces Utilities takes over. One composable, used by both the
 * keyboard's settings and the message thread's, because the moment there are two of them they start
 * disagreeing about what "warmth" means.
 *
 * [preview] is the caller's — a row of keys, a pair of bubbles — drawn above the controls in the
 * colours they currently produce. It is not decoration: warmth and a custom surface are both
 * settings whose effect nobody can predict from a number, and a preview is the difference between
 * choosing a colour and guessing at one.
 */
@Composable
fun LookEditor(
    look: UtilityLook,
    palette: UtilityPalette,
    onChange: (UtilityLook) -> Unit,
    modifier: Modifier = Modifier,
    /** The one button that matters on both screens: take the other surface's settings. */
    matchLabel: String? = null,
    onMatch: (() -> Unit)? = null,
    preview: @Composable (UtilityPalette) -> Unit
) {
    Column(modifier = modifier.fillMaxWidth()) {

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(palette.surface.toComposeColor())
                .border(1.dp, palette.line.toComposeColor(), RoundedCornerShape(16.dp))
                .padding(12.dp)
        ) {
            preview(palette)
        }

        Spacer(Modifier.height(20.dp))

        SectionLabel("Theme")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            UtilityTheme.entries.forEach { theme ->
                FilterChip(
                    selected = look.theme == theme,
                    onClick = { onChange(look.copy(theme = theme)) },
                    label = { Text(theme.label, style = MaterialTheme.typography.labelMedium) }
                )
            }
        }

        if (look.theme == UtilityTheme.NIGHT) {
            Spacer(Modifier.height(8.dp))
            SwitchRow(
                title = "True black",
                detail = "Switches the pixels off on an OLED panel instead of drawing them dark grey.",
                checked = look.trueBlack,
                onChange = { onChange(look.copy(trueBlack = it)) }
            )
        }

        if (look.theme == UtilityTheme.CUSTOM) {
            Spacer(Modifier.height(12.dp))
            ColourRow(
                label = "Surface",
                argb = look.surfaceColor,
                fallback = palette.surface,
                onPick = { onChange(look.copy(surfaceColor = it)) }
            )
            Spacer(Modifier.height(8.dp))
            ColourRow(
                label = "Text",
                argb = look.textColor,
                fallback = palette.text,
                onPick = { onChange(look.copy(textColor = it)) }
            )
        }

        Spacer(Modifier.height(8.dp))
        ColourRow(
            label = "Accent",
            argb = look.accentColor,
            fallback = palette.accent,
            onPick = { onChange(look.copy(accentColor = it)) }
        )
        Text(
            "A pressed key and a sent bubble. Moved toward the text colour if it cannot be read on " +
                "the surface you chose.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )

        Spacer(Modifier.height(20.dp))

        SectionLabel("Warmth")
        SliderRow(
            value = look.warmth,
            range = 0f..1f,
            reading = if (look.warmth <= 0f) "Off" else "${(look.warmth * 100).roundToInt()}%",
            onChange = { onChange(look.copy(warmth = it)) }
        )
        Text(
            "Cuts blue out of every colour rather than laying an orange sheet over them, so the " +
                "surface warms without losing contrast.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(20.dp))

        SectionLabel("Type")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            UtilityTypeface.entries.forEach { face ->
                FilterChip(
                    selected = look.typeface == face,
                    // A face nobody has supplied a file for is offered and says so when chosen,
                    // rather than being hidden: the point of the option is to discover it exists.
                    onClick = { onChange(look.copy(typeface = face)) },
                    label = { Text(face.label, style = MaterialTheme.typography.labelMedium) }
                )
            }
        }
        if (look.typeface == UtilityTypeface.CUSTOM) {
            Text(
                look.fontPath?.let { "Set in ${it.substringAfterLast('/')}." }
                    ?: "No font file chosen yet — pick one on this screen. Nothing is shipped: the " +
                    "faces people ask for here are not ours to redistribute.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        Spacer(Modifier.height(12.dp))
        LabelledSlider(
            label = "Text size",
            value = look.textScale,
            range = 0.7f..2.0f,
            reading = "${(look.textScale * 100).roundToInt()}%",
            onChange = { onChange(look.copy(textScale = it)) }
        )

        Spacer(Modifier.height(12.dp))
        LabelledSlider(
            label = "Roundness",
            value = look.cornerDp,
            range = 0f..32f,
            reading = "${look.cornerDp.roundToInt()}dp",
            onChange = { onChange(look.copy(cornerDp = it)) }
        )

        Spacer(Modifier.height(12.dp))
        LabelledSlider(
            label = "Air",
            value = look.paddingDp,
            range = -4f..12f,
            reading = when {
                look.paddingDp < -0.5f -> "Tighter"
                look.paddingDp > 0.5f -> "Roomier"
                else -> "Default"
            },
            onChange = { onChange(look.copy(paddingDp = it)) }
        )

        if (matchLabel != null && onMatch != null) {
            Spacer(Modifier.height(20.dp))
            Text(
                matchLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onMatch)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

/**
 * A colour, through the suite's own picker.
 *
 * The suite's picker speaks hex strings and the look speaks ARGB ints, which is the one seam
 * between them; converting here rather than changing either is deliberate — the picker is shared by
 * twelve apps, and the look's colours are stored as ints because that is what a `Canvas` and a
 * `Paint` want, and the keyboard's drawing is the hot path.
 */
@Composable
private fun ColourRow(label: String, argb: Int?, fallback: Int, onPick: (Int) -> Unit) {
    val current = argb ?: fallback
    SuiteColorField(
        label = label,
        color = SuiteColors.toHex(current.toLong() and 0xFFFFFFFFL),
        onColorChange = { hex -> onPick(SuiteColors.parseHex(hex).toInt()) }
    )
}

@Composable
private fun LabelledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    reading: String,
    onChange: (Float) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(88.dp))
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            reading,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(64.dp)
        )
    }
}

@Composable
private fun SliderRow(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    reading: String,
    onChange: (Float) -> Unit
) = LabelledSlider(label = "", value = value, range = range, reading = reading, onChange = onChange)

/** A switch with a sentence under it, which is how every setting in this app is offered. */
@Composable
fun SwitchRow(
    title: String,
    detail: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled) { onChange(!checked) }
            .padding(vertical = 6.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (detail != null) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}
