package com.advisor.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.advisor.app.logic.MemoryRecord

/**
 * The long-term memory manager. Add memories with free-form tags, filter by tag facet, and pin the
 * ones that should always be reachable. This is the visible face of the dedicated, tag-rich memory
 * store the assistant recalls from.
 */
@Composable
fun MemoryScreen(vm: AdvisorViewModel, modifier: Modifier = Modifier) {
    val memories by vm.memories.collectAsStateWithLifecycle()
    val tagCounts by vm.tagCounts.collectAsStateWithLifecycle()

    var content by remember { mutableStateOf("") }
    var tags by remember { mutableStateOf("") }
    var salience by remember { mutableStateOf(50f) }
    var pinned by remember { mutableStateOf(false) }
    var filterTag by remember { mutableStateOf<String?>(null) }

    val shown = remember(memories, filterTag) {
        val tag = filterTag
        if (tag == null) memories else memories.filter { it.hasTag(tag) }
    }

    Column(modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Add a memory", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Something worth remembering long-term…") },
                minLines = 2
            )
            OutlinedTextField(
                value = tags,
                onValueChange = { tags = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Tags (comma-separated, e.g. person:sam, topic:health)") },
                singleLine = true
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Salience ${salience.toInt()}", style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = salience,
                    onValueChange = { salience = it },
                    valueRange = 0f..100f,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                )
                Text("Pin", style = MaterialTheme.typography.bodySmall)
                Switch(checked = pinned, onCheckedChange = { pinned = it })
            }
            Button(
                onClick = {
                    vm.remember(content, tags, salience.toInt(), pinned)
                    content = ""; tags = ""; salience = 50f; pinned = false
                },
                enabled = content.isNotBlank(),
                modifier = Modifier.align(Alignment.End)
            ) {
                Text("Remember")
            }
        }

        if (tagCounts.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = filterTag == null,
                    onClick = { filterTag = null },
                    label = { Text("All") }
                )
                tagCounts.forEach { tc ->
                    FilterChip(
                        selected = filterTag == tc.tag,
                        onClick = { filterTag = if (filterTag == tc.tag) null else tc.tag },
                        label = { Text("${tc.tag} (${tc.count})") }
                    )
                }
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        if (shown.isEmpty()) {
            Text(
                "No memories yet. Add one above — the assistant recalls the most relevant ones when " +
                    "you ask a question.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(shown, key = { it.id }) { memory ->
                    MemoryRow(
                        memory = memory,
                        onPin = { vm.setMemoryPinned(memory.id, !memory.pinned) },
                        onDelete = { vm.forget(memory.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun MemoryRow(memory: MemoryRecord, onPin: () -> Unit, onDelete: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(Modifier.weight(1f)) {
                Text(memory.content, style = MaterialTheme.typography.bodyMedium)
                if (memory.tags.isNotEmpty()) {
                    Text(
                        memory.tags.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    "salience ${memory.salience}" +
                        if (memory.recallCount > 0) " · recalled ${memory.recallCount}×" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconToggleButton(checked = memory.pinned, onCheckedChange = { onPin() }) {
                Icon(
                    Icons.Default.PushPin,
                    contentDescription = if (memory.pinned) "Unpin" else "Pin",
                    tint = if (memory.pinned) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete")
            }
        }
    }
}
