package com.project.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.project.app.logic.ProjectPulse
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dayTimeFormat: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.getDefault())

fun formatDayTime(millis: Long): String =
    dayTimeFormat.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

/** A titled block of content — the layout unit these screens are built from. */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                trailing?.invoke()
            }
            content()
        }
    }
}

/** A project's colour mark with its initial — the same identity mark on the shelf and in its header. */
@Composable
fun ProjectMark(name: String, colorArgb: Long, size: Int = 40) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(RoundedCornerShape((size / 3.2f).dp))
            .background(Color(colorArgb)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            name.trim().firstOrNull()?.uppercase() ?: "?",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * The progress bar under a project or a chapter.
 *
 * Renders nothing when there is nothing to measure. A project with no word target and no finished
 * pieces has no progress, and an empty bar is a claim that it is behind rather than unmeasured —
 * which is precisely the discouraging lie a writing tool should not tell on day one.
 */
@Composable
fun ProgressBar(progress: Float?, modifier: Modifier = Modifier) {
    if (progress == null) return
    LinearProgressIndicator(
        progress = { progress.coerceIn(0f, 1f) },
        modifier = modifier.fillMaxWidth()
    )
}

/** The line under a project's name: where it has got to, in its own vocabulary. */
@Composable
fun PulseLine(pulse: ProjectPulse) {
    Text(
        pulse.headline,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** What a section says when there is nothing in it yet — an invitation, not an error. */
@Composable
fun EmptyState(title: String, detail: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
