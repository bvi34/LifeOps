package com.people.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.people.app.data.model.Person
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dayTimeFormat: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.getDefault())
private val dayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())

fun formatDayTime(millis: Long): String =
    dayTimeFormat.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

fun formatDay(millis: Long): String =
    dayFormat.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

/** A person's colour dot with their initial — the same identity mark everywhere in the suite. */
@Composable
fun PersonDot(person: Person, size: Int = 40, outlined: Boolean = false) {
    val color = Color(person.colorArgb)
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(if (person.archived) color.copy(alpha = 0.3f) else color)
            .then(
                if (outlined) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            person.initial,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            fontWeight = FontWeight.Bold
        )
    }
}

/** A titled block of content — the layout unit the screens are built from. */
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

/** A labelled line of detail; renders nothing at all when there's nothing to say. */
@Composable
fun DetailRow(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp)
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
