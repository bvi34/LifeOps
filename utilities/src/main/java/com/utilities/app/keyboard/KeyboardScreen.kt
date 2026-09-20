package com.utilities.app.keyboard

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.utilities.app.data.LexiconStore
import com.utilities.app.data.LookStore
import com.utilities.app.look.UtilityPalette
import com.utilities.app.look.UtilityTypeface
import com.utilities.app.look.rememberPalette
import com.utilities.app.look.toComposeColor
import com.utilities.app.ui.LookEditor
import com.utilities.app.ui.SwitchRow
import kotlin.math.roundToInt

/**
 * The keyboard's own screen: what it looks like, how it behaves, and — the part that earns the
 * whole app — exactly what it has remembered.
 *
 * The word list is shown rather than summarised. A keyboard that says "your data stays on your
 * device" and shows you nothing is making the same claim as every keyboard that does not; a
 * keyboard that prints the four hundred words it knows, with counts, and lets you delete any of
 * them, is making a claim you can check. That list is the screen's third section and it is not
 * buried under an "advanced" heading.
 */
@Composable
fun KeyboardScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val looks = remember { LookStore.get(context) }
    val lexicon = remember { LexiconStore.get(context) }
    val look by looks.keyboard.collectAsState()
    val words by lexicon.lexicon.collectAsState()
    val palette = rememberPalette(look.look)

    var confirmForget by remember { mutableStateOf(false) }

    // Picking a font file. `OpenDocument` rather than `GetContent`, because the persistable grant is
    // what lets the keyboard load the same file again next week — see the note on copying below.
    val pickFont = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val path = com.utilities.app.data.FontFiles.copyIn(context, uri)
            if (path != null) {
                looks.updateKeyboard { it.copy(look = it.look.copy(typeface = UtilityTypeface.CUSTOM, fontPath = path)) }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        LookEditor(
            look = look.look,
            palette = palette,
            onChange = { next -> looks.updateKeyboard { it.copy(look = next) } },
            matchLabel = "Use the message thread's colours",
            onMatch = { looks.matchKeyboardToChat() },
            preview = { colours -> KeyboardPreview(colours) }
        )

        if (look.look.typeface == UtilityTypeface.CUSTOM) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { pickFont.launch(arrayOf("font/*", "application/x-font-ttf", "application/octet-stream")) }) {
                Text(if (look.look.fontPath == null) "Choose a font file" else "Choose a different font file")
            }
        }

        Spacer(Modifier.height(24.dp))
        Heading("The keys")

        LabelledSlider(
            label = "Height",
            value = look.heightScale,
            range = 0.7f..1.6f,
            reading = "${(look.heightScale * 100).roundToInt()}%",
            onChange = { value -> looks.updateKeyboard { it.copy(heightScale = value) } }
        )
        Spacer(Modifier.height(4.dp))
        LabelledSlider(
            label = "Long press",
            value = look.longPressMs.toFloat(),
            range = 150f..800f,
            reading = "${look.longPressMs}ms",
            onChange = { value -> looks.updateKeyboard { it.copy(longPressMs = value.roundToInt()) } }
        )
        Spacer(Modifier.height(8.dp))
        SwitchRow(
            title = "Number row",
            detail = "A row of digits across the top. Costs a fifth of the keyboard's height.",
            checked = look.numberRow,
            onChange = { value -> looks.updateKeyboard { it.copy(numberRow = value) } }
        )
        SwitchRow(
            title = "Key edges",
            detail = "Draw each cap as a key. Edgeless looks better and is harder to aim at.",
            checked = look.keyEdges,
            onChange = { value -> looks.updateKeyboard { it.copy(keyEdges = value) } }
        )
        SwitchRow(
            title = "Capitalise sentences",
            detail = null,
            checked = look.autoCapitalize,
            onChange = { value -> looks.updateKeyboard { it.copy(autoCapitalize = value) } }
        )
        SwitchRow(
            title = "Vibrate on a keypress",
            detail = null,
            checked = look.haptics,
            onChange = { value -> looks.updateKeyboard { it.copy(haptics = value) } }
        )

        Spacer(Modifier.height(24.dp))
        Heading("What it remembers")

        Text(
            "This keyboard suggests words it has seen you type. The list below is the whole of what " +
                "it knows: words and how often, no sentences, no order, nothing about who you were " +
                "writing to. It is a text file in this app's own folder, it is in the suite's " +
                "backup, and this module has no network permission, so there is nowhere for it to go.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Passwords, fields marked \"no suggestions\", and anything typed in a private browser " +
                "window are never learned from — those editors say so and the keyboard listens.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(12.dp))
        SwitchRow(
            title = "Suggest words",
            detail = "The strip above the keys.",
            checked = look.suggestions,
            onChange = { value -> looks.updateKeyboard { it.copy(suggestions = value) } }
        )
        SwitchRow(
            title = "Remember what I type",
            detail = "Turning this off empties the list as well as stopping it growing.",
            checked = look.learn,
            onChange = { value ->
                looks.updateKeyboard { it.copy(learn = value) }
                if (!value) lexicon.clear()
            }
        )

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                if (words.size == 0) "Nothing learned yet." else "${words.size} words",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            if (words.size > 0) {
                TextButton(onClick = { confirmForget = true }) { Text("Forget all") }
            }
        }

        // The top of the list only. Four thousand rows in a scrolling column is not a list anybody
        // reads, and the words that matter — the ones somebody is surprised to see — are the ones
        // typed most.
        words.words().take(LISTED).forEach { (word, count) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
            ) {
                Text(word, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(
                    count.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { lexicon.forget(word) }) { Text("Forget") }
            }
        }
        if (words.size > LISTED) {
            Text(
                "…and ${words.size - LISTED} more, least-typed first off the bottom.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("Forget every word?") },
            text = { Text("The suggestion strip starts from nothing again. Nothing else changes.") },
            confirmButton = {
                TextButton(onClick = { lexicon.clear(); confirmForget = false }) { Text("Forget them") }
            },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Keep them") } }
        )
    }
}

/** Three keys and a suggestion, in the colours currently chosen. */
@Composable
private fun KeyboardPreview(palette: UtilityPalette) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            "keyboard",
            style = MaterialTheme.typography.bodySmall,
            color = palette.muted.toComposeColor()
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            listOf("q", "w", "e", "r", "t").forEachIndexed { index, cap ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (index == 2) palette.accent.toComposeColor()
                            else com.utilities.app.look.UtilityPalettes
                                .mix(palette.surface, palette.text, if (palette.dark) 0.14f else 0.06f)
                                .toComposeColor()
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        cap,
                        color = (if (index == 2) palette.onAccent else palette.text).toComposeColor(),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 8.dp)
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
        androidx.compose.material3.Slider(
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

private const val LISTED = 60
