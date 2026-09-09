package com.finance.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.finance.app.logic.AccountKind
import com.finance.app.logic.Bills
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

private val dayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
private val fullDayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())

fun formatDay(date: LocalDate): String = dayFormat.format(date)

fun formatFullDay(date: LocalDate): String = fullDayFormat.format(date)

/**
 * A date as somebody would say it out loud.
 *
 * "In 3 days" beats "12 Sep" on the Due screen and loses to it on a statement, so both exist and the
 * screens choose. The near-term words matter most: a bill that says "tomorrow" gets acted on and one
 * that says "12 Sep" gets scrolled past, even when they are the same Tuesday.
 */
fun relativeDay(date: LocalDate, today: LocalDate): String {
    val days = ChronoUnit.DAYS.between(today, date)
    return when {
        days == 0L -> "Today"
        days == 1L -> "Tomorrow"
        days == -1L -> "Yesterday"
        days in 2..6 -> "In $days days"
        days in -6..-2 -> "${-days} days ago"
        else -> formatDay(date)
    }
}

/**
 * The colour a bill's urgency is drawn in.
 *
 * Only two states get a colour: overdue is the error colour, due-soon is the app's own accent.
 * Everything else is ordinary text — a list where every row is coloured is a list where no colour
 * means anything.
 */
@Composable
fun statusColour(status: Bills.Status): Color = when (status) {
    Bills.Status.OVERDUE -> MaterialTheme.colorScheme.error
    Bills.Status.DUE_SOON -> MaterialTheme.colorScheme.primary
    Bills.Status.UPCOMING -> MaterialTheme.colorScheme.onSurface
    Bills.Status.PAID -> MaterialTheme.colorScheme.onSurfaceVariant
}

/**
 * The chip that says where a bill's figure came from.
 *
 * It is on every bill rather than only on the predicted ones, because a badge that appears only when
 * something is uncertain is a badge people learn to skip. Saying "from your statement" on the ones
 * that are certain is what makes "predicted" mean something on the ones that aren't.
 */
@Composable
fun SourceChip(source: Bills.Source, modifier: Modifier = Modifier) {
    AssistChip(
        onClick = {},
        enabled = false,
        label = { Text(source.label, style = MaterialTheme.typography.labelSmall) },
        colors = AssistChipDefaults.assistChipColors(
            disabledLabelColor = when (source) {
                Bills.Source.STATEMENT -> MaterialTheme.colorScheme.onSurface
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
        ),
        modifier = modifier
    )
}

/** A figure with its label under it — the unit the summary screens are built from. */
@Composable
fun Figure(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    emphasis: Boolean = false,
    colour: Color = MaterialTheme.colorScheme.onSurface,
    note: String? = null
) {
    Column(modifier) {
        Text(
            value,
            style = if (emphasis) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = colour
        )
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (note != null) {
            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A titled card, which is most of this app's chrome. */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    if (subtitle != null) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                trailing?.invoke()
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

/**
 * What an empty screen says.
 *
 * Every one of these names the next action rather than describing the absence. "No accounts yet" is
 * a statement of fact that leaves somebody stuck on the screen that already told them so.
 */
@Composable
fun EmptyState(headline: String, detail: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(headline, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** The heading a group of accounts sits under. */
fun kindHeading(kind: AccountKind): String = kind.label
