package com.citation.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.citation.core.note.HighlightColor
import com.citation.core.reader.ParagraphSpacing
import com.citation.core.reader.ReaderColorRole
import com.citation.core.reader.ReaderFont
import com.citation.core.reader.ReaderFontNames
import com.citation.core.reader.ReaderPalette
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
    fonts: List<ReaderFont>,
    canScopeToBook: Boolean,
    onSettings: ((ReaderSettings) -> ReaderSettings) -> Unit,
    onPerBook: (Boolean) -> Unit,
    onPickFont: () -> Unit,
    onRenameFont: (String, String) -> Unit,
    onDeleteFont: (String) -> Unit,
    onDismiss: () -> Unit
) {
    // A font the reader is renaming, and one they have asked to remove. Held here rather than inside
    // the rows so the dialog outlives the row that opened it — a rename that renumbered the sorted
    // list under its own dialog would be an odd way to lose your typing.
    var renaming by remember { mutableStateOf<ReaderFont?>(null) }
    var deleting by remember { mutableStateOf<ReaderFont?>(null) }

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
                        fonts.firstOrNull { it.path == settings.customFontPath }?.name
                            ?: "Add a .ttf or .otf — a dyslexia face, or one you prefer.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                TextButton(onClick = onPickFont) { Text("Add") }
            }

            // A list rather than a row of chips, because each font now has things you can do *to*
            // it as well as with it, and an action hidden behind a long-press on a chip is an action
            // nobody finds.
            fonts.forEach { font ->
                FontRow(
                    font = font,
                    selected = settings.customFontPath == font.path &&
                        settings.typeface == ReaderTypeface.CUSTOM,
                    onUse = {
                        onSettings { it.copy(typeface = ReaderTypeface.CUSTOM, customFontPath = font.path) }
                    },
                    onRename = { renaming = font },
                    onDelete = { deleting = font }
                )
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
            // Said rather than discovered: the three options differ by two marks that are easy to
            // mix up in a chip label, and "Both" is the one nobody goes looking for.
            Text(
                when (settings.paragraphs) {
                    ParagraphSpacing.INDENT -> "First line indented, and nothing between."
                    ParagraphSpacing.SPACED -> "A blank line between, and no indent."
                    ParagraphSpacing.BOTH -> "A blank line between, and the first line indented."
                },
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.secondary
            )
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

            // The theme is where the page *starts*; the colours below either follow it or are the
            // reader's own. Two choices rather than one, because wanting the page to follow the
            // system into dark mode and wanting the prose in a colour of your own are not the same
            // wish, and a single "Custom" theme made you give up the first to have the second.
            val systemPage = MaterialTheme.colorScheme.background.toArgb()
            val systemInk = MaterialTheme.colorScheme.onBackground.toArgb()
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

            // What the page is about to be drawn in, with warmth taken out: every row and the
            // preview warm what they are given, and warmth should be seen in one place rather than
            // applied twice over. The app's own colours stand in wherever the theme is System, so a
            // row that says "Auto" shows the colour that is actually on screen rather than a guess.
            val shown = ReaderPalette.colors(settings.copy(warmth = 0f), systemPage, systemInk)
            // The whole palette in one switch, because alternating between the app's colours and
            // your own is how anybody decides they like them, and it has to stay one tap each way.
            // Which roles are yours and what colour each one is both survive being switched off, so
            // this is an A/B rather than a decision; switching it on for the first time takes over
            // the page and the text in the colours already on screen, which is what the old
            // "Custom" theme did and the reason it read as an adjustment rather than a blank page.
            SwitchRow(
                label = "My own colours",
                caption = if (settings.useCustomColors) {
                    "Off puts the page back to ${settings.theme.label}, and keeps these."
                } else if (settings.hasOwnColors) {
                    "The page is ${settings.theme.label}'s. Your colours are kept, ready to come back."
                } else {
                    "Set the page, text, headings or links yourself."
                },
                checked = settings.useCustomColors
            ) { on ->
                onSettings { it.usingCustomColors(on, page = shown.page, text = shown.text) }
            }
            if (settings.useCustomColors) {
                ColourRole(
                    role = ReaderColorRole.PAGE,
                    settings = settings,
                    shown = shown.page,
                    swatches = ReaderPalette.PAGE_SWATCHES,
                    autoCaption = if (settings.theme == ReaderTheme.SYSTEM) {
                        "Follows the app, light and dark."
                    } else {
                        "Follows ${settings.theme.label}."
                    },
                    onSettings = onSettings
                )
                ColourRole(
                    role = ReaderColorRole.TEXT,
                    settings = settings,
                    shown = shown.text,
                    swatches = ReaderPalette.TEXT_SWATCHES,
                    autoCaption = if (settings.theme == ReaderTheme.SYSTEM) {
                        "Follows the app, light and dark."
                    } else {
                        "Follows ${settings.theme.label}."
                    },
                    onSettings = onSettings
                )
                // Headings and links are their own roles because a book is not one colour of text: both
                // used to be painted in the app's own accent, which is a colour nobody reading chose and
                // the reason a chapter full of anchors came out purple over a page somebody had set.
                // Left alone, a heading follows the prose — the printed convention — and a link is
                // fitted to the page it lands on.
                ColourRole(
                    role = ReaderColorRole.HEADING,
                    settings = settings,
                    shown = shown.heading,
                    swatches = ReaderPalette.TEXT_SWATCHES,
                    autoCaption = "Set in the text colour.",
                    onSettings = onSettings
                )
                ColourRole(
                    role = ReaderColorRole.LINK,
                    settings = settings,
                    shown = shown.link,
                    swatches = ReaderPalette.LINK_SWATCHES,
                    autoCaption = "Fitted to the page.",
                    onSettings = onSettings
                )
                // Under the System theme the preview includes the app's own page, which is the pairing
                // most worth seeing: text chosen against a light page is the text that disappears when
                // the system turns dark.
                ColourPreview(
                    page = shown.page,
                    ink = shown.text,
                    heading = shown.heading,
                    link = shown.link,
                    warmth = settings.warmth,
                    followsSystem = settings.theme == ReaderTheme.SYSTEM &&
                        !settings.customizes(ReaderColorRole.PAGE)
                )
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

            // The colour a new highlight is made in. Here rather than at capture because marking a
            // passage has to stay one gesture: you file in the colour you are working in, and
            // recolour the odd one from the note itself.
            ChoiceRow("Highlights") {
                HighlightColor.entries.forEach { color ->
                    Box(
                        Modifier
                            .padding(end = 10.dp)
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(Color(color.tint))
                            .border(
                                width = if (settings.highlightColor == color) 3.dp else 1.dp,
                                color = if (settings.highlightColor == color) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant
                                },
                                shape = CircleShape
                            )
                            .selectable(
                                selected = settings.highlightColor == color,
                                role = Role.RadioButton
                            ) { onSettings { it.copy(highlightColor = color) } }
                            .semantics { contentDescription = color.label }
                    )
                }
            }

            // Kindle and O'Reilly are read inside their own web readers, because the content is
            // licensed and Citation caches none of it. Without this the whole sheet above applies to
            // one of four reading tracks. It is a switch rather than a certainty because those are
            // other people's readers: they change without notice, and a reader looking at a page
            // Citation has made worse needs a way to stop it.
            SwitchRow(
                label = "Use these in Kindle and O'Reilly",
                caption = "Colours, spacing and your font, carried into the readers they host.",
                checked = settings.styleReadInPlace
            ) { on -> onSettings { it.copy(styleReadInPlace = on) } }

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

    renaming?.let { font ->
        RenameFontDialog(
            font = font,
            onSave = { name -> onRenameFont(font.path, name); renaming = null },
            onDismiss = { renaming = null }
        )
    }
    deleting?.let { font ->
        RemoveFontDialog(
            font = font,
            onRemove = { onDeleteFont(font.path); deleting = null },
            onDismiss = { deleting = null }
        )
    }
}

/** One of the reader's fonts: tap the row to read in it, or rename or remove it. */
@Composable
private fun FontRow(
    font: ReaderFont,
    selected: Boolean,
    onUse: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        // `selectable` on the row with a null-handler button, rather than a click on each: it makes
        // the whole row the target, and it stops a screen reader announcing the button and the row
        // as two separate controls that do the same thing.
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onUse)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(
            font.name,
            Modifier.weight(1f),
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        IconButton(onClick = onRename) {
            Icon(Icons.Default.Edit, contentDescription = "Rename ${font.name}")
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = "Remove ${font.name}")
        }
    }
}

/**
 * Give a font a name.
 *
 * Renaming matters more here than it looks: a stored font is named on disk by a digest of its bytes,
 * so without this a reader comparing three weights of one family is choosing between three strings
 * of hex. The picked file's name is only a first guess — `Atkinson-Hyperlegible-Regular-102a` is
 * what a download is called, not what its reader calls it.
 */
@Composable
private fun RenameFontDialog(font: ReaderFont, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember(font.path) { mutableStateOf(font.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename font") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { if (it.length <= ReaderFontNames.MAX_LENGTH) name = it },
                singleLine = true,
                label = { Text("Name") },
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onSave(name) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * Confirm removing a font.
 *
 * Worth confirming rather than undoing: Citation keeps its own copy precisely so a book does not
 * change face when a downloads folder is cleaned, which means this copy may well be the last one
 * left. The dialog says what happens to books already set in it instead of leaving that to be
 * discovered.
 */
@Composable
private fun RemoveFontDialog(font: ReaderFont, onRemove: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove ${font.name}?") },
        text = {
            Text(
                "Citation keeps its own copy of a font you add, so this removes it from the app " +
                    "entirely. Books set in it go back to sans until you add the file again."
            )
        },
        confirmButton = { TextButton(onClick = onRemove) { Text("Remove") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep") } }
    )
}

/**
 * One colour of the page: whether it follows the theme or is the reader's own, and — once it is
 * theirs — what it is.
 *
 * The choice comes first and the picker only appears under it, because the two questions are
 * genuinely separate and the first one is the one most readers answer. It is also what keeps the
 * sheet readable: four colour pickers permanently open is a wall, and a reader who wants the page to
 * follow the system and the prose to be theirs should see that arrangement stated rather than
 * inferred from which fields are filled in.
 */
@Composable
private fun ColourRole(
    role: ReaderColorRole,
    settings: ReaderSettings,
    /** The colour this role is drawn in right now, theme's or the reader's. */
    shown: Int,
    swatches: List<Int>,
    /** What "Auto" means for this role, said rather than left to be discovered. */
    autoCaption: String,
    onSettings: ((ReaderSettings) -> ReaderSettings) -> Unit
) {
    val custom = settings.customizes(role)
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(Color(shown))
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                .semantics { contentDescription = "${role.label} is ${ReaderPalette.hex(shown)}" }
        )
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(role.label, fontSize = 14.sp)
            if (!custom) {
                Text(autoCaption, fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
            }
        }
        // Taking a role back starts from the colour it was last given, or from the one on screen if
        // it never had one — an adjustment to the page they were looking at rather than a fresh
        // problem to solve. Handing it to the theme keeps that colour for the next time.
        Choice("Auto", !custom) { onSettings { it.withoutCustom(role) } }
        Spacer(Modifier.width(8.dp))
        Choice("Custom", custom) {
            onSettings { it.withCustom(role, it.held(role) ?: shown) }
        }
    }
    if (custom) {
        ColourRow(
            label = role.label,
            colour = settings.custom(role) ?: shown,
            swatches = swatches
        ) { picked -> onSettings { it.withCustom(role, picked) } }
    }
}

/**
 * Choosing one colour: a swatch strip for the common answers, a hex field for the exact one.
 *
 * Both, rather than either, because they answer different questions. The strip is for a reader
 * trying tints to see which is easiest on their eyes — that is a comparison, and it wants to be one
 * tap. The field is for a reader who arrives already knowing the value, from an overlay they own or
 * a colour someone recommended, and for whom hunting it down on a gradient would be guesswork.
 */
@Composable
private fun ColourRow(
    label: String,
    colour: Int,
    swatches: List<Int>,
    onPick: (Int) -> Unit
) {
    // The field holds what has been typed, not the current colour, so a half-finished code like "#3f"
    // does not repaint the page — or get rewritten under the cursor — between keystrokes. It resyncs
    // only when the colour changes from somewhere else, which is what tapping a swatch is.
    var typed by remember { mutableStateOf(ReaderPalette.hex(colour)) }
    LaunchedEffect(colour) {
        if (ReaderPalette.parseHex(typed) != colour) typed = ReaderPalette.hex(colour)
    }
    val typedIsColour = ReaderPalette.parseHex(typed) != null

    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            OutlinedTextField(
                value = typed,
                onValueChange = { entry ->
                    typed = entry
                    ReaderPalette.parseHex(entry)?.let(onPick)
                },
                singleLine = true,
                isError = !typedIsColour,
                textStyle = TextStyle(fontSize = 14.sp),
                placeholder = { Text("#RRGGBB", fontSize = 14.sp) },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Ascii,
                    capitalization = KeyboardCapitalization.Characters
                ),
                modifier = Modifier
                    .width(160.dp)
                    .semantics { contentDescription = "$label colour, as a hex code" }
            )
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            swatches.forEach { swatch ->
                val chosen = swatch == colour
                Box(
                    Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(Color(swatch))
                        .border(
                            width = if (chosen) 3.dp else 1.dp,
                            color = if (chosen) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            },
                            shape = CircleShape
                        )
                        .clickable { onPick(swatch) }
                        .semantics { contentDescription = "$label ${ReaderPalette.hex(swatch)}" }
                )
            }
        }
    }
}

/**
 * The chosen colours, shown as a page rather than as two dots.
 *
 * The sheet covers most of the book while it is open, so without this a reader is picking colours
 * against a preview they cannot see. It shows the *warmed* colours — what will actually be on screen
 * — and says so when the pair has fallen below what is comfortable to read for an hour, which is
 * almost always a slip rather than a preference, and cheaper to catch here than after the sheet
 * closes over a page of text that has gone.
 */
@Composable
private fun ColourPreview(
    page: Int,
    ink: Int,
    heading: Int,
    link: Int,
    warmth: Float,
    /** The page shown is the app's own and will change with it — see below. */
    followsSystem: Boolean = false
) {
    val shownPage = ReaderPalette.warm(page, warmth)
    val shownInk = ReaderPalette.warm(ink, warmth)
    val shownHeading = ReaderPalette.warm(heading, warmth)
    val shownLink = ReaderPalette.warm(link, warmth)
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Color(shownPage))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                .padding(14.dp)
        ) {
            // A heading over prose over a link, because that is the arrangement the colours have to
            // work in — three swatches side by side would look fine and still read badly as a page.
            Column {
                Text(
                    "A chapter heading",
                    color = Color(shownHeading),
                    fontSize = 19.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "This is how a page will read in the colours you have chosen.",
                    color = Color(shownInk),
                    fontSize = 15.sp,
                    modifier = Modifier.padding(top = 6.dp)
                )
                Text(
                    "and this is a link",
                    color = Color(shownLink),
                    fontSize = 15.sp,
                    textDecoration = TextDecoration.Underline
                )
            }
        }
        if (!ReaderPalette.isLegible(shownInk, shownPage)) {
            Text(
                "These two are close in brightness — text this low in contrast is hard to read for long.",
                color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
        // The one thing a preview cannot show: the page it is drawn on is the app's, and the app
        // follows the system. A colour picked against the light page is the colour that vanishes at
        // sunset, and the reader is the only one who can decide whether that matters to them.
        if (followsSystem) {
            Text(
                "The page follows the app, so this is how it reads in the mode you are in now. " +
                    "Set the page too if you want the same pair in light and dark.",
                color = MaterialTheme.colorScheme.secondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 6.dp)
            )
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
