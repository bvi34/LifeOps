package com.advisor.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The Advisor chat. A scrolling conversation over a text field. The screen is honest about what's
 * running: the empty state names the live model (Qwen3-4B, or the placeholder while its weights aren't
 * on the device yet), and every answer carries the citations retrieval found.
 */
@Composable
fun ChatScreen(vm: AdvisorViewModel, modifier: Modifier = Modifier) {
    val messages by vm.messages.collectAsStateWithLifecycle()
    val permissions by vm.permissions.collectAsStateWithLifecycle()
    val thinking by vm.thinking.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    // imePadding lifts the whole column (and so the input row at its foot) above the soft keyboard.
    // Needed because the app targets SDK 35, where edge-to-edge is enforced and the manifest's
    // adjustResize no longer shrinks the window for the IME — the insets have to be consumed here.
    Column(modifier.fillMaxSize().imePadding()) {
        if (permissions.isEmpty) {
            NoPermissionsBanner()
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (messages.isEmpty()) {
                item { EmptyState(vm.model.label()) }
            }
            items(messages, key = { it.id }) { message ->
                MessageBubble(message)
            }
            if (thinking) {
                item { ThinkingRow() }
            }
        }

        HorizontalDivider()

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask about your tasks, books, pantry…") },
                enabled = !thinking,
                maxLines = 4
            )
            FilledIconButton(
                onClick = {
                    vm.ask(draft)
                    draft = ""
                },
                enabled = draft.isNotBlank() && !thinking
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Ask")
            }
        }
    }
}

@Composable
private fun NoPermissionsBanner() {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Text(
            "No apps are enabled yet — open Permissions and grant Advisor read access so it has " +
                "something to reason over.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
        )
    }
}

@Composable
private fun EmptyState(modelLabel: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 48.dp, start = 24.dp, end = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Advisor", style = MaterialTheme.typography.headlineSmall)
        Text(
            "A private, on-device assistant that answers from your own suite data — grounded and " +
                "cited, never sent anywhere.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        Text(
            "Model: $modelLabel",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ThinkingRow() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        Text("Retrieving…", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val fromUser = message.fromUser
    val clarify = message.isClarification
    val bubbleColor = when {
        fromUser -> MaterialTheme.colorScheme.primary
        clarify -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val onBubble = when {
        fromUser -> MaterialTheme.colorScheme.onPrimary
        clarify -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = bubbleColor,
            contentColor = onBubble,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                if (clarify) {
                    Text(
                        "Needs your input",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(4.dp))
                }
                Text(message.text, style = MaterialTheme.typography.bodyMedium)
                if (message.citationIds.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Sources: " + message.citationIds.joinToString(", ") { prettyCitation(it) },
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}

/** Turn a document id ("lifeops:task:abc") into a short "LifeOps · task" chip label. */
private fun prettyCitation(id: String): String {
    val parts = id.split(':')
    if (parts.size < 2) return id
    val app = parts[0].replaceFirstChar { it.uppercase() }
    return "$app · ${parts[1]}"
}
