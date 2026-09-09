package com.finance.app.ui.accounts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.finance.app.data.repository.FinanceRepository
import com.finance.app.logic.AccountKind
import com.finance.app.logic.Accounts
import com.finance.app.ui.common.EmptyState
import com.finance.app.ui.common.Figure
import com.finance.app.ui.common.SectionCard
import com.operations.suite.ui.fields.suiteMoney
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

data class AccountsState(
    val grouped: List<Pair<AccountKind, List<Accounts.Account>>> = emptyList(),
    val position: Accounts.NetPosition = Accounts.NetPosition(0L, 0L, 0L)
)

class AccountsViewModel(private val repository: FinanceRepository) : ViewModel() {

    val state: StateFlow<AccountsState> = repository.observeAccounts()
        .map { accounts ->
            AccountsState(
                // Grouped in the order the kinds are declared — cash, then credit, then loans, then
                // investments — which is roughly the order somebody cares about them in on a Tuesday.
                grouped = AccountKind.entries
                    .map { kind -> kind to accounts.filter { it.kind == kind } }
                    .filter { it.second.isNotEmpty() },
                position = Accounts.netPosition(accounts)
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccountsState())

    fun setIncluded(accountId: String, include: Boolean) {
        viewModelScope.launch { repository.setAccountIncluded(accountId, include) }
    }

    class Factory(private val repository: FinanceRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AccountsViewModel(repository) as T
    }
}

/**
 * Accounts: the register, grouped by which way the money points.
 *
 * The grouping is the point rather than decoration. A flat list of balances with a credit card in
 * the middle of it is the exact shape that makes people add up the wrong number, and the headings
 * ("Cash", "Credit", "Loans") say which side of the line each group is on before any figure is read.
 */
@Composable
fun AccountsScreen(vm: AccountsViewModel, onOpenAccount: (String) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()

    if (state.grouped.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(
                headline = "No accounts yet",
                detail = "Connect an institution and its accounts appear here with their balances."
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SectionCard(title = "Net", subtitle = "Held, less owed.") {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Figure(
                        "Net",
                        suiteMoney(state.position.netCents, withCents = false),
                        emphasis = true,
                        colour = if (state.position.netCents < 0L) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                }
            }
        }

        state.grouped.forEach { (kind, accounts) ->
            item(key = "head-${kind.key}") {
                val subtotal = accounts.filter { it.includeInPicture && !it.closed }
                    .sumOf { it.balance.spendable(kind) }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(kind.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        suiteMoney(subtotal, withCents = false),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(accounts, key = { it.id }) { account ->
                AccountRow(
                    account = account,
                    onOpen = { onOpenAccount(account.id) },
                    onSetIncluded = { vm.setIncluded(account.id, it) }
                )
            }
        }

        item {
            Spacer(Modifier.height(8.dp))
            Text(
                "Turning an account off keeps it here and keeps refreshing it — it just stops " +
                    "counting towards the figures on the Picture screen. Useful for a business " +
                    "account or one you hold for somebody else.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AccountRow(
    account: Accounts.Account,
    onOpen: () -> Unit,
    onSetIncluded: (Boolean) -> Unit
) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column(Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        account.displayName(),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    val detail = buildString {
                        if (account.closed) append("Closed · ")
                        append(account.kind.label)
                        // "Available" is only shown where it differs from the ledger figure, which is
                        // the only time it tells you anything — and when it does, it is the number
                        // the forecast is actually built from.
                        account.balance.availableCents
                            ?.takeIf { !account.kind.owed && it != account.balance.currentCents }
                            ?.let { append(" · ${suiteMoney(it)} available") }
                    }
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    suiteMoney(account.balance.currentCents),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (account.kind.owed && account.balance.currentCents > 0L) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
            }

            account.balance.utilisation()?.let { used ->
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { used.toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "${(used * 100).roundToInt()}% of the limit used" +
                        // Thirty percent is the threshold most credit scoring models notice, and it
                        // is the one fact about a card balance worth putting on a list row.
                        if (used > 0.3) " — over the 30% most scoring models notice" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (used > 0.3) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Count towards the picture", style = MaterialTheme.typography.labelMedium)
                Switch(checked = account.includeInPicture, onCheckedChange = onSetIncluded)
            }
        }
    }
}
