@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.lifeops.app.ui.screens.thisweek

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lifeops.app.data.model.BusyBlock
import com.lifeops.app.data.model.Task
import com.lifeops.app.data.model.WeatherAlert
import com.lifeops.app.util.TodayEvents
import com.lifeops.app.util.WeatherAdvisory
import java.time.LocalDate
import kotlinx.coroutines.delay

/**
 * The banners that interrupt the list: weather the week's work depends on, and an event
 * about to happen.
 */

/**
 * Weather alerts banner: the highest-severity active alert/advisory for the tracked location,
 * shown as a persistent error-coloured strip so a storm or warning is visible above the task list.
 * Prefers an actionable advisory (with its delay hint) over the raw alert headline.
 */
@Composable
internal fun WeatherAlertsBanner(
    advisories: List<WeatherAdvisory>,
    alerts: List<WeatherAlert>
) {
    val advisory = advisories.maxByOrNull { it.severityRank }
    val topAlert = alerts.maxByOrNull { it.severity.rank }
    val bannerText = advisory?.let { adv ->
        adv.delayHint?.let { "${adv.headline} · $it" } ?: adv.headline
    } ?: topAlert?.event ?: return

    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(18.dp)
            )
            Text(
                bannerText,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * "Happening today" banner: a single always-visible line that rotates through today's calendar
 * events (busy blocks) and the tasks due today, so whatever needs attention today is glanceable
 * without scrolling or opening anything. Hidden entirely when nothing is on for today. Tapping a
 * task item opens it; a small dot pager hints at how many items are in the rotation.
 */
@Composable
internal fun EventBanner(
    tasks: List<Task>,
    busyBlocks: List<BusyBlock>,
    onOpenTask: (String) -> Unit
) {
    val items = remember(tasks, busyBlocks) {
        TodayEvents.forDate(tasks, busyBlocks, LocalDate.now())
    }
    if (items.isEmpty()) return

    var index by remember(items.size) { mutableStateOf(0) }
    // Auto-advance the rotation once there's more than one thing on today.
    LaunchedEffect(items.size) {
        if (items.size > 1) {
            while (true) {
                delay(4000)
                index = (index + 1) % items.size
            }
        }
    }
    val current = items[index.coerceIn(0, items.lastIndex)]
    val isTask = current.kind == TodayEvents.Kind.TASK_DUE

    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = current.taskId != null) {
                current.taskId?.let(onOpenTask)
            }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                if (isTask) Icons.Default.Assignment else Icons.Default.Event,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(18.dp)
            )
            AnimatedContent(
                targetState = current,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "today-event",
                modifier = Modifier.weight(1f)
            ) { item ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        item.label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    item.detail?.let {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            it,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                            maxLines = 1
                        )
                    }
                }
            }
            if (items.size > 1) {
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    items.forEachIndexed { i, _ ->
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .background(
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                        .copy(alpha = if (i == index) 0.9f else 0.3f),
                                    RoundedCornerShape(50)
                                )
                        )
                    }
                }
            }
        }
    }
}
