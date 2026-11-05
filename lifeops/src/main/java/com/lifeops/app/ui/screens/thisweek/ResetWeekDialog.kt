@file:OptIn(ExperimentalMaterial3Api::class)

package com.lifeops.app.ui.screens.thisweek

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lifeops.app.util.DateUtil
import com.lifeops.app.util.WeekReset

private const val REASON_MAX = 140

/** "Weeks of Mar 2 – Oct 12", or the single week's date. Null for an empty range. */
private fun scarRange(range: IntRange): String? {
    if (range.isEmpty()) return null
    val from = DateUtil.formatDate(DateUtil.weekStartForIndex(range.first).toString())
    val to = DateUtil.formatDate(DateUtil.weekStartForIndex(range.last).toString())
    return if (range.first == range.last) "Week of $from" else "Weeks of $from – $to"
}

private fun weeks(n: Int) = "$n week${if (n == 1) "" else "s"}"

/** Offered at the top of This Week when the weeks right before this one went untouched. */
@Composable
internal fun ResetWeekBanner(plan: WeekReset.Plan?, onReset: () -> Unit) {
    val count = plan?.suggestedCount ?: 0
    if (count == 0) return
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "${weeks(count)} went by untouched.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onReset) {
                Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Scar past weeks")
            }
        }
    }
}

/**
 * "Scar past weeks": pick how many of the weeks before this one to write off, whether to clear
 * weekly / repeating tasks, and the reason — which is kept beside each scar.
 */
@Composable
internal fun ResetWeekDialog(
    plan: WeekReset.Plan?,
    inFlight: Boolean,
    onConfirm: (count: Int, reason: String, clearRecurring: Boolean) -> Unit,
    onDismiss: () -> Unit,
    /** Preset pick (e.g. from the Growth Record bars); defaults to the suggested quiet run. */
    initialCount: Int? = null
) {
    var clearRecurring by remember { mutableStateOf(false) }
    var reason by remember { mutableStateOf("") }
    val available = plan?.availableCount ?: 0
    // Default to the untouched run right before this week, else just last week.
    var count by remember(plan?.currentIndex, available) {
        mutableIntStateOf((initialCount ?: plan?.suggestedCount ?: 0).coerceAtLeast(1).coerceAtMost(available.coerceAtLeast(1)))
    }
    val range = plan?.range(count) ?: IntRange.EMPTY

    AlertDialog(
        onDismissRequest = { if (!inFlight) onDismiss() },
        title = { Text("Scar past weeks", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (plan == null) {
                    Text("Counting weeks…", style = MaterialTheme.typography.bodySmall)
                } else if (available == 0) {
                    Text(
                        "There are no past weeks yet — this is the first one.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    Text("Mark the last", style = MaterialTheme.typography.bodyMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(enabled = count > 1, onClick = { count-- }) {
                            Icon(Icons.Default.Remove, contentDescription = "Fewer weeks")
                        }
                        Text(
                            weeks(count),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.widthIn(min = 84.dp)
                        )
                        IconButton(enabled = count < available, onClick = { count++ }) {
                            Icon(Icons.Default.Add, contentDescription = "More weeks")
                        }
                    }
                    scarRange(range)?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                    Text(
                        "Each is marked skipped and leaves a grey scar on the Growth Record, with your " +
                            "reason beside it. This week stays open as your fresh start.",
                        style = MaterialTheme.typography.bodySmall
                    )

                    HorizontalDivider()

                    Text("Clear weekly and repeating tasks?", style = MaterialTheme.typography.bodyMedium)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = clearRecurring,
                            onClick = { clearRecurring = true },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                        ) { Text("Yes") }
                        SegmentedButton(
                            selected = !clearRecurring,
                            onClick = { clearRecurring = false },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                        ) { Text("No") }
                    }
                    if (clearRecurring) {
                        Text(
                            "They're skipped this week (unless you've logged time on them) and in the " +
                                "scarred weeks they're no longer counted as missed. They come back next week as usual.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }

                    OutlinedTextField(
                        value = reason,
                        onValueChange = { reason = it.take(REASON_MAX) },
                        label = { Text("Reason") },
                        supportingText = { Text("${reason.length}/$REASON_MAX") },
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = available > 0 && reason.isNotBlank() && !inFlight,
                onClick = { onConfirm(count, reason.trim(), clearRecurring) }
            ) { Text("Scar ${weeks(count)}") }
        },
        dismissButton = {
            TextButton(enabled = !inFlight, onClick = onDismiss) { Text("Cancel") }
        }
    )
}
