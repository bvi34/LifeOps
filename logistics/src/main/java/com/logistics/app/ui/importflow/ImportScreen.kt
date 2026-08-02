package com.logistics.app.ui.importflow

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.logistics.app.data.model.ImportSource
import com.logistics.app.data.model.ParsedOrder
import com.logistics.app.data.model.ParsedOrderLine
import com.logistics.app.data.repository.PantryRepository
import com.logistics.app.logic.WalmartOrderParser
import com.logistics.app.net.PdfTextExtractor
import com.logistics.app.ui.pantry.formatQty
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface ImportUiState {
    object Idle : ImportUiState
    object Working : ImportUiState
    data class Preview(val order: ParsedOrder, val source: ImportSource) : ImportUiState
    data class Done(val count: Int, val label: String) : ImportUiState
    data class Error(val message: String) : ImportUiState
}

class ImportViewModel(private val repo: PantryRepository) : ViewModel() {

    private val _state = MutableStateFlow<ImportUiState>(ImportUiState.Idle)
    val state: StateFlow<ImportUiState> = _state.asStateFlow()

    fun working() { _state.value = ImportUiState.Working }
    fun reset() { _state.value = ImportUiState.Idle }
    fun fail(message: String) { _state.value = ImportUiState.Error(message) }

    fun parseText(text: String, source: ImportSource) {
        if (text.isBlank()) { _state.value = ImportUiState.Error("Nothing to import — the text was empty."); return }
        val order = WalmartOrderParser.parse(text)
        _state.value = if (order.lines.isEmpty()) {
            ImportUiState.Error("Couldn't find any items. Make sure this is a Walmart order/invoice.")
        } else {
            ImportUiState.Preview(order, source)
        }
    }

    fun commit(order: ParsedOrder, source: ImportSource) = viewModelScope.launch {
        _state.value = ImportUiState.Working
        runCatching { repo.commitImport(order, source) }
            .onSuccess { _state.value = ImportUiState.Done(order.lines.size, it.label) }
            .onFailure { _state.value = ImportUiState.Error(it.message ?: "Import failed") }
    }

    class Factory(private val repo: PantryRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ImportViewModel(repo) as T
    }
}

@Composable
fun ImportScreen(
    vm: ImportViewModel,
    initialPdf: Uri? = null,
    initialText: String? = null
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            vm.working()
            scope.launch {
                val text = PdfTextExtractor.extract(context, uri)
                if (text == null) vm.fail("Couldn't read that PDF.")
                else vm.parseText(text, ImportSource.WALMART_PDF)
            }
        }
    }

    // Handle an incoming shared/opened PDF or text once.
    LaunchedEffect(initialPdf, initialText) {
        when {
            initialPdf != null -> {
                vm.working()
                val text = PdfTextExtractor.extract(context, initialPdf)
                if (text == null) vm.fail("Couldn't read that PDF.") else vm.parseText(text, ImportSource.WALMART_PDF)
            }
            !initialText.isNullOrBlank() -> vm.parseText(initialText, ImportSource.PASTED_TEXT)
        }
    }

    when (val s = state) {
        is ImportUiState.Preview -> PreviewList(
            order = s.order,
            source = s.source,
            onCancel = { vm.reset() },
            onConfirm = { order -> vm.commit(order, s.source) }
        )
        else -> ChooseSource(
            state = s,
            onPickPdf = { pickPdf.launch(arrayOf("application/pdf")) },
            onParseText = { vm.parseText(it, ImportSource.PASTED_TEXT) },
            onDone = { vm.reset() }
        )
    }
}

@Composable
private fun ChooseSource(
    state: ImportUiState,
    onPickPdf: () -> Unit,
    onParseText: (String) -> Unit,
    onDone: () -> Unit
) {
    var pasted by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Import a grocery order", style = MaterialTheme.typography.titleLarge)
        Text(
            "Fill your pantry from a Walmart order. Open the order's PDF, or paste the order text below.",
            style = MaterialTheme.typography.bodyMedium
        )

        Button(onClick = onPickPdf, enabled = state !is ImportUiState.Working, modifier = Modifier.fillMaxWidth()) {
            Text("Open Walmart PDF…")
        }

        HorizontalDivider()

        Text("Or paste order text", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = pasted,
            onValueChange = { pasted = it },
            label = { Text("Pasted order text") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp)
        )
        OutlinedButton(
            onClick = { onParseText(pasted) },
            enabled = pasted.isNotBlank() && state !is ImportUiState.Working,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Parse pasted text") }

        when (state) {
            is ImportUiState.Working -> {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Reading…", style = MaterialTheme.typography.bodySmall)
            }
            is ImportUiState.Error -> Text(state.message, color = MaterialTheme.colorScheme.error)
            is ImportUiState.Done -> {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Imported ${state.count} items", style = MaterialTheme.typography.titleMedium)
                        Text(state.label, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = onDone) { Text("Import another") }
                    }
                }
            }
            else -> {}
        }
    }
}

@Composable
private fun PreviewList(
    order: ParsedOrder,
    source: ImportSource,
    onCancel: () -> Unit,
    onConfirm: (ParsedOrder) -> Unit
) {
    // Editable copy of the parsed lines; a null slot means "excluded".
    val lines = remember { mutableStateListOf<ParsedOrderLine?>().apply { addAll(order.lines) } }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(16.dp)) {
            Text("Review import", style = MaterialTheme.typography.titleLarge)
            Text(
                buildString {
                    append("${lines.count { it != null }} items")
                    order.orderNumber?.let { append(" · order #$it") }
                    append(" · ${source.label}")
                },
                style = MaterialTheme.typography.bodySmall
            )
        }
        LazyColumn(
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            itemsIndexed(lines) { index, line ->
                if (line != null) {
                    PreviewRow(
                        line = line,
                        onQty = { newQty -> lines[index] = line.copy(quantity = newQty) },
                        onRemove = { lines[index] = null }
                    )
                }
            }
        }
        Surface(tonalElevation = 3.dp) {
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Cancel") }
                val kept = lines.filterNotNull()
                Button(
                    onClick = { onConfirm(ParsedOrder(order.orderNumber, kept)) },
                    enabled = kept.isNotEmpty(),
                    modifier = Modifier.weight(1f)
                ) { Text("Add ${kept.size} to pantry") }
            }
        }
    }
}

@Composable
private fun PreviewRow(
    line: ParsedOrderLine,
    onQty: (Int) -> Unit,
    onRemove: () -> Unit
) {
    var qtyText by remember { mutableStateOf(line.quantity.toString()) }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(line.rawName, style = MaterialTheme.typography.bodyMedium)
                Text(
                    listOfNotNull(line.unit, line.category).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedTextField(
                value = qtyText,
                onValueChange = {
                    qtyText = it
                    it.toIntOrNull()?.let(onQty)
                },
                label = { Text("Qty") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(84.dp)
            )
            IconButton(onClick = onRemove) { Icon(Icons.Default.Close, contentDescription = "Remove") }
        }
    }
}
