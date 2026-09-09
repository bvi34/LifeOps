package com.finance.app.ui.due

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.finance.app.data.prefs.FinancePrefs
import com.finance.app.data.repository.BillPublisher
import com.finance.app.data.repository.FinanceRepository
import com.finance.app.logic.Accounts
import com.finance.app.logic.Bills
import com.finance.app.ui.common.EmptyState
import com.finance.app.ui.common.SectionCard
import com.finance.app.ui.common.SourceChip
import com.finance.app.ui.common.formatFullDay
import com.finance.app.ui.common.relativeDay
import com.finance.app.ui.common.statusColour
import com.operations.suite.ui.fields.SuiteMoneyField
import com.operations.suite.ui.fields.SuiteTextField
import com.operations.suite.ui.fields.suiteMoney
import com.operations.suite.ui.pickers.SuiteDatePickerDialog
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

data class DueState(
    val bills: List<Bills.Bill> = emptyList(),
    val accountNames: Map<String, String> = emptyMap(),
    /** The accounts a typed bill can be filed against, richest cash first. */
    val fundingAccounts: List<Accounts.Account> = emptyList(),
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
                fundingAccounts = Accounts.fundingAccounts(picture.accounts),
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

    /**
     * Write down a bill nobody's bank is going to tell us about.
     *
     * This is the gap Plaid cannot fill by construction: rent paid by standing order from an account
     * that was never connected, a quarterly tax estimate, the loan from a relative. Without it the
     * Due screen is only ever as complete as the connections behind it, which is exactly the kind of
     * partial truth that makes somebody stop trusting the whole list.
     *
     * A round follows, so a bill due inside the lead window reaches the week immediately rather than
     * on whatever the next refresh happens to be.
     */
    fun addManualBill(
        accountId: String,
        payee: String,
        due: LocalDate,
        amountCents: Long,
        recurrenceMonths: Int?
    ) {
        viewModelScope.launch {
            repository.addManualBill(
                accountId = accountId,
                payee = payee,
                due = due,
                amountCents = amountCents,
                recurrenceMonths = recurrenceMonths
            )
            publisher.round()
        }
    }

    /**
     * Stop a typed bill, including the occurrences ahead of it.
     *
     * The tasks come off the week **first**, and through the publisher rather than by deleting rows
     * out from under it: a round mid-flight would otherwise republish the very bill being removed a
     * moment after its task was withdrawn.
     */
    fun deleteManualBill(bill: Bills.Bill) {
        viewModelScope.launch {
            val orphaned = repository.deleteManualSeries(bill.accountId, bill.merchantKey.orEmpty())
            publisher.retire(orphaned)
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
fun DueScreen(vm: DueViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<Bills.Bill?>(null) }

    // The add action lives on the empty state too, and that is the point of putting it there: a
    // household with nothing connected yet can still write down the rent, and the screen stops
    // being a dead end while Plaid credentials are being sorted out.
    if (state.bills.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                EmptyState(
                    headline = "Nothing due that we know of",
                    detail = "Bills appear here three ways: a card or loan statement puts a real " +
                        "date on one, a charge that has come round three times gets predicted, " +
                        "and anything else you can write down yourself."
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = { adding = true }) { Text("Add a bill") }
            }
        }
        AddBillDialog(state, adding, onDismiss = { adding = false }, onAdd = vm::addManualBill)
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
                onSetPublish = { vm.setPublishToWeek(bill.id, it) },
                onDelete = { confirmDelete = bill }
            )
        }
        item {
            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Add a bill yourself")
            }
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

    AddBillDialog(state, adding, onDismiss = { adding = false }, onAdd = vm::addManualBill)

    confirmDelete?.let { bill ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Stop asking about ${bill.payee}?") },
            text = {
                Text(
                    if ((bill.recurrenceMonths ?: 0) > 0) {
                        "This one and the repeats ahead of it go, along with any tasks they put on " +
                            "your week. Occurrences you've already paid stay — they're a record of " +
                            "money that actually left."
                    } else {
                        "It goes, along with any task it put on your week."
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteManualBill(bill)
                    confirmDelete = null
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Keep it") } }
        )
    }
}

/**
 * Writing down a bill nobody's bank will report.
 *
 * The **repeat** is not an optional extra here — it is most of the value. A one-off manual bill is
 * useful for a quarterly tax estimate and useless for rent, and a household that had to re-type the
 * rent every month would stop after two. So the dialog asks how often, and defaults to monthly,
 * because that is what almost everything typed into a screen like this turns out to be.
 */
@Composable
private fun AddBillDialog(
    state: DueState,
    visible: Boolean,
    onDismiss: () -> Unit,
    onAdd: (accountId: String, payee: String, due: LocalDate, amountCents: Long, recurrenceMonths: Int?) -> Unit
) {
    if (!visible) return

    var payee by remember { mutableStateOf("") }
    var amountText by remember { mutableStateOf("") }
    var amountCents by remember { mutableStateOf<Long?>(null) }
    var due by remember { mutableStateOf(state.today.plusDays(7)) }
    var repeatMonths by remember { mutableStateOf<Int?>(1) }
    var accountId by remember { mutableStateOf(state.fundingAccounts.firstOrNull()?.id) }
    var pickingDate by remember { mutableStateOf(false) }

    if (pickingDate) {
        SuiteDatePickerDialog(
            initial = due,
            onPick = { picked ->
                picked?.let { due = it }
                pickingDate = false
            },
            onDismiss = { pickingDate = false }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a bill") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SuiteTextField(
                    label = "Who's paid",
                    value = payee,
                    onValueChange = { payee = it },
                    supporting = "Write it the way your bank does if you can — that's how Finance " +
                        "spots the payment and ticks it off."
                )
                SuiteMoneyField(
                    label = "Amount",
                    text = amountText,
                    onChange = { text, cents -> amountText = text; amountCents = cents }
                )

                Text("First due", style = MaterialTheme.typography.labelLarge)
                OutlinedButton(onClick = { pickingDate = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(formatFullDay(due))
                }

                Text("Repeats", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    REPEATS.forEach { (months, label) ->
                        FilterChip(
                            selected = repeatMonths == months,
                            onClick = { repeatMonths = months },
                            label = { Text(label) }
                        )
                    }
                }

                if (state.fundingAccounts.isNotEmpty()) {
                    Text("Paid from", style = MaterialTheme.typography.labelLarge)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        state.fundingAccounts.forEach { account ->
                            FilterChip(
                                selected = accountId == account.id,
                                onClick = { accountId = account.id },
                                label = { Text(account.displayName()) }
                            )
                        }
                    }
                } else {
                    // No connected cash account is a perfectly normal state for a typed bill — it is
                    // the case the feature exists for — so it is explained rather than blocked.
                    Text(
                        "No connected account to file this against yet, which is fine: it'll sit on " +
                            "its own and go on your week all the same. Connect the account it comes " +
                            "out of later and Finance will start matching payments to it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = payee.isNotBlank() && (amountCents ?: 0L) > 0L,
                onClick = {
                    onAdd(
                        accountId ?: UNFILED_ACCOUNT,
                        payee,
                        due,
                        amountCents ?: 0L,
                        repeatMonths
                    )
                    onDismiss()
                }
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** "Monthly" and friends, for a row that repeats. Null for a one-off — nothing to say. */
private fun repeatLabel(months: Int?): String? = when (months) {
    null, 0 -> null
    1 -> "monthly"
    3 -> "quarterly"
    12 -> "yearly"
    else -> "every $months months"
}

/** How often a typed bill can be set to come back. Monthly is the default because it usually is. */
private val REPEATS = listOf(
    1 to "Monthly",
    3 to "Quarterly",
    12 to "Yearly",
    null to "Just once"
)

/**
 * The account a typed bill is filed under when there is no connected one to choose.
 *
 * A sentinel rather than a null column: bills key their identity off an account id
 * ([Bills.manualId]), and making that nullable would mean every id, every lookup and every series
 * grouping in the module growing a null branch to serve one case. A row that names an account this
 * app does not hold already renders without one, which is exactly the behaviour wanted here.
 */
private const val UNFILED_ACCOUNT = "unfiled"

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
    onSetPublish: (Boolean) -> Unit,
    onDelete: () -> Unit
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
                            repeatLabel(bill.recurrenceMonths)?.let { append(" · $it") }
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
                    // Only typed bills can be removed. A statement bill is the institution's word
                    // and a predicted one is re-derived on every refresh, so "delete" on either
                    // would be a button that undoes itself within the hour — untick the week
                    // instead, which is a preference the app actually keeps.
                    if (bill.source == Bills.Source.MANUAL) {
                        TextButton(onClick = onDelete) { Text("Remove") }
                    }
                    Text("On my week", style = MaterialTheme.typography.labelMedium)
                    Checkbox(checked = bill.publishToWeek, onCheckedChange = onSetPublish)
                }
            }
        }
    }
}
