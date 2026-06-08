package com.lifeops.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.lifeops.app.data.model.Task
import com.lifeops.app.data.model.TaskNote
import com.lifeops.app.data.model.TaskStatus
import com.lifeops.app.ui.theme.CompletedGreen
import com.lifeops.app.ui.theme.ExpiredRed
import com.lifeops.app.ui.theme.parseColor
import com.lifeops.app.ui.theme.priorityColor
import com.lifeops.app.util.DateUtil
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val ACTION_PANEL_WIDTH = 168.dp

@Composable
fun TaskRow(
    task: Task,
    aspectColor: String,
    notes: List<TaskNote>,
    totalTimeMinutes: Int,
    isTimerActive: Boolean,
    timerElapsedSeconds: Int,
    onComplete: () -> Unit,
    onSkip: () -> Unit,
    onCarryForward: () -> Unit,
    onEdit: () -> Unit,
    onStartTimer: () -> Unit,
    onStopTimer: () -> Unit,
    onOpenDetail: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    val density = LocalDensity.current
    val maxOffset = remember(density) { with(density) { ACTION_PANEL_WIDTH.toPx() } }

    val isPending = task.status == TaskStatus.PENDING
    val isCompleted = task.status == TaskStatus.COMPLETED
    val isSkipped = task.status == TaskStatus.SKIPPED
    val isExpired = task.status == TaskStatus.EXPIRED
    val hasExpandContent = notes.isNotEmpty() || totalTimeMinutes > 0 || isTimerActive

    var expanded by remember { mutableStateOf(false) }

    fun closeSwipe() { scope.launch { offset.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy)) } }

    // Snap closed when task status changes
    LaunchedEffect(task.status) {
        if (offset.value != 0f) offset.animateTo(0f, spring())
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        // Action panel behind the card
        Row(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(ACTION_PANEL_WIDTH)
                .matchParentSize()
                .background(MaterialTheme.colorScheme.surfaceVariant),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isPending) {
                IconButton(onClick = { closeSwipe(); onComplete() }) {
                    Icon(Icons.Default.Done, "Complete", tint = CompletedGreen)
                }
                IconButton(onClick = { closeSwipe(); onSkip() }) {
                    Icon(Icons.Default.Close, "Skip", tint = ExpiredRed)
                }
            }
            IconButton(
                onClick = {
                    closeSwipe()
                    if (isTimerActive) onStopTimer() else onStartTimer()
                }
            ) {
                Icon(
                    if (isTimerActive) Icons.Default.Stop else Icons.Default.AccessTime,
                    contentDescription = if (isTimerActive) "Stop timer" else "Start timer",
                    tint = if (isTimerActive) MaterialTheme.colorScheme.tertiary
                           else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Swipeable task card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .pointerInput(task.id, hasExpandContent, isPending, isTimerActive) {
                    coroutineScope {
                        launch {
                            detectHorizontalDragGestures(
                                onDragEnd = {
                                    scope.launch {
                                        val target =
                                            if (offset.value < -maxOffset * 0.35f) -maxOffset else 0f
                                        offset.animateTo(
                                            target,
                                            spring(dampingRatio = Spring.DampingRatioMediumBouncy)
                                        )
                                    }
                                },
                                onHorizontalDrag = { change, delta ->
                                    change.consume()
                                    scope.launch {
                                        offset.snapTo(
                                            (offset.value + delta).coerceIn(-maxOffset, 0f)
                                        )
                                    }
                                }
                            )
                        }
                        launch {
                            detectTapGestures(
                                onTap = {
                                    when {
                                        offset.value < -maxOffset * 0.3f -> closeSwipe()
                                        hasExpandContent -> expanded = !expanded
                                    }
                                },
                                onLongPress = {
                                    if (offset.value > -maxOffset * 0.3f) onOpenDetail()
                                }
                            )
                        }
                    }
                },
            colors = CardDefaults.cardColors(
                containerColor = if (isExpired)
                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                else MaterialTheme.colorScheme.surface
            )
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .fillMaxHeight()
                        .background(priorityColor(task.priority.label))
                        .align(Alignment.CenterVertically)
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = isCompleted,
                            onCheckedChange = { if (isPending) onComplete() },
                            enabled = isPending,
                            colors = CheckboxDefaults.colors(checkedColor = parseColor(aspectColor))
                        )
                        Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = task.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    textDecoration = if (isCompleted || isSkipped) TextDecoration.LineThrough else null,
                                    color = if (isCompleted || isSkipped)
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                    else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                if (task.hardDeadline) {
                                    Icon(
                                        Icons.Default.AccessTime,
                                        contentDescription = "Hard deadline",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(14.dp).padding(start = 2.dp)
                                    )
                                }
                            }
                            task.dueDate?.let { date ->
                                Text(
                                    text = DateUtil.formatDate(date),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (DateUtil.isOverdue(date) && isPending)
                                        MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                            // Summary chips (collapsed view)
                            if (!expanded || (!hasExpandContent)) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.padding(top = 2.dp)
                                ) {
                                    if (isTimerActive) {
                                        Text(
                                            "● ${formatElapsed(timerElapsedSeconds)}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.tertiary
                                        )
                                    } else if (notes.isNotEmpty() || totalTimeMinutes > 0) {
                                        if (notes.isNotEmpty()) {
                                            Text(
                                                "${notes.size} note${if (notes.size > 1) "s" else ""}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                                            )
                                        }
                                        if (totalTimeMinutes > 0) {
                                            Text(
                                                formatMinutes(totalTimeMinutes),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.8f)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        PriorityBadge(task.priority.label)
                    }

                    AnimatedVisibility(visible = expanded && hasExpandContent) {
                        Column(modifier = Modifier.padding(start = 44.dp, top = 2.dp, bottom = 4.dp)) {
                            if (isTimerActive) {
                                Text(
                                    "● Running: ${formatElapsed(timerElapsedSeconds)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.padding(bottom = 2.dp)
                                )
                            }
                            if (totalTimeMinutes > 0) {
                                Text(
                                    "Total: ${formatMinutes(totalTimeMinutes)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )
                            }
                            notes.forEach { note ->
                                Text(
                                    "• ${note.content}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                                    modifier = Modifier.padding(vertical = 1.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun formatMinutes(totalMinutes: Int): String {
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

internal fun formatElapsed(totalSeconds: Int): String {
    val m = totalSeconds / 60
    val s = totalSeconds % 60
    return "%d:%02d".format(m, s)
}

@Composable
private fun PriorityBadge(priority: String) {
    val color = priorityColor(priority)
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = color.copy(alpha = 0.2f),
        modifier = Modifier.padding(start = 4.dp)
    ) {
        Text(
            text = priority.take(1).uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}
