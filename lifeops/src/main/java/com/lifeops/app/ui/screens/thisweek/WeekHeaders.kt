@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.lifeops.app.ui.screens.thisweek

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lifeops.app.data.model.WeekProgress
import com.lifeops.app.ui.components.formatMinutes
import com.lifeops.app.ui.theme.parseColor
import com.lifeops.app.util.WeekCapacity

/**
 * The headers that divide the week: how far through it you are, what you committed to, and
 * the aspect and category a run of tasks sits under.
 */

/** Weekly task + time summary: completed/total, a progress bar, total logged time, and the
 *  close-week action (the only place the week is closed now that the old top app bar is gone). */
@Composable
internal fun WeekProgressHeader(
    progress: WeekProgress,
    capacity: WeekCapacity? = null,
    canCloseWeek: Boolean = false,
    onCloseWeek: () -> Unit = {}
) {
    if (progress.totalCount == 0) return
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${progress.completedCount}/${progress.totalCount} done",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                )
                if (progress.totalCount > 0) {
                    LinearProgressIndicator(
                        progress = { progress.completedCount.toFloat() / progress.totalCount },
                        modifier = Modifier.weight(1f).height(4.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                if (progress.totalTimeMinutes > 0) {
                    Text(
                        formatMinutes(progress.totalTimeMinutes),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                if (canCloseWeek) {
                    IconButton(onClick = onCloseWeek, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = "Close week",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
            // The bar you set, and whether the week you've planned actually fits. Both lines earn
            // their place or don't appear: no commitments marked, no bar line; nothing honest to say
            // about capacity, no capacity line.
            CommitmentLine(progress)
            capacity?.headline?.let { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (capacity.isWarning) MaterialTheme.colorScheme.tertiary
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp)
                )
            }
        }
    }
}

/**
 * The week's bar: how much of what you called essential is done. Shown only once something is
 * marked — an empty bar is not a zero, it's a week you chose not to set one for, and drawing
 * "0/0 committed" over every unmarked week would make the marker meaningless.
 *
 * When it's cleared it says so in as many words. That sentence is the whole point of the feature:
 * a completion percentage can tell you how much of the list moved, never whether you're done.
 */
@Composable
private fun CommitmentLine(progress: WeekProgress) {
    if (progress.commitmentTotal == 0) return
    val met = progress.commitmentMet
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (met) Icons.Default.Star else Icons.Default.StarBorder,
            contentDescription = null,
            tint = if (met) MaterialTheme.colorScheme.primary
                   else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            modifier = Modifier.size(14.dp)
        )
        Text(
            if (met) "The week's bar is met — ${progress.commitmentTotal}/${progress.commitmentTotal}. Rest is earned."
            else "Bar: ${progress.commitmentCompleted}/${progress.commitmentTotal}",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (met) FontWeight.Medium else FontWeight.Normal,
            color = if (met) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
    }
}

@Composable
internal fun AspectHeader(
    name: String,
    color: String,
    isArchived: Boolean = false,
    isExpanded: Boolean = true,
    isComplete: Boolean = false,
    onToggle: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(12.dp),
            shape = MaterialTheme.shapes.extraSmall,
            color = parseColor(color).copy(alpha = if (isArchived) 0.4f else 1f)
        ) {}
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (isArchived) "$name (archived)" else name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = parseColor(color).copy(alpha = if (isArchived) 0.5f else 1f)
        )
        if (isComplete) {
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = "All tasks resolved",
                tint = parseColor(color).copy(alpha = if (isArchived) 0.5f else 1f),
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.weight(1f))
        Icon(
            if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = if (isExpanded) "Collapse" else "Expand",
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
    }
}

@Composable
internal fun CategoryHeader(name: String, priorityTint: Color = Color.Transparent) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 16.dp, top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (priorityTint != Color.Transparent) {
            Surface(
                modifier = Modifier.size(6.dp),
                shape = MaterialTheme.shapes.extraSmall,
                color = priorityTint
            ) {}
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = name,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
}
