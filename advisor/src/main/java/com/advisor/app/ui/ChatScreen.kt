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
 * The Advisor chat. A scrolling conversation over a text field. The model is a placeholder, so the
 * screen is honest about it: an empty state and a footer both say what's really running, and every
 * answer carries the citations retrieval found.
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

    Column(modifier.fillMaxSize()) {
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
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = if (fromUser) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (fromUser) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
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
