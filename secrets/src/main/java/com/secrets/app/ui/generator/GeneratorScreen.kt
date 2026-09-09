package com.secrets.app.ui.generator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.operations.vaultkit.GeneratedSecret
import com.operations.vaultkit.PasswordGenerator
import com.operations.vaultkit.PasswordRecipe
import com.secrets.app.data.SecretsPrefs
import com.secrets.app.ui.common.SecretClipboard

/**
 * The generator, on its own screen.
 *
 * It is a tab rather than a dialog inside the editor because half its use has nothing to do with an
 * item in this vault: somebody is filling in a sign-up form in another app and needs a password to
 * paste, and having to create a vault item first in order to reach the generator is the kind of
 * friction that ends with `Summer2024!`.
 *
 * Both shapes are here — characters and words — and the entropy is shown for both, in bits and as a
 * length of time, because those two numbers are the only honest way to compare a sixteen-character
 * jumble with five English words. (Spoiler: at these defaults the words win, and they can be read
 * aloud.)
 */
@Composable
fun GeneratorScreen(prefs: SecretsPrefs) {
    val context = LocalContext.current

    var words by remember { mutableStateOf(false) }
    var length by remember { mutableStateOf(20f) }
    var wordCount by remember { mutableStateOf(5f) }
    var upper by remember { mutableStateOf(true) }
    var digits by remember { mutableStateOf(true) }
    var symbols by remember { mutableStateOf(true) }
    var unambiguous by remember { mutableStateOf(false) }
    var capitalise by remember { mutableStateOf(false) }
    var appendNumber by remember { mutableStateOf(false) }
    var nonce by remember { mutableStateOf(0) }

    val recipe = PasswordRecipe(
        length = length.toInt(),
        upper = upper,
        digits = digits,
        symbols = symbols,
        avoidAmbiguous = unambiguous
    )

    // `nonce` is in the key so that pressing refresh with every other input unchanged still produces
    // a new password — which is the entire purpose of a refresh button on a generator.
    val generated: GeneratedSecret = remember(words, recipe, wordCount, capitalise, appendNumber, nonce) {
        if (words) {
            PasswordGenerator.passphrase(
                words = wordCount.toInt(),
                capitalise = capitalise,
                appendNumber = appendNumber
            )
        } else {
            PasswordGenerator.password(recipe)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = generated.value,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "${generated.entropyBits.toInt()} bits · " +
                        "${com.operations.vaultkit.SecretStrength.crackTime(generated.entropyBits)} to guess",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = {
                        SecretClipboard.copy(context, "Generated", generated.value, prefs.clipboardClearSeconds)
                    }) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Copy")
                    }
                    IconButton(onClick = { nonce++ }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Generate another")
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !words, onClick = { words = false }, label = { Text("Characters") })
            FilterChip(selected = words, onClick = { words = true }, label = { Text("Words") })
        }

        if (words) {
            Text("${wordCount.toInt()} words", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = wordCount,
                onValueChange = { wordCount = it },
                valueRange = PasswordGenerator.MIN_WORDS.toFloat()..8f,
                steps = 4
            )
            ToggleRow("Capitalise each word", capitalise) { capitalise = it }
            ToggleRow("Add a number (some forms insist)", appendNumber) { appendNumber = it }
            Text(
                text = "Drawn from ${com.operations.vaultkit.VaultWords.list.size} words, so each one " +
                    "is worth about ten bits. The list is in the app and public — the strength is in " +
                    "the dice, not the vocabulary.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Text("${length.toInt()} characters", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = length,
                onValueChange = { length = it },
                valueRange = 8f..64f
            )
            ToggleRow("Capitals", upper) { upper = it }
            ToggleRow("Digits", digits) { digits = it }
            ToggleRow("Symbols", symbols) { symbols = it }
            ToggleRow("Avoid look-alike characters", unambiguous) { unambiguous = it }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
