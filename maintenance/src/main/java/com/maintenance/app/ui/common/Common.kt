package com.maintenance.app.ui.common

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Kitchen
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.maintenance.app.logic.AssetKind
import com.maintenance.app.logic.DueStatus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import com.operations.suite.ui.fields.suiteMoney

private val dayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())
private val shortDayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
private val monthFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM yyyy", Locale.getDefault())

fun formatDay(millis: Long): String =
    dayFormat.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

fun formatShortDay(millis: Long): String =
    shortDayFormat.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

fun formatMonth(date: LocalDate): String = monthFormat.format(date)

fun formatDate(date: LocalDate): String = dayFormat.format(date)

fun toEpochMillis(date: LocalDate): Long =
    date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

fun toLocalDate(millis: Long): LocalDate =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()

/**
 * Money for a screen: cents in, the phone's own symbol on the front.
 *
 * Resolving the symbol is the suite's job now — the money *field* had to agree with the money
 * *label*, and two locale lookups would eventually disagree. Maintenance keeps the short name
 * because twenty read-only rows say `money(...)` and that reads better in a table than the
 * qualified one.
 */
@Composable
fun money(cents: Long, withCents: Boolean = true): String = suiteMoney(cents, withCents)

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

/** What a screen says when it has nothing to show — never a blank rectangle. */
@Composable
fun EmptyState(headline: String, detail: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(headline, style = MaterialTheme.typography.titleMedium)
        Text(
            detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** A label above the value it names — the shape every fact on the detail screen is drawn in. */
@Composable
fun LabeledValue(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

/** The glyph a kind is drawn with. Five kinds, five silhouettes, no two alike at list size. */
fun iconFor(kind: AssetKind): ImageVector = when (kind) {
    AssetKind.HOME -> Icons.Filled.Home
    AssetKind.VEHICLE -> Icons.Filled.DirectionsCar
    AssetKind.APPLIANCE -> Icons.Filled.Kitchen
    AssetKind.EQUIPMENT -> Icons.Filled.Build
    AssetKind.OTHER -> Icons.Filled.Inventory2
}

/** An asset's identity mark: its colour, its kind's glyph — the same mark in the list and header. */
@Composable
fun AssetMark(kind: AssetKind, colorArgb: Long, archived: Boolean = false, size: Int = 44) {
    val color = Color(colorArgb).let { if (archived) it.copy(alpha = 0.35f) else it }
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(RoundedCornerShape((size / 3.2f).dp))
            .background(color),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = iconFor(kind),
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size((size / 2.1f).dp)
        )
    }
}

/**
 * The colour a due status is drawn in.
 *
 * Only two of the five get a colour of their own. Overdue is the error colour because it is the one
 * state that costs money; due-soon is the tertiary. Everything else takes the ordinary surface
 * colours, so a screen full of things in hand looks calm rather than looking like a dashboard.
 */
@Composable
fun statusColor(status: DueStatus): Color = when (status) {
    DueStatus.OVERDUE -> MaterialTheme.colorScheme.error
    DueStatus.DUE_SOON -> MaterialTheme.colorScheme.tertiary
    DueStatus.NEEDS_BASELINE -> MaterialTheme.colorScheme.secondary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

fun statusLabel(status: DueStatus): String = when (status) {
    DueStatus.OVERDUE -> "Overdue"
    DueStatus.DUE_SOON -> "Soon"
    DueStatus.SCHEDULED -> "Scheduled"
    DueStatus.NEEDS_BASELINE -> "Needs a reading"
    DueStatus.DORMANT -> "No schedule"
}

/** The small tinted badge a row wears to say where it stands. */
@Composable
fun StatusPill(status: DueStatus, modifier: Modifier = Modifier) {
    val color = statusColor(status)
    Surface(
        color = color.copy(alpha = 0.14f),
        shape = RoundedCornerShape(8.dp),
        modifier = modifier
    ) {
        Text(
            statusLabel(status),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}
