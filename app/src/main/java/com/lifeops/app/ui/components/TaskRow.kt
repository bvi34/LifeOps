package com.lifeops.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
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

private val SWIPE_THRESHOLD_DP = 80.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TaskRow(
    task: Task,
    aspectColor: String,
    basketColor: Color = Color.Transparent,
    notes: List<TaskNote>,
    totalTimeMinutes: Int,
    isTimerActive: Boolean,
    // Deferred read so a per-second timer tick only recomposes the active row's clock text,
    // not the whole list. Only invoked inside the `isTimerActive` branches below.
    timerElapsedSeconds: () -> Int,
    isPlanningMode: Boolean = false,
    projectName: String? = null,
    onComplete: () -> Unit,
    onUnComplete: () -> Unit = {},
    onUnSkip: () -> Unit = {},
    onSkip: () -> Unit,
    onCarryForward: () -> Unit,
    onEdit: () -> Unit,
    onStartTimer: () -> Unit,
    onStopTimer: () -> Unit,
    onOpenDetail: () -> Unit,
    onMoveUp: () -> Unit = {},
    onMoveDown: () -> Unit = {},
    onQuickLogTime: ((Int) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    val swipeThresholdPx = with(androidx.compose.ui.platform.LocalDensity.current) { SWIPE_THRESHOLD_DP.toPx() }
    val maxSwipePx = swipeThresholdPx * 1.5f
    val haptic = LocalHapticFeedback.current

    val isPending = task.status == TaskStatus.PENDING
    val isCompleted = task.status == TaskStatus.COMPLETED
    val isSkipped = task.status == TaskStatus.SKIPPED
    val isExpired = task.status == TaskStatus.EXPIRED
    val isCarriedForward = task.status == TaskStatus.CARRIED_FORWARD
    val isUnsuccessful = task.status == TaskStatus.UNSUCCESSFUL
    val hasExpandContent = notes.isNotEmpty() || totalTimeMinutes > 0 || isTimerActive || task.estimatedMinutes != null

    var expanded by remember { mutableStateOf(false) }

    // Swipe action colors: right = complete (green), left = timer (tertiary)
    val swipeBgColor = when {
        offset.value > 0 -> CompletedGreen.copy(alpha = (offset.value / maxSwipePx).coerceIn(0f, 0.45f))
        offset.value < 0 -> MaterialTheme.colorScheme.tertiary.copy(alpha = (-offset.value / maxSwipePx).coerceIn(0f, 0.45f))
        else -> Color.Transparent
    }

    LaunchedEffect(task.status) {
        if (offset.value != 0f) offset.animateTo(0f, spring())
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        // Swipe action background
        if (swipeBgColor != Color.Transparent) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(swipeBgColor),
                contentAlignment = if (offset.value > 0) Alignment.CenterStart else Alignment.CenterEnd
            ) {
                val icon = if (offset.value > 0) Icons.Default.CheckCircle else Icons.Default.AccessTime
                val tint = if (offset.value > 0) CompletedGreen else MaterialTheme.colorScheme.tertiary
                Icon(
                    icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.padding(horizontal = 16.dp).size(28.dp)
                )
            }
        }

        // Task card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .pointerInput(task.id, hasExpandContent, isPending, isCompleted, isTimerActive) {
                    coroutineScope {
                        launch {
                            detectHorizontalDragGestures(
                                onDragEnd = {
                                    scope.launch {
                                        when {
                                            // Swipe right past threshold → complete
                                            offset.value > swipeThresholdPx && isPending -> {
                                                offset.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                onComplete()
                                            }
                                            // Swipe left past threshold → toggle timer
                                            offset.value < -swipeThresholdPx && !isCarriedForward -> {
                                                offset.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                if (isTimerActive) onStopTimer() else onStartTimer()
                                            }
                                            else -> offset.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                                        }
                                    }
                                },
                                onHorizontalDrag = { change, delta ->
                                    change.consume()
                                    scope.launch {
                                        offset.snapTo((offset.value + delta).coerceIn(-maxSwipePx, maxSwipePx))
                                    }
                                }
                            )
                        }
                        launch {
                            detectTapGestures(
                                onTap = {
                                    if (hasExpandContent) expanded = !expanded
                                },
                                onLongPress = { onOpenDetail() }
                            )
                        }
                    }
                },
            colors = CardDefaults.cardColors(
                containerColor = when {
                    isExpired -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                    basketColor != Color.Transparent -> basketColor.copy(alpha = 0.07f)
                    else -> MaterialTheme.colorScheme.surface
                }
            )
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Priority color bar
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .height(56.dp)
                        .background(priorityColor(task.priority.label))
                )

                // Status icon (checkbox-like)
                TaskStatusIcon(
                    status = task.status,
                    aspectColor = aspectColor,
                    onComplete = onComplete,
                    onUnComplete = onUnComplete,
                    onUnSkip = onUnSkip,
                    modifier = Modifier.size(48.dp)
                )

                // Task content
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 4.dp, top = 6.dp, bottom = 6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = task.title,
                            style = MaterialTheme.typography.bodyMedium,
                            textDecoration = if (isCompleted || isSkipped || isCarriedForward || isUnsuccessful) TextDecoration.LineThrough else null,
                            color = if (isCompleted || isSkipped || isCarriedForward || isUnsuccessful)
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
                        if (task.isRecurring) {
                            Icon(
                                Icons.Default.Redo,
                                contentDescription = "Recurring",
                                tint = MaterialTheme.colorScheme.secondary.copy(alpha = 0.6f),
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
                    if (!expanded) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(top = 2.dp)
                        ) {
                            if (task.carriedCount > 0) {
                                Text(
                                    "↩${task.carriedCount}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.8f)
                                )
                            }
                            projectName?.let { name ->
                                Text(
                                    "▸ $name",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 100.dp)
                                )
                            }
                            if (isTimerActive) {
                                Text(
                                    "● ${formatElapsed(timerElapsedSeconds())}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.tertiary
                                )
                            } else {
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
                            task.estimatedMinutes?.let { est ->
                                Text(
                                    "est ${formatMinutes(est)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                )
                            }
                        }
                    }

                    AnimatedVisibility(visible = expanded && hasExpandContent) {
                        Column(modifier = Modifier.padding(top = 2.dp, bottom = 4.dp)) {
                            if (task.carriedCount > 0) {
                                Text(
                                    "↩ Carried forward ${task.carriedCount} time${if (task.carriedCount > 1) "s" else ""}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.8f),
                                    modifier = Modifier.padding(bottom = 2.dp)
                                )
                            }
                            projectName?.let { name ->
                                Text(
                                    "Project: $name",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                                    modifier = Modifier.padding(bottom = 2.dp)
                                )
                            }
                            if (task.estimatedMinutes != null) {
                                Text(
                                    "Est: ${formatMinutes(task.estimatedMinutes)} / Logged: ${formatMinutes(totalTimeMinutes)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                    modifier = Modifier.padding(bottom = 2.dp)
                                )
                            }
                            if (isTimerActive) {
                                Text(
                                    "● Running: ${formatElapsed(timerElapsedSeconds())}",
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

                // Right-side action buttons (or planning mode arrows)
                if (isPlanningMode) {
                    Column {
                        IconButton(onClick = onMoveUp, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.KeyboardArrowUp, "Move up", modifier = Modifier.size(18.dp))
                        }
                        IconButton(onClick = onMoveDown, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.KeyboardArrowDown, "Move down", modifier = Modifier.size(18.dp))
                        }
                    }
                } else {
                    Row {
                        // Skip / Cancel button (X)
                        if (isPending) {
                            IconButton(
                                onClick = onSkip,
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Cancel task",
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        } else {
                            Spacer(Modifier.size(40.dp))
                        }
                        // Timer button with long-press quick log
                        if (!isCarriedForward) {
                            var showTimerMenu by remember { mutableStateOf(false) }
                            Box {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .combinedClickable(
                                            onClick = { if (isTimerActive) onStopTimer() else onStartTimer() },
                                            onLongClick = {
                                                if (!isTimerActive && onQuickLogTime != null) showTimerMenu = true
                                            }
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        if (isTimerActive) Icons.Default.Stop else Icons.Default.AccessTime,
                                        contentDescription = if (isTimerActive) "Stop timer" else "Start timer",
                                        tint = if (isTimerActive) MaterialTheme.colorScheme.tertiary
                                               else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                if (showTimerMenu && onQuickLogTime != null) {
                                    DropdownMenu(
                                        expanded = showTimerMenu,
                                        onDismissRequest = { showTimerMenu = false }
                                    ) {
                                        listOf(15, 30, 45, 60).forEach { mins ->
                                            DropdownMenuItem(
                                                text = { Text("Log ${mins}m") },
                                                onClick = { onQuickLogTime(mins); showTimerMenu = false }
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            Spacer(Modifier.size(40.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskStatusIcon(
    status: TaskStatus,
    aspectColor: String,
    onComplete: () -> Unit,
    onUnComplete: () -> Unit,
    onUnSkip: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val clickAction: (() -> Unit)? = when (status) {
        TaskStatus.PENDING -> ({ haptic.performHapticFeedback(HapticFeedbackType.LongPress); onComplete() })
        TaskStatus.COMPLETED -> ({ haptic.performHapticFeedback(HapticFeedbackType.LongPress); onUnComplete() })
        TaskStatus.SKIPPED -> ({ haptic.performHapticFeedback(HapticFeedbackType.LongPress); onUnSkip() })
        TaskStatus.UNSUCCESSFUL -> ({ haptic.performHapticFeedback(HapticFeedbackType.LongPress); onUnSkip() })
        TaskStatus.CARRIED_FORWARD -> null
        else -> null
    }
    Box(
        modifier = modifier
            .then(if (clickAction != null) Modifier.clickable(onClick = clickAction) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        when (status) {
            TaskStatus.PENDING -> Icon(
                Icons.Default.CheckBoxOutlineBlank,
                contentDescription = "Mark complete",
                tint = parseColor(aspectColor),
                modifier = Modifier.size(24.dp)
            )
            TaskStatus.COMPLETED -> Icon(
                Icons.Default.CheckBox,
                contentDescription = "Mark incomplete",
                tint = CompletedGreen,
                modifier = Modifier.size(24.dp)
            )
            TaskStatus.SKIPPED -> Box(
                modifier = Modifier
                    .size(22.dp)
                    .background(ExpiredRed.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                    .border(1.5.dp, ExpiredRed.copy(alpha = 0.6f), RoundedCornerShape(4.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Tap to restore",
                    tint = ExpiredRed,
                    modifier = Modifier.size(14.dp)
                )
            }
            TaskStatus.CARRIED_FORWARD -> Box(
                modifier = Modifier
                    .size(22.dp)
                    .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                    .border(1.5.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.6f), RoundedCornerShape(4.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "↩",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
            TaskStatus.UNSUCCESSFUL -> Box(
                modifier = Modifier
                    .size(22.dp)
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                    .border(1.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f), RoundedCornerShape(4.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "½",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            else -> Icon(
                Icons.Default.CheckBoxOutlineBlank,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
                modifier = Modifier.size(24.dp)
            )
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
