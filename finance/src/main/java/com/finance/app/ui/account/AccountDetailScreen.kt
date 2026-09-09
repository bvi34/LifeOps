package com.finance.app.ui.account

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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.finance.app.logic.Accounts
import com.finance.app.logic.Bills
import com.finance.app.logic.CashFlow
import com.finance.app.logic.Transaction
import com.finance.app.ui.common.EmptyState
import com.finance.app.ui.common.Figure
import com.finance.app.ui.common.SectionCard
import com.finance.app.ui.common.SourceChip
import com.finance.app.ui.common.formatDay
import com.finance.app.ui.common.relativeDay
import com.finance.app.ui.common.statusColour
import com.operations.backupkit.AppId
import com.operations.suite.ui.fields.suiteMoney
import com.repository.app.logic.DocumentKind
import com.repository.app.ui.attach.DocumentsPanel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.roundToInt

data class AccountDetailState(
    val account: Accounts.Account? = null,
    val activity: List<Transaction> = emptyList(),
    val bills: List<Bills.Bill> = emptyList(),
    val months: List<CashFlow.Summary> = emptyList(),
    val today: LocalDate = LocalDate.now()
)

class AccountDetailViewModel(
    private val repository: FinanceRepository,
    private val accountId: String,
    private val today: () -> LocalDate = { LocalDate.now() }
) : ViewModel() {

    val state: StateFlow<AccountDetailState> = repository
        .observePicture(today().minusMonths(6), today().plusMonths(3))
        .map { picture ->
            // Filtered here rather than queried per account, because the picture is already loaded
            // for every other screen and a second query would fetch rows this one already holds.
            val mine = picture.transactions.filter { it.accountId == accountId }
            AccountDetailState(
                account = picture.accounts.firstOrNull { it.id == accountId },
                activity = mine,
                bills = picture.bills
                    .filter { it.accountId == accountId }
                    .sortedWith(compareBy({ it.paid }, { it.dueDate })),
                // One account is one currency, so this roll-up needs no base-currency filter —
                // and unlike the cross-account screens it is correct for a foreign account too.
                months = CashFlow.byMonth(mine, today(), months = 6),
                today = today()
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccountDetailState())

    fun setIncluded(include: Boolean) {
        viewModelScope.launch { repository.setAccountIncluded(accountId, include) }
    }

    class Factory(
        private val repository: FinanceRepository,
        private val accountId: String
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AccountDetailViewModel(repository, accountId) as T
    }
}

/**
 * One account: what is in it, what is due out of it, what has moved through it, and its paperwork.
 *
 * This is a route rather than a fifth tab, for the reason Maintenance gives about an asset's page:
 * it is one thing rather than a list, and the back gesture should return you to whichever list you
 * came from.
 *
 * The **Documents** section is the one place Finance touches `:repository`, and it is the reason
 * that dependency exists. A mortgage statement, a payoff letter, a 1099, the letter saying the rate
 * changed — money arrives with paperwork, and a finance app that grew its own document store would
 * be the second place in the suite a household had to remember to look. So the rows live on the
 * shelf, findable from Repository without knowing they were filed here, and are worked on from both
 * ends.
 */
@Composable
fun AccountDetailScreen(vm: AccountDetailViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val account = state.account

    if (account == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(
                headline = "That account isn't here any more",
                detail = "It may have been closed at the bank, or its connection removed."
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "balance") { BalanceCard(account, vm::setIncluded) }

        if (state.bills.isNotEmpty()) {
            item(key = "due") { BillsCard(state) }
        }

        item(key = "flow") { FlowCard(state) }

        item(key = "documents") {
            SectionCard(title = "Documents") {
                DocumentsPanel(
                    appKey = AppId.FINANCE.key,
                    recordKey = account.id,
                    recordLabel = account.displayName(),
                    kinds = FINANCE_KINDS,
                    emptyLine = "The statement, the payoff letter, the 1099, the notice that the " +
                        "rate changed. Filed here, findable in Repository."
                )
            }
        }

        item(key = "activity-head") {
            Text(
                "Activity",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        items(state.activity, key = { it.id }) { row -> ActivityRow(row) }

        if (state.activity.isEmpty()) {
            item(key = "activity-empty") {
                Text(
                    "Nothing in the last six months.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun BalanceCard(account: Accounts.Account, onSetIncluded: (Boolean) -> Unit) {
    SectionCard(
        title = account.displayName(),
        subtitle = buildString {
            append(account.kind.label)
            account.officialName?.takeIf { it != account.name }?.let { append(" · $it") }
            if (account.closed) append(" · closed")
        }
    ) {
        Figure(
            label = if (account.kind.owed) "Owed" else "Balance",
            value = suiteMoney(account.balance.currentCents),
            emphasis = true,
            colour = if (account.kind.owed && account.balance.currentCents > 0L) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            }
        )

        // Available is shown only where it differs from the ledger figure, which is the only time it
        // says anything — and when it does, it is the number the forecast is actually built from.
        account.balance.availableCents
            ?.takeIf { !account.kind.owed && it != account.balance.currentCents }
            ?.let {
                Spacer(Modifier.height(8.dp))
                Figure(
                    label = "Available now",
                    value = suiteMoney(it),
                    note = "What the forecast plans with. The difference is holds and unsettled items."
                )
            }

        account.balance.utilisation()?.let { used ->
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { used.toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "${(used * 100).roundToInt()}% of the limit used" +
                    if (used > 0.3) " — over the 30% most scoring models notice" else "",
                style = MaterialTheme.typography.labelSmall,
                color = if (used > 0.3) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))
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

@Composable
private fun BillsCard(state: AccountDetailState) {
    SectionCard(title = "Due out of this", subtitle = "Bills filed against this account.") {
        state.bills.forEachIndexed { index, bill ->
            if (index > 0) HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f)) {
                    Text(bill.payee, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (bill.paid) {
                            "Paid ${relativeDay(bill.paidOn!!, state.today).lowercase()}"
                        } else {
                            relativeDay(bill.dueDate, state.today)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColour(bill.status(state.today))
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        suiteMoney(bill.amountCents),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    SourceChip(bill.source)
                }
            }
        }
    }
}

@Composable
private fun FlowCard(state: AccountDetailState) {
    val month = state.months.lastOrNull()
    SectionCard(
        title = "Through this account",
        subtitle = "This month, with transfers and card payments left out."
    ) {
        if (month == null) {
            Text(
                "Nothing yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@SectionCard
        }
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Figure("In", suiteMoney(month.inCents, withCents = false))
            Figure("Out", suiteMoney(month.outCents, withCents = false))
        }
        if (month.byCategory.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            month.byCategory.take(4).forEach { line ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(line.category.label, style = MaterialTheme.typography.bodySmall)
                    Text(
                        suiteMoney(line.amountCents, withCents = false),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun ActivityRow(row: Transaction) {
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
                fontStyle = if (row.pending) FontStyle.Italic else FontStyle.Normal
            )
            Text(
                buildString {
                    append(formatDay(row.date))
                    append(" · ")
                    append(row.category.label)
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
            color = if (row.inflow) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * What Finance usually files, offered first in the picker.
 *
 * Statements lead because they are what an account arrives with every month; the rest are what turns
 * up once and matters years later — the letter that changed the rate, the payoff, the tax form.
 */
private val FINANCE_KINDS = listOf(
    DocumentKind.STATEMENT,
    DocumentKind.CORRESPONDENCE,
    DocumentKind.CONTRACT,
    DocumentKind.RECEIPT,
    DocumentKind.REPORT,
    DocumentKind.RECORD
)
