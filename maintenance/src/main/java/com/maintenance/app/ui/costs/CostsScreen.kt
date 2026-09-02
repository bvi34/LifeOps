package com.maintenance.app.ui.costs

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
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
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
import com.maintenance.app.logic.AssetSpend
import com.maintenance.app.logic.Ledger
import com.maintenance.app.logic.Ledgers
import com.maintenance.app.logic.VendorSpend
import com.maintenance.app.ui.common.AssetMark
import com.maintenance.app.ui.common.EmptyState
import com.maintenance.app.ui.common.LabeledValue
import com.maintenance.app.ui.common.SectionCard
import com.maintenance.app.ui.common.formatDay
import com.maintenance.app.ui.common.money
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

/** The two ways somebody asks this: about the year, or about the whole time they have owned it. */
enum class CostWindow(val label: String) {
    YEAR("Last 12 months"),
    EVER("All time");

    fun since(now: Long): Long? = if (this == YEAR) Ledgers.yearTo(now) else null
}

@OptIn(ExperimentalCoroutinesApi::class)
class CostsViewModel(private val repo: MaintenanceRepository) : ViewModel() {

    private val _window = MutableStateFlow(CostWindow.YEAR)
    val window: StateFlow<CostWindow> = _window.asStateFlow()

    // The window is state rather than a parameter because switching it re-asks the same question of
    // the same rows; the fold is cheap and the alternative is holding two ledgers.
    val ledger: StateFlow<Ledger?> = _window
        .flatMapLatest { repo.observeLedger(since = it.since(System.currentTimeMillis())) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setWindow(window: CostWindow) {
        _window.value = window
    }

    class Factory(private val repo: MaintenanceRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = CostsViewModel(repo) as T
    }
}

/**
 * What all of this is costing.
 *
 * Every figure here already existed on an asset's page. What did not exist was the sideways view —
 * the only place that can answer *which of these things is eating the money*, which is the question
 * that actually changes what somebody does. An asset page can tell you the truck cost $1,900 this
 * year; only this screen can tell you that is most of everything.
 *
 * Nothing here is a projection. The bar beside each asset is its share of what was actually spent,
 * not a forecast, and the app still refuses to annualise a history shorter than a year — see
 * `logic/Costs`.
 */
@Composable
fun CostsScreen(vm: CostsViewModel, onOpenAsset: (String) -> Unit) {
    val ledger by vm.ledger.collectAsStateWithLifecycle()
    val window by vm.window.collectAsStateWithLifecycle()
    val current = ledger

    if (current == null || current.isEmpty) {
        EmptyState(
            headline = "Nothing has cost anything yet",
            detail = "Log a service, or put a loan or a policy on something, and this is where it adds up."
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item(key = "window") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CostWindow.entries.forEach { entry ->
                    FilterChip(
                        selected = entry == window,
                        onClick = { vm.setWindow(entry) },
                        label = { Text(entry.label) }
                    )
                }
            }
        }

        item(key = "total") {
            SectionCard(title = if (window == CostWindow.YEAR) "The last 12 months" else "Everything, ever") {
                Text(
                    money(current.allInCents, withCents = false),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    listOfNotNull(
                        "${money(current.serviceCents, withCents = false)} of work",
                        current.coverageCents.takeIf { it > 0L }
                            ?.let { "${money(it, withCents = false)} of premiums" }
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item(key = "standing") {
            SectionCard(title = "Where you stand") {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    LabeledValue(
                        label = "Worth",
                        value = if (current.valued == 0) "—" else money(current.worthCents, withCents = false),
                        modifier = Modifier.weight(1f)
                    )
                    LabeledValue(
                        label = "Owed",
                        value = money(current.owedCents, withCents = false),
                        modifier = Modifier.weight(1f)
                    )
                    LabeledValue(
                        label = "Equity",
                        value = current.equityCents?.let { money(it, withCents = false) } ?: "—",
                        modifier = Modifier.weight(1f)
                    )
                }
                // Said rather than hidden: a total worth is only ever as complete as the figures
                // typed into it, and "worth" with two of five things valued is not a household's
                // net worth.
                if (current.valued < current.assets) {
                    Text(
                        if (current.valued == 0) {
                            "Nothing has a value typed in, so there is nothing to compare the debt with."
                        } else {
                            "${current.valued} of ${current.assets} have a value typed in — the rest are missing from it."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (current.spend.isNotEmpty()) {
            item(key = "spend-head") {
                Text(
                    "Where it went",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            items(current.spend, key = { it.assetId }) { spend ->
                SpendRow(
                    spend = spend,
                    ofTotal = current.allInCents,
                    onClick = { onOpenAsset(spend.assetId) }
                )
            }
        }

        if (current.vendors.isNotEmpty()) {
            item(key = "vendors") {
                SectionCard(title = "Who you've paid") {
                    current.vendors.take(8).forEach { vendor -> VendorRow(vendor) }
                }
            }
        }

        item(key = "tail") { Spacer(Modifier.height(24.dp)) }
    }
}

/** One asset's share of the money, with the bar showing how much of it that was. */
@Composable
private fun SpendRow(spend: AssetSpend, ofTotal: Long, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AssetMark(kind = spend.kind, colorArgb = spend.colorArgb, archived = spend.archived, size = 36)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (spend.archived) "${spend.name} · no longer owned" else spend.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        listOfNotNull(
                            "${spend.entries} logged".takeIf { spend.entries > 0 },
                            spend.coverageCents.takeIf { it > 0L }
                                ?.let { "${money(it, withCents = false)} of premiums" }
                        ).joinToString(" · ").ifBlank { "Premiums only" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    money(spend.allInCents, withCents = false),
                    style = MaterialTheme.typography.titleSmall
                )
            }
            if (ofTotal > 0L) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { (spend.allInCents.toDouble() / ofTotal).coerceIn(0.0, 1.0).toFloat() },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/** One garage, and what they have had. */
@Composable
private fun VendorRow(vendor: VendorSpend) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(vendor.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(
                    if (vendor.visits == 1) "once" else "${vendor.visits} times",
                    "on ${vendor.assets} things".takeIf { vendor.assets > 1 },
                    "last ${formatDay(vendor.lastAt)}"
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(money(vendor.totalCents, withCents = false), style = MaterialTheme.typography.bodyMedium)
    }
}
