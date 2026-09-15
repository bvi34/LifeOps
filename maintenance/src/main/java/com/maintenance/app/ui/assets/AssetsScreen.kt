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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.mutableStateMapOf
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
import com.maintenance.app.logic.AssetAttributes
import com.maintenance.app.logic.AssetKind
import com.maintenance.app.logic.DueStatus
import com.maintenance.app.ui.asset.KindAttributeFields
import com.maintenance.app.ui.common.AssetMark
import com.maintenance.app.ui.common.EmptyState
import com.maintenance.app.ui.common.StatusPill
import com.maintenance.app.ui.common.money
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.operations.suite.ui.fields.SuiteMoneyField
import com.operations.suite.ui.fields.SuiteNumberField
import com.operations.suite.ui.fields.SuiteTextField

class AssetsViewModel(private val repo: MaintenanceRepository) : ViewModel() {

    val cards: StateFlow<List<AssetCard>> =
        repo.board.observeAssetCards().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun addAsset(
        name: String,
        kind: AssetKind,
        make: String?,
        model: String?,
        year: Int?,
        attributes: Map<String, String>,
        onAdded: (String) -> Unit
    ) = viewModelScope.launch { onAdded(repo.assets.addAsset(name, kind, make, model, year, attributes)) }

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
            onAdd = { name, kind, make, model, year, attributes ->
                adding = false
                vm.addAsset(name, kind, make, model, year, attributes) { onOpenAsset(it) }
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
 * Adding an asset asks for everything the kind has — and insists on none of it but the name.
 *
 * Pick "Vehicle" and the VIN, the trim, the engine, the plate and the rest are right there, because
 * the moment somebody is typing the car in is the moment they have the title or the insurance card
 * in their other hand, and coming back for those fields later is a trip most people never make. The
 * fields are generated from `logic/AssetKind`, so this dialog and the edit dialog ask for the same
 * things by construction, and a new kind grows its own here for free.
 *
 * What keeps that from being a wall: **every one of them may be left blank**, the Add button
 * watches the name alone, and the list scrolls. Nothing here can stop somebody standing in the
 * garage from getting the car into the app — it just stops them having to come back.
 *
 * The values typed under one kind survive switching to another, so a serial number entered under
 * "Appliance" is still there if it turns out to be "Equipment"; only the selected kind's fields are
 * saved.
 */
@Composable
private fun AddAssetDialog(
    onDismiss: () -> Unit,
    onAdd: (
        name: String,
        kind: AssetKind,
        make: String?,
        model: String?,
        year: Int?,
        attributes: Map<String, String>
    ) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(AssetKind.VEHICLE) }
    var make by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var year by remember { mutableStateOf("") }
    val attributes = remember { mutableStateMapOf<String, String>() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add an asset") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                KindChips(selected = kind, onSelect = { kind = it })
                SuiteTextField(label = "Name", value = name, onValueChange = { name = it })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SuiteTextField(label = "Make", value = make, onValueChange = { make = it }, modifier = Modifier.weight(1f))
                    SuiteTextField(label = "Model", value = model, onValueChange = { model = it }, modifier = Modifier.weight(1f))
                }
                SuiteNumberField(label = "Year", value = year, onValueChange = { year = it.take(4) })

                Text(
                    "Everything below is optional — fill in what you have in front of you." +
                        when (kind) {
                            AssetKind.VEHICLE -> " The VIN fills in most of the rest later."
                            // The two pickers are what choose a home's schedules, so they are worth
                            // a sentence here: this is the one screen where somebody is looking at
                            // the house while they think about it.
                            AssetKind.HOME -> " The type of home and what it has are what choose its upkeep."
                            else -> ""
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                KindAttributeFields(
                    kind = kind,
                    values = attributes,
                    onChange = { key, value -> attributes[key] = value }
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
                        year.toIntOrNull(),
                        // Only the chosen kind's fields, normalised the way the edit dialog stores
                        // them — a VIN typed in lower case is filed as it reads on the title.
                        kind.attributes.associate { spec ->
                            spec.key to AssetAttributes.normalise(spec, attributes[spec.key].orEmpty())
                        }
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
