package com.maintenance.app.ui.due

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Handyman
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.maintenance.app.data.repository.MaintenanceRepository
import com.maintenance.app.logic.Docket
import com.maintenance.app.logic.DocketEntry
import com.maintenance.app.logic.DocketSource
import com.maintenance.app.logic.DueStatus
import com.maintenance.app.ui.common.EmptyState
import com.maintenance.app.ui.common.StatusPill
import com.maintenance.app.ui.common.formatDay
import com.maintenance.app.ui.common.statusColor
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class DueViewModel(repo: MaintenanceRepository) : ViewModel() {

    val docket: StateFlow<List<DocketEntry>> =
        repo.observeDocket().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    class Factory(private val repo: MaintenanceRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = DueViewModel(repo) as T
    }
}

/**
 * The docket: everything the household owes its things, across every asset, in the order it wants
 * attention.
 *
 * This is the app's front door because it is the only question the app exists to answer. A register
 * of what you own is a list you consult; *what needs doing* is a list you act on, and putting the
 * register first would mean walking past the filing cabinet every time you wanted the one useful
 * sentence in it.
 *
 * By default it shows only what is pressing — overdue, and the next fortnight. Everything scheduled
 * is one chip away, because a list that also carries the brake fluid due in nineteen months is a
 * list nobody reads to the bottom of.
 */
@Composable
fun DueScreen(
    vm: DueViewModel,
    showAll: Boolean,
    onShowAllChange: (Boolean) -> Unit,
    onOpenAsset: (String) -> Unit
) {
    val docket by vm.docket.collectAsStateWithLifecycle()
    val pressing = docket.filter { it.status.isPressing }
    val visible = if (showAll) docket.filter { it.status != DueStatus.DORMANT } else pressing

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                Docket.headline(docket),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (pressing.any { it.status == DueStatus.OVERDUE }) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
            FilterChip(
                selected = showAll,
                onClick = { onShowAllChange(!showAll) },
                label = { Text(if (showAll) "Everything" else "Pressing") }
            )
        }

        if (visible.isEmpty()) {
            EmptyState(
                headline = if (docket.isEmpty()) "Nothing on the docket" else "Nothing pressing",
                detail = if (docket.isEmpty()) {
                    "Add an asset and give it something that comes round — an oil change, a filter, a registration."
                } else {
                    "Everything with a schedule is in hand. Tap the chip to see what's further out."
                }
            )
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(visible, key = { "${it.source}:${it.id}" }) { entry ->
                DocketRow(entry = entry, onClick = { onOpenAsset(entry.assetId) })
            }
            item(key = "tail") { Spacer(Modifier.height(72.dp)) }
        }
    }
}

/**
 * One line of the docket.
 *
 * The asset's name leads, not the job's. "Truck · Oil change" is how the thing is remembered, and a
 * column of job titles with the asset in small print underneath reads as a to-do list belonging to
 * nobody.
 */
@Composable
private fun DocketRow(entry: DocketEntry, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = when (entry.source) {
                    DocketSource.UPKEEP -> Icons.Filled.Handyman
                    DocketSource.COVERAGE -> Icons.Filled.Description
                },
                contentDescription = null,
                tint = statusColor(entry.status)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "${entry.assetName} · ${entry.title}",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    entry.detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = statusColor(entry.status)
                )
                entry.dueAt?.let { due ->
                    Text(
                        formatDay(due),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            StatusPill(entry.status)
        }
    }
}
