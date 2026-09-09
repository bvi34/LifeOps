package com.finance.app.ui.picture

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.finance.app.data.prefs.FinancePrefs
import com.finance.app.data.repository.FinanceRepository
import com.finance.app.logic.Accounts
import com.finance.app.logic.Bills
import com.finance.app.logic.CashFlow
import com.finance.app.logic.Forecast
import com.finance.app.ui.common.EmptyState
import com.finance.app.ui.common.Figure
import com.finance.app.ui.common.SectionCard
import com.operations.suite.ui.fields.rememberCurrencySymbol
import com.operations.suite.ui.fields.suiteMoney
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Everything the front screen shows, computed in one place from one read of the store.
 *
 * The whole state is derived rather than stored: change a balance and every figure here moves,
 * because there is no second copy of any of them. The only inputs are the [FinanceRepository.Picture]
 * and the two preferences that shape the forecast.
 */
data class PictureState(
    val position: Accounts.NetPosition = Accounts.NetPosition(0L, 0L, 0L),
    val thisMonth: CashFlow.Summary? = null,
    val lastMonth: CashFlow.Summary? = null,
    val months: List<CashFlow.Summary> = emptyList(),
    val projection: Forecast.Projection? = null,
    val dueSoon: List<Bills.Bill> = emptyList(),
    val runwayDays: Long? = null,
    val today: LocalDate = LocalDate.now(),
    val hasAnything: Boolean = false,
    val forecastIncludesSpending: Boolean = true
) {
    /** The change in total spending against last month, or null when there's no last month yet. */
    val spendChange: Double?
        get() {
            val now = thisMonth ?: return null
            val before = lastMonth ?: return null
            return CashFlow.change(before.outCents, now.outCents)
        }
}

class PictureViewModel(
    repository: FinanceRepository,
    private val prefs: FinancePrefs,
    private val today: () -> LocalDate = { LocalDate.now() }
) : ViewModel() {

    private val settings = MutableStateFlow(prefs.forecastIncludesSpending)

    val state: StateFlow<PictureState> = repository
        // Fifteen months, which is what the monthly strip and the recurring detector both want. The
        // forecast needs only the recent burn rate, but reading a shorter window here would mean a
        // second query for the same rows.
        .observePicture(today().minusMonths(15), today().plusMonths(3))
        .map { picture -> build(picture, settings.value) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PictureState())

    fun setForecastIncludesSpending(include: Boolean) {
        prefs.forecastIncludesSpending = include
        settings.value = include
    }

    private fun build(picture: FinanceRepository.Picture, includeSpending: Boolean): PictureState {
        val now = today()
        val months = CashFlow.byMonth(picture.transactions, now, months = 6)
        val thisMonth = months.lastOrNull()
        val position = picture.netPosition()

        // The burn rate comes from the last *complete* month rather than from this one. A rate taken
        // from the 3rd of the month is three days of spending divided by three days, which on a
        // month that opened with the rent in it projects a household into destitution by Friday.
        val burnSource = months.dropLast(1).lastOrNull() ?: thisMonth
        val burn = if (includeSpending) burnSource?.dailyBurnCents ?: 0L else 0L

        return PictureState(
            position = position,
            thisMonth = thisMonth,
            lastMonth = months.dropLast(1).lastOrNull(),
            months = months,
            projection = Forecast.project(
                startingCashCents = position.cashCents,
                bills = picture.bills,
                dailyBurnCents = burn,
                today = now,
                horizonDays = prefs.horizonDays.toLong()
            ),
            dueSoon = Bills.upcoming(picture.bills, now, horizonDays = 14L).filter { !it.paid },
            runwayDays = burnSource?.let { CashFlow.runwayDays(position.cashCents, it) },
            today = now,
            hasAnything = picture.accounts.isNotEmpty(),
            forecastIncludesSpending = includeSpending
        )
    }

    class Factory(
        private val repository: FinanceRepository,
        private val prefs: FinancePrefs
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            PictureViewModel(repository, prefs) as T
    }
}

/**
 * The Picture: the app's front door, and the one screen that answers a question rather than showing
 * a list.
 *
 * It leads with the forecast's trough rather than with net worth, and that ordering is the whole
 * design of the screen. Net worth is the number a finance app is *expected* to lead with, and it is
 * almost never the number that changes what somebody does today — it moves slowly, it is dominated
 * by a house nobody is going to sell, and knowing it has gone up 1.2% this month is interesting
 * rather than useful. "At worst $412 on the 28th" is a sentence somebody acts on.
 *
 * Net worth is still here, one card down, because over a year it is the only number that matters.
 */
@Composable
fun PictureScreen(vm: PictureViewModel, onOpenDue: () -> Unit, onOpenConnections: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val symbol = rememberCurrencySymbol()

    if (!state.hasAnything) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(
                headline = "Nothing connected yet",
                detail = "Connect USAA (or any bank Plaid reaches) and Mercury on the Connections " +
                    "screen, and this becomes the picture: what you have, what's due, and the " +
                    "lowest your balance gets before it does."
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { ForecastCard(state, symbol, onOpenDue) }
        item { PositionCard(state) }
        item { MonthCard(state) }
        if (state.months.size > 1) item { TrendCard(state) }
        item {
            SectionCard(
                title = "Where it comes from",
                subtitle = "Every figure above is read from the accounts you connected. Nothing " +
                    "here is typed in, and nothing here is sent anywhere."
            ) {
                Text(
                    "Manage connections",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenConnections)
                        .padding(vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun ForecastCard(state: PictureState, symbol: String, onOpenDue: () -> Unit) {
    val projection = state.projection
    SectionCard(
        title = "Between now and then",
        subtitle = if (state.forecastIncludesSpending) {
            "Bills you know about, plus what you usually spend."
        } else {
            "Bills you know about. Ordinary spending isn't counted."
        }
    ) {
        Text(
            projection?.headline(state.today, symbol) ?: "Nothing scheduled to project against.",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = if (projection?.overdrawn == true) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            }
        )
        Spacer(Modifier.height(4.dp))
        // The sentence above is a floor and says so; this says why it is a floor. Somebody who reads
        // one number off this screen should not be able to mistake it for a prediction.
        Text(
            "Money coming in isn't projected — a paycheque this app promised on your behalf would " +
                "be a promise it can't keep. So the real balance can only be better than this.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (projection != null && projection.committedCents > 0L) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Figure("Committed", suiteMoney(projection.committedCents, withCents = false))
                Figure("Cash now", suiteMoney(state.position.cashCents, withCents = false))
                state.runwayDays?.let { Figure("Runway", "$it days", note = "if nothing came in") }
            }
        }

        if (state.dueSoon.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                "${state.dueSoon.size} due in the next fortnight",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenDue)
                    .padding(vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun PositionCard(state: PictureState) {
    SectionCard(
        title = "What you're worth",
        subtitle = "Everything you hold, less everything you owe."
    ) {
        Figure(
            label = "Net",
            value = suiteMoney(state.position.netCents, withCents = false),
            emphasis = true,
            colour = if (state.position.netCents < 0L) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            }
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Figure("Held", suiteMoney(state.position.assetsCents, withCents = false))
            Figure("Owed", suiteMoney(state.position.liabilitiesCents, withCents = false))
        }
    }
}

@Composable
private fun MonthCard(state: PictureState) {
    val month = state.thisMonth ?: return
    SectionCard(
        title = "This month so far",
        subtitle = "Transfers and card payments left out, so nothing is counted twice."
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Figure("In", suiteMoney(month.inCents, withCents = false))
            Figure("Out", suiteMoney(month.outCents, withCents = false))
            state.spendChange?.let { change ->
                Figure(
                    label = "vs last month",
                    value = (if (change >= 0) "+" else "") + "${(change * 100).roundToInt()}%",
                    colour = if (change > 0.15) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
            }
        }

        if (month.byCategory.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            month.byCategory.take(6).forEach { line ->
                CategoryBar(line, month.outCents)
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

/**
 * One category as a labelled bar.
 *
 * A bar rather than a pie, and six rather than all sixteen. A pie of sixteen slices is a legend
 * somebody has to read twice; six bars in descending order answer "where did it go" at a glance, and
 * the seventh through sixteenth are, by construction, the ones that didn't matter this month.
 */
@Composable
private fun CategoryBar(line: CashFlow.CategoryTotal, total: Long) {
    val share = line.shareOf(total)
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(line.category.label, style = MaterialTheme.typography.bodyMedium)
            Text(
                suiteMoney(line.amountCents, withCents = false),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { share.toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
        )
    }
}

/**
 * Six months of in-and-out as bars.
 *
 * Deliberately not a line chart. A line implies the points between the months mean something, and
 * a month is a bucket rather than a sample — there is no such thing as the spending on the 14th of
 * a six-month trend.
 */
@Composable
private fun TrendCard(state: PictureState) {
    val months = state.months
    val peak = months.maxOfOrNull { maxOf(it.inCents, it.outCents) }?.takeIf { it > 0L } ?: return

    SectionCard(title = "Six months", subtitle = "In against out, month by month.") {
        Row(
            modifier = Modifier.fillMaxWidth().height(120.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            months.forEach { month ->
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom
                ) {
                    Row(
                        modifier = Modifier.height(88.dp),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Bar(month.inCents, peak, MaterialTheme.colorScheme.primary)
                        Bar(month.outCents, peak, MaterialTheme.colorScheme.tertiary)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        monthInitials(month.from),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun Bar(value: Long, peak: Long, colour: Color) {
    val fraction = (value.toFloat() / peak.toFloat()).coerceIn(0f, 1f)
    Box(
        Modifier
            .width(10.dp)
            // A month with nothing in it still gets a sliver, so an empty column reads as "nothing
            // happened" rather than as a rendering failure.
            .height((4 + 84 * fraction).dp)
            .clip(RoundedCornerShape(2.dp))
            .background(colour)
    )
}

/** "Sep" for a month's first day — the label under a bar, in the phone's own language. */
private fun monthInitials(date: LocalDate): String =
    date.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
