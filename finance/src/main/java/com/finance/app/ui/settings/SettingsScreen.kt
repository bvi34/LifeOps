package com.finance.app.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.finance.app.data.prefs.FinancePrefs
import com.finance.app.ui.common.SectionCard
import com.operations.suite.ui.fields.SuiteMoneyField
import com.operations.suite.ui.fields.suiteMoney
import com.operations.suitekit.SuiteMoney

/**
 * Finance's own settings — three decisions that shape what the Picture and Due screens say.
 *
 * There are deliberately only three, and none of them is a toggle for a feature. Everything else
 * about this app is either a fact from a bank (not a preference) or a decision the app has already
 * taken a position on (leading with the statement balance rather than the minimum; refusing to
 * project income). A setting for those would be an app that could not make up its mind about its own
 * claims.
 *
 * The suite's *appearance* is not here either: that lives in the Operations Sandbox gear and paints
 * every app at once.
 */
@Composable
fun FinanceSettingsScreen(prefs: FinancePrefs) {
    var floorText by remember { mutableStateOf(SuiteMoney.plain(prefs.floorCents).takeIf { prefs.floorCents > 0L }.orEmpty()) }
    var floorCents by remember { mutableStateOf(prefs.floorCents) }
    var horizon by remember { mutableStateOf(prefs.horizonDays) }
    var includeSpending by remember { mutableStateOf(prefs.forecastIncludesSpending) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SectionCard(
                title = "Your floor",
                subtitle = "The balance you don't want to go under."
            ) {
                SuiteMoneyField(
                    label = "Floor",
                    text = floorText,
                    onChange = { text, cents ->
                        floorText = text
                        // A blank field means "no floor" rather than "zero typed": the two behave
                        // the same today and would diverge the moment anything treated an explicit
                        // zero as a deliberate choice.
                        val value = if (text.isBlank()) 0L else cents ?: floorCents
                        floorCents = value
                        prefs.floorCents = value
                    },
                    supporting = "Leave it blank and the only alarm is going overdrawn."
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "This is what turns the forecast into something you can act on early. " +
                        "\"You'll dip under ${suiteMoney(floorCents.coerceAtLeast(50_000L), withCents = false)} " +
                        "on the 28th\" gives you a week to move money; \"you'll be overdrawn on the " +
                        "28th\" is news that arrives too late to use.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            SectionCard(
                title = "How far ahead",
                subtitle = "What the Due list and the forecast reach to."
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HORIZONS.forEach { (days, label) ->
                        FilterChip(
                            selected = horizon == days,
                            onClick = {
                                horizon = days
                                prefs.horizonDays = days
                            },
                            label = { Text(label) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Six weeks covers a monthly cycle and a bit of the next, which is usually the " +
                        "useful window. Going much longer fills the list with things you can't do " +
                        "anything about yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            SectionCard(
                title = "What the forecast counts",
                subtitle = "Bills always. Everyday spending, optionally."
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Include ordinary spending", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = includeSpending,
                        onCheckedChange = {
                            includeSpending = it
                            prefs.forecastIncludesSpending = it
                        }
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    if (includeSpending) {
                        "On: a flat daily rate from your last complete month is subtracted alongside " +
                            "the bills. Not because Tuesdays and Saturdays cost the same, but because " +
                            "a day-of-week model would be false precision on a number whose job is to " +
                            "be roughly right for a fortnight."
                    } else {
                        "Off: the line shows scheduled bills only. Honest, and optimistic — it says " +
                            "nothing about the groceries."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            SectionCard(title = "What isn't settable here") {
                Text(
                    "Money coming in is never projected, a card's bill always leads with the " +
                        "statement balance rather than the minimum, and a bill is only ever marked " +
                        "paid by a payment landing or by you ticking its task. Those are positions " +
                        "this app takes rather than preferences — a switch for them would be an app " +
                        "that couldn't stand behind its own numbers.\n\n" +
                        "Colours and light/dark live in the Operations Sandbox gear, which paints " +
                        "every app in the suite at once.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * The horizons on offer.
 *
 * Four fixed choices rather than a free number, because the difference between 45 and 47 days is not
 * a decision anybody has, and a text field would invite one.
 */
private val HORIZONS = listOf(
    14 to "2 weeks",
    30 to "A month",
    45 to "6 weeks",
    90 to "3 months"
)
