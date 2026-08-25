package com.citation.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.citation.app.ui.reader.fontDisplayName
import com.citation.core.reader.ParagraphSpacing
import com.citation.core.reader.ReaderSettings
import com.citation.core.reader.ReaderTheme
import com.citation.core.reader.ReaderTypeface
import com.citation.core.reader.ScreenOrientation

/**
 * Everything about how the book looks and how the screen behaves while you read it.
 *
 * Grouped into sections rather than one long list because the settings answer different questions —
 * how the type is set, how the page is coloured, what the screen does — and a reader looking for
 * one of them should not have to scan all of them. The sheet scrolls: there is genuinely a lot here,
 * and hiding half of it behind an "advanced" fold would only mean nobody finds hyphenation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DisplaySheet(
    settings: ReaderSettings,
    perBook: Boolean,
    fonts: List<String>,
    canScopeToBook: Boolean,
    onSettings: ((ReaderSettings) -> ReaderSettings) -> Unit,
    onPerBook: (Boolean) -> Unit,
    onPickFont: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Text("Display", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)

            if (canScopeToBook) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Just this book")
                        Text(
                            if (perBook) {
                                "This book keeps its own settings."
                            } else {
                                "Following your usual settings."
                            },
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                    Switch(checked = perBook, onCheckedChange = onPerBook)
                }
                Divider(Modifier.padding(vertical = 12.dp))
            }

            // --- Type ---------------------------------------------------------------------
            Section("Type")
            LabeledSlider("Text size", settings.fontSize, 10f..36f) { v ->
                onSettings { it.copy(fontSize = v) }
            }
            LabeledSlider("Line spacing", settings.lineSpacing, 1.0f..2.6f) { v ->
                onSettings { it.copy(lineSpacing = v) }
            }
            LabeledSlider("Margins", settings.marginDp, 0f..72f) { v ->
                onSettings { it.copy(marginDp = v) }
            }
            LabeledSlider("Letter spacing", settings.letterSpacing, -0.05f..0.4f) { v ->
                onSettings { it.copy(letterSpacing = v) }
            }

            ChoiceRow("Typeface") {
                ReaderTypeface.entries.forEach { face ->
                    // "Your font" only appears once there is one, so it can never be a dead end.
                    if (face != ReaderTypeface.CUSTOM || fonts.isNotEmpty() || settings.customFontPath != null) {
                        Choice(face.label, settings.typeface == face) {
                            onSettings { it.copy(typeface = face) }
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                }
            }

            // Citation ships no font of its own: the faces readers ask for here — OpenDyslexic
            // above all — are ones it has no right to redistribute. Pointing at a file you already
            // have covers those, and a preferred serif, and a face for your book's script.
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Your own font", fontSize = 14.sp)
                    Text(
                        settings.customFontPath?.let { fontDisplayName(it) }
                            ?: "Add a .ttf or .otf — a dyslexia face, or one you prefer.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                TextButton(onClick = onPickFont) { Text("Add") }
            }

            if (fonts.size > 1 || (fonts.size == 1 && settings.customFontPath != fonts.firstOrNull())) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    fonts.forEach { path ->
                        Choice(fontDisplayName(path), settings.customFontPath == path) {
                            onSettings { it.copy(typeface = ReaderTypeface.CUSTOM, customFontPath = path) }
                        }
                    }
                }
            }

            // --- Setting ------------------------------------------------------------------
            Divider(Modifier.padding(vertical = 12.dp))
            Section("Setting")
            ChoiceRow("Paragraphs") {
                ParagraphSpacing.entries.forEach { mode ->
                    Choice(mode.label, settings.paragraphs == mode) {
                        onSettings { it.copy(paragraphs = mode) }
                    }
                    Spacer(Modifier.width(8.dp))
                }
            }
            SwitchRow(
                label = "Justify",
                // Stated rather than left to be discovered: justification on a narrow column without
                // hyphenation is what opens rivers of whitespace down a page.
                caption = if (settings.justify && !settings.hyphenate) {
                    "Works best with hyphenation on."
                } else {
                    "Straight edges on both sides."
                },
                checked = settings.justify
            ) { on -> onSettings { it.copy(justify = on) } }
            SwitchRow(
                label = "Hyphenate",
                caption = "Breaks long words so lines even out.",
                checked = settings.hyphenate
            ) { on -> onSettings { it.copy(hyphenate = on) } }

            ChoiceRow("Reading") {
                Choice("Paged", settings.paged) { onSettings { it.copy(paged = true) } }
                Spacer(Modifier.width(8.dp))
                Choice("Scroll", !settings.paged) { onSettings { it.copy(paged = false) } }
            }

            // --- Colour -------------------------------------------------------------------
            Divider(Modifier.padding(vertical = 12.dp))
            Section("Colour")
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ReaderTheme.entries.forEach { theme ->
                    Choice(theme.label, settings.theme == theme) {
                        onSettings { it.copy(theme = theme) }
                    }
                }
            }
            if (settings.theme == ReaderTheme.NIGHT) {
                SwitchRow(
                    label = "True black",
                    caption = "Switches OLED pixels off entirely.",
                    checked = settings.trueBlack
                ) { on -> onSettings { it.copy(trueBlack = on) } }
            }
            LabeledSlider("Warmth", settings.warmth, 0f..1f) { v ->
                onSettings { it.copy(warmth = v) }
            }

            // Brightness here is a window attribute: it applies while the reader is up and never
            // touches the device's own setting, which is what makes turning it right down safe.
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Brightness", Modifier.weight(1f), fontSize = 14.sp)
                TextButton(
                    onClick = {
                        onSettings {
                            it.copy(
                                brightness = if (it.followsSystemBrightness) 0.6f else ReaderSettings.SYSTEM_BRIGHTNESS
                            )
                        }
                    }
                ) {
                    Text(if (settings.followsSystemBrightness) "Set here" else "Follow system")
                }
            }
            if (!settings.followsSystemBrightness) {
                LabeledSlider("", settings.brightness, 0.01f..1f) { v ->
                    onSettings { it.copy(brightness = v) }
                }
            }

            // --- Screen -------------------------------------------------------------------
            Divider(Modifier.padding(vertical = 12.dp))
            Section("Screen")
            SwitchRow(
                label = "Keep screen on",
                caption = "While a book is open.",
                checked = settings.keepAwake
            ) { on -> onSettings { it.copy(keepAwake = on) } }
            SwitchRow(
                label = "Full screen",
                caption = "Hides the system bars; swipe from an edge to bring them back.",
                checked = settings.immersive
            ) { on -> onSettings { it.copy(immersive = on) } }
            SwitchRow(
                label = "Volume keys turn pages",
                caption = if (settings.volumeKeysReversed) "Volume up goes forward." else "Volume down goes forward.",
                checked = settings.volumeKeyTurns
            ) { on -> onSettings { it.copy(volumeKeyTurns = on) } }
            if (settings.volumeKeyTurns) {
                SwitchRow(
                    label = "Reverse them",
                    caption = null,
                    checked = settings.volumeKeysReversed
                ) { on -> onSettings { it.copy(volumeKeysReversed = on) } }
            }
            ChoiceRow("Orientation") {
                ScreenOrientation.entries.forEach { orientation ->
                    Choice(orientation.label, settings.orientation == orientation) {
                        onSettings { it.copy(orientation = orientation) }
                    }
                    Spacer(Modifier.width(8.dp))
                }
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.secondary,
        modifier = Modifier.padding(bottom = 4.dp)
    )
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit
) {
    Column(Modifier.padding(top = 4.dp)) {
        if (label.isNotBlank()) {
            Text(label, color = MaterialTheme.colorScheme.secondary, fontSize = 13.sp)
        }
        Slider(value = value.coerceIn(range), onValueChange = onChange, valueRange = range)
    }
}

@Composable
private fun ChoiceRow(label: String, content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, Modifier.width(96.dp), fontSize = 14.sp)
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically
        ) { content() }
    }
}

@Composable
private fun SwitchRow(label: String, caption: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 14.sp)
            caption?.let {
                Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick) { Text(label, maxLines = 1) }
    } else {
        OutlinedButton(onClick = onClick) { Text(label, maxLines = 1) }
    }
}
