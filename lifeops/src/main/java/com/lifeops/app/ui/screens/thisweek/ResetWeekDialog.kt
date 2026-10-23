@file:OptIn(ExperimentalMaterial3Api::class)

package com.lifeops.app.ui.screens.thisweek

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lifeops.app.util.DateUtil
import com.lifeops.app.util.WeekReset

private const val REASON_MAX = 140

/** "Weeks of Mar 2 – Oct 12", or the single week's date. Null when nothing will be scarred. */
private fun scarRange(plan: WeekReset.Plan): String? {
    val first = plan.firstIndex ?: return null
    val last = plan.lastIndex ?: return null
    val from = DateUtil.formatDate(DateUtil.weekStartForIndex(first).toString())
    val to = DateUtil.formatDate(DateUtil.weekStartForIndex(last).toString())
    return if (first == last) "Week of $from" else "Weeks of $from – $to"
}

/** Offered at the top of This Week when weeks went by untouched. */
@Composable
internal fun ResetWeekBanner(plan: WeekReset.Plan?, onReset: () -> Unit) {
    val count = plan?.scarCount ?: 0
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
                "$count week${if (count == 1) "" else "s"} went by untouched.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onReset) {
                Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Reset to current week")
            }
        }
    }
}

/**
 * Confirmation for "Reset to current week": how many weeks get scarred, whether it's a true reset
 * (weekly / repeating tasks set aside), and the reason — which is kept beside each scar.
 */
@Composable
internal fun ResetWeekDialog(
    plan: WeekReset.Plan?,
    inFlight: Boolean,
    onConfirm: (reason: String, trueReset: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var trueReset by remember { mutableStateOf(false) }
    var reason by remember { mutableStateOf("") }
    val count = plan?.scarCount ?: 0

    AlertDialog(
        onDismissRequest = { if (!inFlight) onDismiss() },
        title = { Text("Reset to current week", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (plan == null) {
                    Text("Counting weeks…", style = MaterialTheme.typography.bodySmall)
                } else if (count == 0) {
                    Text(
                        "No missed weeks to scar — you're already on the current week.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    Text(
                        "How many weeks are being scarred: $count",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    scarRange(plan)?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                    Text(
                        "Each is closed as skipped and leaves a grey scar on the Growth Record, with your " +
                            "reason beside it. This week stays open as your fresh start.",
                        style = MaterialTheme.typography.bodySmall
                    )

                    HorizontalDivider()

                    Text(
                        "True Reset: stop and don't use weekly or repeating tasks for the week",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = trueReset,
                            onClick = { trueReset = true },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                        ) { Text("Yes") }
                        SegmentedButton(
                            selected = !trueReset,
                            onClick = { trueReset = false },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                        ) { Text("No") }
                    }
                    if (trueReset) {
                        Text(
                            "Weekly and repeating tasks are skipped for the scarred weeks and this week. " +
                                "They come back next week as usual.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }

                    OutlinedTextField(
                        value = reason,
                        onValueChange = { reason = it.take(REASON_MAX) },
                        label = { Text("Reason this was needed") },
                        supportingText = { Text("${reason.length}/$REASON_MAX") },
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = count > 0 && reason.isNotBlank() && !inFlight,
                onClick = { onConfirm(reason.trim(), trueReset) }
            ) { Text(if (count > 0) "Scar $count week${if (count == 1) "" else "s"}" else "Reset") }
        },
        dismissButton = {
            TextButton(enabled = !inFlight, onClick = onDismiss) { Text("Cancel") }
        }
    )
}
