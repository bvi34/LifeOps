package com.finance.app.ui.due

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.finance.app.data.prefs.FinancePrefs
import com.finance.app.data.repository.BillPublisher
import com.finance.app.data.repository.FinanceRepository
import com.finance.app.logic.Bills
import com.finance.app.ui.common.EmptyState
import com.finance.app.ui.common.SectionCard
import com.finance.app.ui.common.SourceChip
import com.finance.app.ui.common.formatFullDay
import com.finance.app.ui.common.relativeDay
import com.finance.app.ui.common.statusColour
import com.operations.suite.ui.fields.suiteMoney
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

data class DueState(
    val bills: List<Bills.Bill> = emptyList(),
    val accountNames: Map<String, String> = emptyMap(),
    val today: LocalDate = LocalDate.now()
) {
    val outstanding: List<Bills.Bill> get() = bills.filter { !it.paid }
    val overdue: List<Bills.Bill> get() = bills.filter { it.status(today) == Bills.Status.OVERDUE }
    val totalOutstandingCents: Long get() = outstanding.sumOf { it.amountCents }
}

class DueViewModel(
    private val repository: FinanceRepository,
    private val publisher: BillPublisher,
    prefs: FinancePrefs,
    private val today: () -> LocalDate = { LocalDate.now() }
) : ViewModel() {

    private val horizon = prefs.horizonDays.toLong()

    val state: StateFlow<DueState> = repository
        .observePicture(today().minusMonths(3), today().plusMonths(6))
        .map { picture ->
            DueState(
                bills = Bills.upcoming(picture.bills, today(), horizon),
                accountNames = picture.accounts.associate { it.id to it.displayName() },
                today = today()
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DueState())

    /**
     * Turn the week off for one bill.
     *
     * Then run a round, so the task that is already on the week comes back off in the same breath.
     * Waiting for the next automatic round would leave somebody looking at a task they just told the
     * app to stop asking for.
     */
    fun setPublishToWeek(billId: String, publish: Boolean) {
        viewModelScope.launch {
            repository.setBillPublishToWeek(billId, publish)
            publisher.round()
        }
    }

    class Factory(
        private val repository: FinanceRepository,
        private val publisher: BillPublisher,
        private val prefs: FinancePrefs
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            DueViewModel(repository, publisher, prefs) as T
    }
}

/**
 * Due: everything with a date on it, in the order it needs attention.
 *
 * Two things about this screen are unusual for a bill list and both are deliberate.
 *
 * **Paid bills stay.** A list that empties itself as the month goes on looks like the app forgot;
 * "the insurance went out on the 3rd" is exactly as useful as "the insurance goes out on the 3rd",
 * and it is the only way to see at a glance that a month is genuinely handled.
 *
 * **Nothing here has a "mark as paid" button.** A bill is settled when a matching payment turns up in
 * the account, or when its LifeOps task is ticked — both of which are facts rather than intentions.
 * A button would let the list say a bill was paid when it wasn't, which is the one thing a screen
 * like this must never be able to do.
 */
@Composable
fun DueScreen(vm: DueViewModel, onOpenConnections: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()

    if (state.bills.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(
                headline = "Nothing due that we know of",
                detail = "Bills appear here two ways: a credit card or loan statement puts a real " +
                    "date on one, and a charge that has come round three times gets predicted. " +
                    "Connect an account and give it a month."
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { Summary(state) }
        items(state.bills, key = { it.id }) { bill ->
            BillRow(
                bill = bill,
                accountName = state.accountNames[bill.accountId],
                today = state.today,
                onSetPublish = { vm.setPublishToWeek(bill.id, it) }
            )
        }
        item {
            Spacer(Modifier.height(8.dp))
            Text(
                "Finance puts each of these on your LifeOps week as a task, dated the day it falls " +
                    "due, and ticks it off by itself when the payment lands. Untick one to leave it " +
                    "off your week — it stays on this list either way.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun Summary(state: DueState) {
    val overdue = state.overdue.size
    SectionCard(
        title = if (overdue > 0) "$overdue overdue" else "${state.outstanding.size} still to pay",
        subtitle = if (overdue > 0) {
            "Nothing has been matched to a payment for these."
        } else {
            "Between now and the end of the window."
        }
    ) {
        Text(
            suiteMoney(state.totalOutstandingCents, withCents = false),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = if (overdue > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun BillRow(
    bill: Bills.Bill,
    accountName: String?,
    today: LocalDate,
    onSetPublish: (Boolean) -> Unit
) {
    val status = bill.status(today)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        bill.payee,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        // A paid bill is struck through rather than removed or greyed to
                        // illegibility: it is still a record of the month, just a finished one.
                        textDecoration = if (bill.paid) TextDecoration.LineThrough else null
                    )
                    Text(
                        buildString {
                            append(relativeDay(bill.dueDate, today))
                            append(" · ")
                            append(formatFullDay(bill.dueDate))
                            accountName?.let { append(" · $it") }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = statusColour(status)
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        suiteMoney(bill.amountCents),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    // The minimum is shown beside the balance rather than instead of it. Leading
                    // with the minimum is how a card balance becomes permanent.
                    bill.minimumCents?.let {
                        Text(
                            "min ${suiteMoney(it)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SourceChip(bill.source)
                Spacer(Modifier.weight(1f))
                if (bill.paid) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(0.dp))
                    Text(
                        "  Paid ${relativeDay(bill.paidOn!!, today).lowercase()}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text("On my week", style = MaterialTheme.typography.labelMedium)
                    Checkbox(checked = bill.publishToWeek, onCheckedChange = onSetPublish)
                }
            }
        }
    }
}
