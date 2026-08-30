package com.maintenance.app.ui.assets

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.maintenance.app.data.model.AssetCard
import com.maintenance.app.data.repository.MaintenanceRepository
import com.maintenance.app.logic.AssetKind
import com.maintenance.app.logic.DueStatus
import com.maintenance.app.ui.common.AssetMark
import com.maintenance.app.ui.common.EmptyState
import com.maintenance.app.ui.common.StatusPill
import com.maintenance.app.ui.common.TextField
import com.maintenance.app.ui.common.money
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AssetsViewModel(private val repo: MaintenanceRepository) : ViewModel() {

    val cards: StateFlow<List<AssetCard>> =
        repo.observeAssetCards().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun addAsset(name: String, kind: AssetKind, make: String?, model: String?, year: Int?, onAdded: (String) -> Unit) =
        viewModelScope.launch { onAdded(repo.addAsset(name, kind, make, model, year)) }

    class Factory(private val repo: MaintenanceRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AssetsViewModel(repo) as T
    }
}

/**
 * The register: everything you own, grouped by what kind of thing it is.
 *
 * Grouping by kind rather than sorting by name is what makes this readable at a glance — houses,
 * then vehicles, then the appliances — because that is how people hold the list in their head, and
 * because the number of assets a household has is small enough that any alphabetical order is just
 * a different shuffle of the same twelve rows.
 *
 * Sold or scrapped things stay behind a filter rather than disappearing. Their service history is
 * the most useful thing you own about a car right up until the day after you sell it, and the
 * difference between "gone" and "no longer mine" is the whole reason the archive exists.
 */
@Composable
fun AssetsScreen(
    vm: AssetsViewModel,
    showArchived: Boolean,
    onShowArchivedChange: (Boolean) -> Unit,
    onOpenAsset: (String) -> Unit
) {
    val cards by vm.cards.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }

    val visible = cards.filter { showArchived || !it.asset.archived }
    val archivedCount = cards.count { it.asset.archived }
    val grouped = visible.groupBy { it.asset.kind }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { adding = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Add asset") }
            )
        }
    ) { padding ->
        if (visible.isEmpty()) {
            EmptyState(
                headline = "Nothing here yet",
                detail = "Add the house, the cars, the furnace — whatever you'd have to look up a serial number for.",
                modifier = Modifier.padding(padding)
            )
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (archivedCount > 0) {
                    item(key = "filter") {
                        FilterChip(
                            selected = showArchived,
                            onClick = { onShowArchivedChange(!showArchived) },
                            label = { Text("$archivedCount no longer owned") }
                        )
                    }
                }
                AssetKind.entries.forEach { kind ->
                    val ofKind = grouped[kind].orEmpty()
                    if (ofKind.isEmpty()) return@forEach
                    item(key = "head-${kind.key}") {
                        Text(
                            kind.plural,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                    items(ofKind, key = { it.asset.id }) { card ->
                        AssetRow(card = card, onClick = { onOpenAsset(card.asset.id) })
                    }
                }
                item(key = "tail") { Spacer(Modifier.height(72.dp)) }
            }
        }
    }

    if (adding) {
        AddAssetDialog(
            onDismiss = { adding = false },
            onAdd = { name, kind, make, model, year ->
                adding = false
                vm.addAsset(name, kind, make, model, year) { onOpenAsset(it) }
            }
        )
    }
}

/**
 * One asset in the register: its mark, what it is, and the single most useful fact about it today —
 * the thing it next needs, or what it costs you to keep, or nothing at all if it is simply on file.
 */
@Composable
private fun AssetRow(card: AssetCard, onClick: () -> Unit) {
    val asset = card.asset
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AssetMark(kind = asset.kind, colorArgb = asset.colorArgb, archived = asset.archived)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    asset.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    asset.descriptor.ifBlank { asset.kind.label },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                card.nextDue?.let { due ->
                    Text(
                        "${due.title} · ${due.detail}",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                val meterLine = card.meter?.current?.let { card.meter.unit.format(it) }
                val balanceLine = card.balanceCents?.takeIf { it > 0L }?.let { "${money(it, withCents = false)} owed" }
                val footnote = listOfNotNull(meterLine, balanceLine).joinToString(" · ")
                if (footnote.isNotBlank()) {
                    Text(
                        footnote,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (card.nextDue != null && card.nextDue.status != DueStatus.SCHEDULED) {
                Spacer(Modifier.width(8.dp))
                StatusPill(card.nextDue.status)
            }
        }
    }
}

/**
 * Adding an asset asks for four things and no more.
 *
 * The VIN, the parcel number, the mortgage and the service schedule all live one screen in — asked
 * for when you are sitting with the paperwork, not while you are standing in the garage trying to
 * get the car into the app at all. Anything this dialog demanded up front is a thing that would
 * stop somebody adding the car.
 */
@Composable
private fun AddAssetDialog(
    onDismiss: () -> Unit,
    onAdd: (name: String, kind: AssetKind, make: String?, model: String?, year: Int?) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(AssetKind.VEHICLE) }
    var make by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var year by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add an asset") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                KindChips(selected = kind, onSelect = { kind = it })
                TextField(label = "Name", value = name, onChange = { name = it })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextField(label = "Make", value = make, onChange = { make = it }, modifier = Modifier.weight(1f))
                    TextField(label = "Model", value = model, onChange = { model = it }, modifier = Modifier.weight(1f))
                }
                com.maintenance.app.ui.common.NumberField(
                    label = "Year",
                    value = year,
                    onChange = { year = it.take(4) }
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    onAdd(
                        name.trim(),
                        kind,
                        make.trim().takeIf { it.isNotBlank() },
                        model.trim().takeIf { it.isNotBlank() },
                        year.toIntOrNull()
                    )
                }
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * The five kinds, as chips. Which one it is decides what the app asks for next.
 *
 * They scroll sideways rather than wrapping: five chips do not fit across a phone, and a wrapped
 * row changes height as the labels change, which makes a dialog jump while you are reading it.
 */
@Composable
fun KindChips(selected: AssetKind, onSelect: (AssetKind) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        AssetKind.entries.forEach { kind ->
            FilterChip(
                selected = kind == selected,
                onClick = { onSelect(kind) },
                label = { Text(kind.label, style = MaterialTheme.typography.labelSmall) }
            )
        }
    }
}
