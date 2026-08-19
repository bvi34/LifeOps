package com.advisor.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.advisor.app.llm.AdvisorModelStore
import com.advisor.app.logic.Identity
import com.advisor.app.logic.SourceApp

/**
 * The permission gate, made visible. One switch per hosted app; Advisor reads an app's data only
 * while its switch is on. Denied-by-default is the whole point — the user opts each app in, and can
 * revoke at any time, which the next question honours immediately (denied apps are never loaded).
 * It also surfaces the identity summary and the model card.
 */
@Composable
fun PermissionsScreen(vm: AdvisorViewModel, modifier: Modifier = Modifier) {
    val permissions by vm.permissions.collectAsStateWithLifecycle()
    val identity by vm.identity.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        IdentityCard(identity)

        Text("Data access", style = MaterialTheme.typography.titleMedium)
        Text(
            "Advisor is offline and reads only what you allow here. Each app stays off until you " +
                "turn it on; turning it off stops Advisor from reading that app on the next question. " +
                "The one thing it writes is a task you explicitly ask it to add, into an app you have " +
                "turned on.",
            style = MaterialTheme.typography.bodySmall
        )

        SourceApp.entries.forEach { app ->
            ElevatedCard(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(app.displayName, style = MaterialTheme.typography.titleMedium)
                        Text(describe(app), style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        checked = permissions.isGranted(app),
                        onCheckedChange = { on -> vm.setPermission(app, on) }
                    )
                }
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 4.dp))

        ModelCard(vm)

        HorizontalDivider(Modifier.padding(vertical = 4.dp))

        EmbeddingModelCard(vm)

        Spacer(Modifier.height(8.dp))
        SystemPromptCard(vm)

        Text("Reasoning (C3A)", style = MaterialTheme.typography.titleMedium)
        Text(
            "A unifying engine coordinates identity, profiles, memory and app data, and checks for " +
                "gaps and contradictions before answering. Its rule: not knowing is fine — being " +
                "wrong without asking is not. When it's unsure it asks you a question instead of " +
                "guessing.",
            style = MaterialTheme.typography.bodySmall
        )

        OutlinedButton(onClick = { vm.clearConversation() }) {
            Text("Clear conversation")
        }
    }
}

private fun describe(app: SourceApp): String = when (app) {
    SourceApp.LIFEOPS -> "Tasks, aspects, projects and milestones. Can add a task when you ask for one."
    SourceApp.CITATION -> "Your library and reading notes."
    SourceApp.LOGISTICS -> "Pantry stock and grocery list."
}

/**
 * The model card: what's running, plus in-app provisioning of the Qwen3-4B GGUF. The weights are
 * *imported* from a file the user picked (no `INTERNET`, nothing leaves the device); Advisor loads
 * them on the next question. When a file is present but generation is still the placeholder, the card
 * says why — this build doesn't include the native runtime.
 */
@Composable
private fun ModelCard(vm: AdvisorViewModel) {
    val modelState by vm.modelState.collectAsStateWithLifecycle()

    val pickModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importModel(uri)
    }

    Text("Model", style = MaterialTheme.typography.titleMedium)
    Text(vm.model.label(), style = MaterialTheme.typography.bodyMedium)
    // Ground truth from the backend: the loaded file, or exactly why it's still the placeholder.
    Text("Status: ${vm.modelStatus}", style = MaterialTheme.typography.bodySmall)

    when (val s = modelState) {
        is ModelUiState.Importing -> {
            if (s.total > 0) {
                LinearProgressIndicator(
                    progress = { (s.copied.toFloat() / s.total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Importing… ${AdvisorModelStore.humanBytes(s.copied)} of " +
                        AdvisorModelStore.humanBytes(s.total),
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    "Importing… ${AdvisorModelStore.humanBytes(s.copied)}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        is ModelUiState.Error -> {
            Text(s.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = { pickModel.launch(arrayOf("*/*")) }) {
                Text("Try another file…")
            }
        }

        is ModelUiState.Idle -> {
            if (s.info.installed) {
                Text(
                    "Model file: ${s.info.label()}",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    if (vm.model.isPlaceholder) {
                        "A model file is installed, but Advisor is still on the deterministic " +
                            "placeholder — see Status above. If it says the native runtime isn't in " +
                            "this build, rebuild with -Padvisor.buildNativeLlm=true; if it failed to " +
                            "load, the GGUF is likely the wrong format or too large for this device."
                    } else {
                        "Advisor runs Qwen3-4B locally via llama.cpp — fully on-device, no network. " +
                            "It reasons over the same retrieved, cited context the pipeline assembled."
                    },
                    style = MaterialTheme.typography.bodySmall
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pickModel.launch(arrayOf("*/*")) }) {
                        Text("Replace model…")
                    }
                    TextButton(onClick = { vm.deleteModel() }) {
                        Text("Remove")
                    }
                }
            } else {
                Text(
                    "No model is installed yet, so Advisor uses a deterministic placeholder that " +
                        "retrieves and cites your own records. Import a Qwen3-4B Q4_K_M GGUF and " +
                        "Advisor runs it fully on-device — no network, nothing leaves the device.",
                    style = MaterialTheme.typography.bodySmall
                )
                Button(onClick = { pickModel.launch(arrayOf("*/*")) }) {
                    Text("Import model file (.gguf)…")
                }
            }
        }
    }
}

/**
 * The embedding-model card: optional, in-app provisioning of a small sentence-embedding GGUF. With it
 * installed, retrieval ranks the user's data by *meaning* (so "Who am I?" finds a `Name:` fact);
 * without it, Advisor uses the deterministic lexical retriever. Same promise as the generation model —
 * the file is imported from a document the user picked, no network, nothing leaves the device.
 */
@Composable
private fun EmbeddingModelCard(vm: AdvisorViewModel) {
    val state by vm.embeddingModelState.collectAsStateWithLifecycle()

    val pickModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importEmbeddingModel(uri)
    }

    Text("Semantic retrieval", style = MaterialTheme.typography.titleMedium)

    when (val s = state) {
        is ModelUiState.Importing -> {
            if (s.total > 0) {
                LinearProgressIndicator(
                    progress = { (s.copied.toFloat() / s.total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Importing… ${AdvisorModelStore.humanBytes(s.copied)} of " +
                        AdvisorModelStore.humanBytes(s.total),
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    "Importing… ${AdvisorModelStore.humanBytes(s.copied)}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        is ModelUiState.Error -> {
            Text(s.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = { pickModel.launch(arrayOf("*/*")) }) {
                Text("Try another file…")
            }
        }

        is ModelUiState.Idle -> {
            if (s.info.installed) {
                Text("Embedding model: ${s.info.label()}", style = MaterialTheme.typography.bodySmall)
                Text(
                    if (vm.semanticRetrieval) {
                        "Retrieval ranks your data by meaning, on-device. This lets Advisor match a " +
                            "question to relevant records even when they share no exact words."
                    } else {
                        "An embedding file is installed, but this build doesn't include the native " +
                            "runtime, so retrieval is still lexical. Rebuild with " +
                            "-Padvisor.buildNativeLlm=true to run it."
                    },
                    style = MaterialTheme.typography.bodySmall
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pickModel.launch(arrayOf("*/*")) }) {
                        Text("Replace model…")
                    }
                    TextButton(onClick = { vm.deleteEmbeddingModel() }) {
                        Text("Remove")
                    }
                }
            } else {
                Text(
                    "Optional. Without it, Advisor retrieves your data by keyword (lexical). Import a " +
                        "small sentence-embedding GGUF and retrieval becomes semantic — matching by " +
                        "meaning — fully on-device, no network.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedButton(onClick = { pickModel.launch(arrayOf("*/*")) }) {
                    Text("Import embedding model (.gguf)…")
                }
            }
        }
    }
}

/**
 * Editor for the standing instruction the model is given before every question — tone, what to lead
 * with, what never to do. Shown here beside the model cards because it is the same kind of setting:
 * it changes how the model behaves, and it takes effect on the next question with no restart.
 *
 * The draft is local to the editor so typing never re-runs anything, and it re-seeds whenever the
 * saved value changes (including after a reset). Saving a blank box resets rather than clearing —
 * a model with no standing instruction answers unusably.
 */
@Composable
private fun SystemPromptCard(vm: AdvisorViewModel) {
    val saved by vm.systemPrompt.collectAsStateWithLifecycle()
    var draft by remember(saved.text) { mutableStateOf(saved.text) }
    val dirty = draft.trim() != saved.text.trim()

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("System prompt", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                if (saved.isCustom) {
                    Text("Customised", style = MaterialTheme.typography.labelSmall)
                }
            }
            Text(
                "The standing instruction given to the model before every question. It's stored as a " +
                    "plain text file (advisor/system-prompt.txt) you can also edit directly, and it's " +
                    "included in backup. Changes apply to your next question.",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Instruction") },
                minLines = 6,
                maxLines = 16,
                textStyle = MaterialTheme.typography.bodySmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.saveSystemPrompt(draft) }, enabled = dirty) {
                    Text("Save")
                }
                OutlinedButton(
                    onClick = { draft = saved.text },
                    enabled = dirty
                ) {
                    Text("Discard")
                }
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = { vm.resetSystemPrompt() },
                    enabled = saved.isCustom
                ) {
                    Text("Reset to default")
                }
            }
        }
    }
}

@Composable
private fun IdentityCard(identity: Identity) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Identity", style = MaterialTheme.typography.titleMedium)
            if (identity.isEmpty) {
                Text(
                    "No identity set yet. Identity is stored as a portable JSON file " +
                        "(advisor/identity.json) that you can edit directly; it's included in backup " +
                        "and given to the model as always-on context.",
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                identity.toContextLines().forEach { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
