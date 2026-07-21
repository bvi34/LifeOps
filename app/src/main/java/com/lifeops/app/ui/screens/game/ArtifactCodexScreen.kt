@file:OptIn(ExperimentalMaterial3Api::class)

package com.lifeops.app.ui.screens.game

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lifeops.app.game.content.Artifacts
import com.lifeops.app.game.content.StartingWeapon
import com.lifeops.app.game.core.Modifier as GameModifier
import com.lifeops.app.ui.components.AppHeader
import com.lifeops.app.ui.components.BackNavIcon

/**
 * A static codex of the baseline content — the two starting weapons and the four-artifact draft
 * pool with per-rank stacking (DESIGN.md §4/§5). Everything here reads from the same data tables
 * the engine consumes, so it can never drift from what a run actually offers.
 */
@Composable
fun ArtifactCodexScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = { AppHeader(navigationIcon = { BackNavIcon(onBack) }) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text("Starting Weapons", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            items(StartingWeapon.values().toList()) { weapon ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(weapon.displayName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            weapon.blurb,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                        Spacer(Modifier.height(4.dp))
                        val rangeText = if (weapon.unlimitedRange) "unlimited range"
                        else "range ${weapon.baseStats[com.lifeops.app.game.core.Stat.RANGE]?.toInt() ?: 0}"
                        Text(
                            "$rangeText · ${weapon.magazineSize}-round mag · ${weapon.reloadSeconds}s reload",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            item {
                Spacer(Modifier.height(4.dp))
                Text("Combat Equipment", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "Automatic weapons you deploy — picked as a level-up.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            items(Artifacts.COMBAT_EQUIPMENT) { artifact -> ArtifactCard(artifact) }

            item {
                Spacer(Modifier.height(4.dp))
                Text("Stat Support", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "Pure stat boosts to the weapon you already carry.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            items(Artifacts.STAT_SUPPORT) { artifact -> ArtifactCard(artifact) }
        }
    }
}

@Composable
private fun ArtifactCard(artifact: GameModifier) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text(
                    artifact.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "${artifact.maxRank} ranks",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                artifact.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }
    }
}
