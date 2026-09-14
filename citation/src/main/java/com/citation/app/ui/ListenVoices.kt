package com.citation.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.citation.core.speech.CustomVoice
import com.citation.core.speech.EnginePreference
import com.citation.core.speech.SpeechSettings
import com.citation.core.speech.VoiceDraft
import com.citation.core.speech.VoiceModel
import com.citation.core.speech.VoiceQuality
import com.citation.core.speech.VoiceSource

/**
 * The voices reading aloud is done in: the ones installed, the ones added here, and the
 * dialog that defines a new one from model files.
 */

// --- Voice -------------------------------------------------------------------------------------

@Composable
internal fun VoiceSection(
    vm: ReaderViewModel,
    settings: SpeechSettings,
    downloading: Pair<String, Float>?
) {
    val installed by vm.installedVoices.collectAsStateWithLifecycle()
    val mine by vm.userVoices.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var adding by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text("Voice", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Text(
            if (vm.neuralVoicesSupported) {
                "Downloaded voices run entirely on this device: no account, no per-sentence cost, " +
                    "and nothing about what you read leaves the phone."
            } else {
                "This build has no neural voice runtime, so your device's own speech engine is " +
                    "used. It is the only engine that reports word boundaries, so it is also the " +
                    "one that can highlight word by word."
            },
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
        )

        // Without a runtime there is exactly one engine, so offering a choice between three would
        // be offering two ways to get silence.
        if (vm.neuralVoicesSupported) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                EnginePreference.entries.forEach { preference ->
                    FilterChip(
                        selected = settings.engine == preference,
                        onClick = { vm.updateSpeech { it.copy(engine = preference) } },
                        label = { Text(preference.label) }
                    )
                }
            }
        }

        if (installed.isNotEmpty()) {
            Text(
                "Installed",
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 12.dp)
            )
            installed.forEach { voice ->
                VoiceRow(
                    model = voice.model,
                    installed = true,
                    selected = settings.voiceId == voice.model.id,
                    downloadProgress = null,
                    onSelect = { vm.setVoice(voice.model.id) },
                    onInstall = {},
                    onDelete = { vm.deleteVoice(voice.model) }
                )
            }
            Text(
                "Using ${VoiceModel.megabytes(vm.voiceStorageBytes())}.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        val installedIds = installed.map { it.model.id }.toSet()
        // Nothing to download to. Offering sixty megabytes that no engine in this build can load
        // would be the worst kind of dead button: it works, it takes a long time, and then nothing
        // uses what it fetched.
        val available = if (vm.neuralVoicesSupported) {
            vm.voiceCatalog().filter { it.id !in installedIds }
        } else {
            emptyList()
        }
        if (available.isNotEmpty()) {
            Text(
                "Available to download",
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 12.dp)
            )
            available.forEach { model ->
                VoiceRow(
                    model = model,
                    installed = false,
                    selected = false,
                    downloadProgress = downloading?.takeIf { it.first == model.id }?.second,
                    onSelect = {},
                    onInstall = { vm.installVoice(model) },
                    onDelete = {}
                )
            }
        }

        // Voices the reader added whose files are not (yet) here: a download still running, or one
        // that failed. Listed rather than hidden, because the alternative to a retry button is
        // typing a sixty-character link again.
        val waiting = mine.filter { it.id !in installedIds }
        if (vm.neuralVoicesSupported) {
            Text(
                "Your voices",
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 16.dp)
            )
            Text(
                "The catalogue above is a handful of English narrators, not the limit. Any Piper " +
                    "voice packaged for this runtime works: give it a name and a link, or point at " +
                    "the two files if you already have them. It installs into the same place, is " +
                    "picked from the same list, and never leaves the device either.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(top = 4.dp)
            )
            waiting.forEach { model ->
                AddedVoiceRow(
                    model = model,
                    downloadProgress = downloading?.takeIf { it.first == model.id }?.second,
                    onInstall = { vm.installVoice(model) },
                    onDelete = { vm.deleteVoice(model) }
                )
            }
            OutlinedButton(onClick = { adding = true }, modifier = Modifier.padding(top = 8.dp)) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("Add a voice", modifier = Modifier.padding(start = 8.dp))
            }
        }
    }

    if (adding) {
        AddVoiceDialog(
            onDismiss = { adding = false },
            onAdd = { draft, model, tokens ->
                when (draft.source) {
                    is VoiceSource.Link -> vm.addVoiceFromLink(draft)
                    // The streams are opened here rather than in the ViewModel, which has no
                    // context: reading what the reader picked needs the resolver and the grant that
                    // came with the pick.
                    is VoiceSource.Device -> vm.addVoiceFromFiles(
                        draft = draft,
                        openTokens = { tokens?.uri?.let { context.contentResolver.openInputStream(it) } },
                        openWeights = { model?.uri?.let { context.contentResolver.openInputStream(it) } },
                        modelBytes = model?.bytes,
                        tokensBytes = tokens?.bytes
                    )
                }
            }
        )
    }
}

/**
 * A voice the reader added that has no files yet — the retry, and the way back out.
 *
 * Kept apart from [VoiceRow] because nothing about it is the same: it cannot be selected (there is
 * nothing to speak with), its size is unknown until something lands, and the useful thing to say
 * about it is where it is supposed to come from. A voice added from files on the device and then
 * lost has no link to retry, so it offers only the delete — and says why.
 */
@Composable
private fun AddedVoiceRow(
    model: VoiceModel,
    downloadProgress: Float?,
    onInstall: () -> Unit,
    onDelete: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(model.name)
                Text(model.summary, fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary)
                Text(
                    when {
                        downloadProgress != null -> "Downloading…"
                        model.modelUrl.isNotBlank() -> "Not downloaded — ${model.modelUrl}"
                        else -> "Its files are not here. Remove it and add it again from your files."
                    },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
            if (downloadProgress != null) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            } else if (model.modelUrl.isNotBlank()) {
                IconButton(onClick = onInstall) {
                    Icon(Icons.Filled.Download, contentDescription = "Download ${model.name}")
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Remove ${model.name}")
            }
        }
        downloadProgress?.let {
            LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        }
    }
}

/** A file the reader picked, with the two things a document provider will tell us about it. */
internal data class PickedFile(val uri: Uri, val name: String, val bytes: Long?)

/**
 * The form for a voice of the reader's own.
 *
 * Deliberately short. A Piper voice is two files and a label, and everything else a [VoiceModel]
 * carries is either derived (the id, from the name), reported by the runtime (the sample rate, from
 * the model it loaded), or measured once the file lands (the size). Asking for any of it would be
 * asking a reader to look up facts about a file to tell them to an app that is about to read the
 * file. The speaker number is the one exception, and only because nothing can infer it: a
 * multi-speaker model holds hundreds of narrators and only the person choosing knows which.
 *
 * The dialog does not decide whether the answer is any good — [CustomVoice.define] does, and its
 * refusals arrive on the status line behind. So [onAdd] returns whether the voice was accepted, and
 * the form stays open with everything still typed in it when it was not.
 */
@Composable
private fun AddVoiceDialog(
    onAdd: (VoiceDraft, PickedFile?, PickedFile?) -> Boolean,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var language by remember { mutableStateOf("en-US") }
    var quality by remember { mutableStateOf(VoiceQuality.MEDIUM) }
    var speaker by remember { mutableStateOf("") }
    var fromDevice by remember { mutableStateOf(false) }
    var modelUrl by remember { mutableStateOf("") }
    var tokensUrl by remember { mutableStateOf("") }
    var modelFile by remember { mutableStateOf<PickedFile?>(null) }
    var tokensFile by remember { mutableStateOf<PickedFile?>(null) }

    val pickModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        modelFile = uri?.let { describe(context, it) }
    }
    val pickTokens = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        tokensFile = uri?.let { describe(context, it) }
    }

    val ready = name.isNotBlank() &&
        if (fromDevice) modelFile != null && tokensFile != null else modelUrl.isNotBlank()

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a voice") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "A Piper voice is a .onnx model and the tokens.txt beside it. Nothing is sent " +
                        "anywhere: the file is fetched or copied once and then read on this device.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.secondary
                )
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Name") },
                    supportingText = { Text("What you will pick it by.") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                OutlinedTextField(
                    value = language, onValueChange = { language = it },
                    label = { Text("Language") },
                    supportingText = {
                        Text(
                            "A tag like en-US. Pronunciation data ships trimmed to English, so a " +
                                "voice in another language may not speak until that is extended."
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                Text("Quality", fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    VoiceQuality.entries.forEach { tier ->
                        FilterChip(
                            selected = quality == tier,
                            onClick = { quality = tier },
                            label = { Text(tier.label) }
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = !fromDevice,
                        onClick = { fromDevice = false },
                        label = { Text("From a link") }
                    )
                    FilterChip(
                        selected = fromDevice,
                        onClick = { fromDevice = true },
                        label = { Text("From my files") }
                    )
                }
                if (fromDevice) {
                    PickedFileRow("Model (.onnx)", modelFile) { pickModel.launch(arrayOf("*/*")) }
                    PickedFileRow("Tokens (tokens.txt)", tokensFile) { pickTokens.launch(arrayOf("*/*")) }
                } else {
                    OutlinedTextField(
                        value = modelUrl, onValueChange = { modelUrl = it },
                        label = { Text("Model link (.onnx)") },
                        supportingText = { Text("https:// only.") },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                    OutlinedTextField(
                        value = tokensUrl, onValueChange = { tokensUrl = it },
                        label = { Text("Tokens link (optional)") },
                        supportingText = { Text("Left empty, the tokens.txt beside the model is used.") },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                }
                OutlinedTextField(
                    value = speaker, onValueChange = { speaker = it.filter { c -> c.isDigit() } },
                    label = { Text("Speaker number (optional)") },
                    supportingText = { Text("Only for models that hold more than one voice.") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            }
        },
        confirmButton = {
            Button(
                enabled = ready,
                onClick = {
                    val draft = VoiceDraft(
                        name = name,
                        language = language,
                        quality = quality,
                        source = if (fromDevice) {
                            VoiceSource.Device
                        } else {
                            VoiceSource.Link(modelUrl.trim(), tokensUrl.trim())
                        },
                        speaker = speaker.toIntOrNull() ?: 0
                    )
                    if (onAdd(draft, modelFile, tokensFile)) onDismiss()
                }
            ) { Text(if (fromDevice) "Add" else "Download") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun PickedFileRow(label: String, picked: PickedFile?, onPick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 13.sp)
            Text(
                picked?.let { file ->
                    file.name + (file.bytes?.let { " · ${VoiceModel.megabytes(it)}" } ?: "")
                } ?: "Not chosen",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.secondary
            )
        }
        TextButton(onClick = onPick) { Text(if (picked == null) "Choose" else "Change") }
    }
}
