package com.health.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.health.app.data.model.Profile
import com.health.app.logic.Allergies
import com.health.app.logic.AllergyWarning
import com.health.app.logic.CareLevel
import com.health.app.logic.Fever
import java.time.LocalDate
import com.operations.suite.ui.pickers.SuiteDates

/**
 * The pieces every Health screen shares: who's on screen, how a care level looks, and how instants
 * are written. Kept in one place so five screens can't drift into five vocabularies for the same
 * facts — which, in an app where the difference between "watch it" and "call someone" is the whole
 * product, would be worse than duplicated code.
 */

// How an instant is written is the suite's decision, not Health's — the when-picker that recorded
// this temperature spells the same instant back in [SuiteDates]' words, and two vocabularies for the
// same moment on one screen is how "14:20" and "2:20 PM" end up in the same list.
fun formatTime(millis: Long): String = SuiteDates.formatTime(millis)

fun formatDay(millis: Long): String = SuiteDates.formatShortDay(SuiteDates.toLocalDate(millis))

/**
 * The same, for a date that is already a date.
 *
 * A day heading takes this rather than the instant of one of its entries: a group knows its own
 * date, and deriving it from whichever row happens to be first breaks the moment the list is read
 * the other way round.
 */
fun formatDay(date: LocalDate): String = SuiteDates.formatShortDay(date)

fun formatDayTime(millis: Long): String = SuiteDates.formatDayTime(millis)

/** Today's entries read as a time; older ones need the date, because "14:20" alone lies about age. */
fun formatStamp(millis: Long, nowMillis: Long = System.currentTimeMillis()): String =
    SuiteDates.formatStamp(millis, nowMillis)

/** The colour a care level is allowed to be. Red is reserved; nothing else in Health uses it. */
@Composable
fun careColor(level: CareLevel): Color = when (level) {
    CareLevel.ROUTINE -> MaterialTheme.colorScheme.onSurfaceVariant
    CareLevel.MONITOR -> MaterialTheme.colorScheme.tertiary
    CareLevel.CALL_DOCTOR -> MaterialTheme.colorScheme.tertiary
    CareLevel.SEEK_CARE_NOW -> MaterialTheme.colorScheme.error
}

@Composable
fun CareBadge(level: CareLevel, modifier: Modifier = Modifier) {
    if (level == CareLevel.ROUTINE) return
    val color = careColor(level)
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = color.copy(alpha = 0.12f)
    ) {
        Text(
            level.label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

/** A person's colour dot with their initial — the same identity mark on every screen. */
@Composable
fun ProfileDot(profile: Profile, size: Int = 36, selected: Boolean = false) {
    val color = Color(profile.colorArgb)
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = if (selected) 1f else 0.35f))
            .then(
                if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            profile.initial,
            style = MaterialTheme.typography.titleSmall,
            color = Color.White,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * The who-am-I-looking-at bar that sits above every screen. In a household app this is not chrome:
 * a temperature filed against the wrong child is worse than one not recorded at all, so the current
 * person is on screen at all times, not buried in a settings menu.
 */
@Composable
fun ProfileBar(
    profiles: List<Profile>,
    selectedId: String?,
    onSelect: (Profile) -> Unit,
    onOpenPeople: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        profiles.forEach { profile ->
            val isSelected = profile.id == selectedId
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(profile) },
                label = { Text(profile.name) },
                leadingIcon = { ProfileDot(profile, size = 24, selected = isSelected) }
            )
        }
        // "People", not "Add person": Health does not own who these people are and has no screen for
        // adding one. The chip opens the household directory, which is the app that does.
        onOpenPeople?.let {
            AssistChip(
                onClick = it,
                label = { Text("People") },
                leadingIcon = { Icon(Icons.Default.Groups, contentDescription = null) }
            )
        }
    }
}

/**
 * The empty state shown before anyone has been added — every screen bottoms out here.
 *
 * It points at **People** rather than offering a form. Health takes the people the household
 * directory has ticked as members and grows a profile for each of them; a second place to add one
 * would be a second answer to "who lives here", and the sync seam exists precisely so there is only
 * one. See `HealthSyncService`.
 */
@Composable
fun NoProfiles(onOpenPeople: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Nobody here yet", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Health keeps a separate record for everyone in the household — but it takes the " +
                "household from People. Tick somebody as a household member there and they turn up " +
                "here, birth date and all, ready to record against.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onOpenPeople) { Text("Open People") }
    }
}

/** The standing "this isn't medical advice" line, rendered the same way wherever it appears. */
@Composable
fun DisclaimerText(modifier: Modifier = Modifier) {
    Text(
        Fever.DISCLAIMER,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )
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

/** A single-choice row of chips — used for sites, units, severities and kinds. */
@Composable
fun <T> ChoiceRow(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(label(option)) }
            )
        }
    }
}

/** A row that reads as a list item and can be tapped or long-pressed away. */
@Composable
fun RecordRow(
    headline: String,
    support: String?,
    trailing: String? = null,
    trailingColor: Color? = null,
    onClick: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null
) {
    ListItem(
        headlineContent = { Text(headline) },
        supportingContent = support?.let { { Text(it, style = MaterialTheme.typography.bodySmall) } },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                trailing?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelLarge,
                        color = trailingColor ?: MaterialTheme.colorScheme.onSurface
                    )
                }
                onDelete?.let {
                    TextButton(onClick = it) { Text("Delete") }
                }
            }
        },
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    )
}

/**
 * What the household has written down that matches the medicine in front of you.
 *
 * Rendered **only when something matched**. An empty check draws nothing at all — no green tick, no
 * "no allergies found", no reassuring absence of a badge. That is the one rule this component has,
 * and it is the reason it exists as a component rather than as a line in one dialog: "nothing
 * recorded matched" and "she isn't allergic to this" are different sentences, and an app that shows
 * the first as though it were the second is making a medical claim on no evidence. See
 * `logic/Allergies`.
 *
 * Red, and one of only two places in Health that spends it — the other being a care level of
 * SEEK_CARE_NOW. This is the moment it is for.
 */
@Composable
fun AllergyWarningBanner(warnings: List<AllergyWarning>, personName: String?) {
    if (warnings.isEmpty()) return
    val color = MaterialTheme.colorScheme.error

    Surface(
        shape = MaterialTheme.shapes.small,
        color = color.copy(alpha = 0.10f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                if (personName != null) "$personName has a recorded allergy to this"
                else "There is a recorded allergy to this",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = color
            )
            warnings.forEach { warning ->
                Column {
                    Text(
                        warning.headline,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    // The evidence, quoted rather than paraphrased. A warning nobody can audit is a
                    // warning people learn to tap straight past.
                    Text(
                        warning.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                Allergies.DISCLAIMER,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
