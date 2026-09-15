@file:OptIn(ExperimentalMaterial3Api::class)

package com.lifeops.app.ui.screens.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lifeops.app.data.model.Aspect

/**
 * What completed work is worth: the reading reward rate, and the aspect a task published from
 * another app is filed under when it does not say.
 */

/**
 * Reading rewards: choose which aspect reading time in Citation earns into (or Off), and the flat
 * points-per-hour rate. Both reading categories fold into the one aspect; the economy stays simple.
 */
@Composable
internal fun ReadingRewardsSection(
    aspects: List<Aspect>,
    selectedAspectId: String?,
    pointsPerHour: Int,
    onSelectAspect: (String?) -> Unit,
    onSetPoints: (Int) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Engaged reading time in Citation earns resources into the aspect you pick. Off by default.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            Text("Earns into", style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedAspectId == null,
                    onClick = { onSelectAspect(null) },
                    label = { Text("Off") }
                )
                aspects.filter { !it.isArchived }.forEach { aspect ->
                    FilterChip(
                        selected = selectedAspectId == aspect.id,
                        onClick = { onSelectAspect(aspect.id) },
                        label = { Text(aspect.name) }
                    )
                }
            }
            if (selectedAspectId != null) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Rate", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = { onSetPoints(pointsPerHour - 1) }, enabled = pointsPerHour > 0) {
                        Icon(Icons.Default.Remove, contentDescription = "Fewer points")
                    }
                    Text(
                        "$pointsPerHour pts/hr",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    IconButton(onClick = { onSetPoints(pointsPerHour + 1) }) {
                        Icon(Icons.Default.Add, contentDescription = "More points")
                    }
                }
            }
        }
    }
}

/**
 * Which aspect the upkeep tasks Maintenance puts on the week are filed under.
 *
 * The same shape as [ReadingRewardsSection], for the same reason: another app in the suite feeds
 * work into LifeOps, and LifeOps decides which part of your life it counts towards — not that app.
 *
 * The one difference is what "none" means. Reading rewards are *off* until an aspect is chosen;
 * published tasks arrive either way, and an unfiled one still scores. So the chip says "Unfiled"
 * rather than "Off", because nothing here is being switched off.
 *
 * There are two of these now — Maintenance's upkeep and Finance's bills — and they get separate
 * settings rather than one shared one, because changing the oil and paying the mortgage are not the
 * same part of anybody's life. The only thing that differs between the two is [blurb], so the
 * section is parameterised rather than copied.
 */
@Composable
internal fun PublishedTaskAspectSection(
    blurb: String,
    aspects: List<Aspect>,
    selectedAspectId: String?,
    onSelectAspect: (String?) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                blurb,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            Text("Filed under", style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedAspectId == null,
                    onClick = { onSelectAspect(null) },
                    label = { Text("Unfiled") }
                )
                aspects.filter { !it.isArchived }.forEach { aspect ->
                    FilterChip(
                        selected = selectedAspectId == aspect.id,
                        onClick = { onSelectAspect(aspect.id) },
                        label = { Text(aspect.name) }
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Changing this re-files the tasks published from now on. Anything already on a week " +
                    "keeps the aspect it arrived with — including one you moved by hand.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
