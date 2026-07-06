@file:OptIn(ExperimentalMaterial3Api::class)

package com.lifeops.app.ui.screens.collection

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeops.app.ui.components.AppHeader
import com.lifeops.app.ui.components.BackNavIcon

/** Autosaves on every field change rather than requiring an explicit save action — this is a
 *  running idea journal, not a form with a submit step. */
@Composable
fun FutureProjectDetailScreen(viewModel: FutureProjectDetailViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val project = state.project

    var title by rememberSaveable(project?.id) { mutableStateOf(project?.title ?: "") }
    var content by rememberSaveable(project?.id) { mutableStateOf(project?.content ?: "") }

    Scaffold(
        topBar = {
            AppHeader(
                navigationIcon = { BackNavIcon(onBack) },
                actions = {
                    IconButton(onClick = { viewModel.delete(onBack) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete project")
                    }
                }
            )
        }
    ) { padding ->
        if (project == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Loading…", style = MaterialTheme.typography.bodyMedium)
            }
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = {
                    title = it
                    viewModel.save(it, content)
                },
                label = { Text("Title") },
                textStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = content,
                onValueChange = {
                    content = it
                    viewModel.save(title, it)
                },
                label = { Text("Notes") },
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
        }
    }
}
