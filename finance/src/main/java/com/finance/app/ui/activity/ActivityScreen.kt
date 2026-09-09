package com.finance.app.ui.activity

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.finance.app.data.repository.FinanceRepository
import com.finance.app.logic.Category
import com.finance.app.logic.Recurring
import com.finance.app.logic.Transaction
import com.finance.app.ui.common.EmptyState
import com.finance.app.ui.common.SectionCard
import com.finance.app.ui.common.formatDay
import com.operations.suite.ui.fields.suiteMoney
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate

data class ActivityState(
    val transactions: List<Transaction> = emptyList(),
    val series: List<Recurring.Series> = emptyList(),
    val accountNames: Map<String, String> = emptyMap()
)

class ActivityViewModel(
    repository: FinanceRepository,
    today: () -> LocalDate = { LocalDate.now() }
) : ViewModel() {

    val state: StateFlow<ActivityState> = repository
        .observePicture(today().minusMonths(15), today())
        .map { picture ->
            ActivityState(
                // The list shows everything — an account in another currency is still your money
                // and its rows are still true. Only the *detector* is restricted, because a series'
                // "typical amount" summed across currencies would be a number about nothing.
                transactions = picture.transactions,
                series = Recurring.detect(picture.countable()),
                accountNames = picture.accounts.associate { it.id to it.displayName() }
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ActivityState())

    class Factory(private val repository: FinanceRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ActivityViewModel(repository) as T
    }
}

/**
 * Activity: what actually happened, and what the app has noticed happening repeatedly.
 *
 * The recurring card at the top is the reason this screen is worth opening rather than opening the
 * bank's own app — it is the list of standing commitments the household has, which nobody keeps
 * anywhere and everybody discovers by accident. Everything below it is the ledger, which the bank
 * shows too; it is here so a figure on the Picture screen can always be traced to the rows behind it.
 */
@Composable
fun ActivityScreen(vm: ActivityViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    val category = filter?.let { Category.fromKey(it) }

    if (state.transactions.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(
                headline = "Nothing to show yet",
                detail = "Once an account is connected, everything it reports lands here."
            )
        }
        return
    }

    val visible = if (category == null) {
        state.transactions
    } else {
        state.transactions.filter { it.category == category }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (state.series.isNotEmpty()) {
            item { RecurringCard(state.series) }
        }

        item {
            // The categories present in the data, not all sixteen: a filter row offering ten
            // categories with nothing in them is a row that has to be read to be dismissed.
            val present = state.transactions.map { it.category }.distinct()
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = filter == null,
                    onClick = { filter = null },
                    label = { Text("Everything") }
                )
                present.forEach { option ->
                    FilterChip(
                        selected = filter == option.key,
                        onClick = { filter = option.key },
                        label = { Text(option.label) }
                    )
                }
            }
        }

        items(visible, key = { it.id }) { row ->
            TransactionRow(row, state.accountNames[row.accountId])
        }
    }
}

@Composable
private fun RecurringCard(series: List<Recurring.Series>) {
    SectionCard(
        title = "Things that come round",
        subtitle = "Charges seen at least three times, on a steady rhythm and for a steady amount."
    ) {
        series.take(10).forEachIndexed { index, item ->
            if (index > 0) HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f)) {
                    Text(item.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${item.cadence.label} · ${item.occurrences} times · last ${formatDay(item.lastSeen)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    // A charge that moves about says "about", so a figure quoted off this card is
                    // never more precise than the thing it describes.
                    (if (item.fixed) "" else "~") + suiteMoney(item.typicalAmountCents),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun TransactionRow(row: Transaction, accountName: String?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                row.label(),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // Pending rows are italic rather than hidden: they are real enough to see and not
                // real enough to count, and italics say that without a badge on every row.
                fontStyle = if (row.pending) FontStyle.Italic else FontStyle.Normal
            )
            Text(
                buildString {
                    append(formatDay(row.date))
                    append(" · ")
                    append(row.category.label)
                    accountName?.let { append(" · $it") }
                    if (row.pending) append(" · pending")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            suiteMoney(row.amountCents),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (row.inflow) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            }
        )
    }
}
