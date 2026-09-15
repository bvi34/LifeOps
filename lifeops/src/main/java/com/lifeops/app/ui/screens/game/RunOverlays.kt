@file:OptIn(ExperimentalMaterial3Api::class)

package com.lifeops.app.ui.screens.game

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lifeops.app.game.run.Loadout
import com.lifeops.app.game.run.RunSnapshot
import com.lifeops.app.game.run.RunStatus
import com.lifeops.app.game.run.revive

/**
 * The overlays that pause a run to ask something: a level-up pick, a set bonus, the
 * between-set store, an overflow spend, and the summary once it is over.
 */

@Composable
internal fun LevelUpOverlay(snapshot: RunSnapshot, onChoose: (com.lifeops.app.game.run.LevelUpOption) -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center
    ) {
        Card(Modifier.fillMaxWidth().padding(24.dp)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Level ${snapshot.level}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Choose an upgrade", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                snapshot.levelUpOptions.forEach { opt ->
                    val isEquipment = opt.modifier.category == com.lifeops.app.game.core.ArtifactCategory.COMBAT_EQUIPMENT
                    FilledTonalButton(onClick = { onChoose(opt) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(opt.label, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                Text(
                                    if (isEquipment) "Equipment" else "Support",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isEquipment) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                )
                            }
                            Text(
                                opt.modifier.description,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SetBonusOverlay(
    snapshot: RunSnapshot,
    onChoose: (com.lifeops.app.game.run.SetBonusOption) -> Unit,
) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center
    ) {
        Card(Modifier.fillMaxWidth().padding(24.dp)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Set ${snapshot.tier + 1}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "Draft a boon — but the enemies take the bane with it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                snapshot.setBonusOptions.forEach { opt ->
                    FilledTonalButton(onClick = { onChoose(opt) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text("↑ ${opt.boon.name}", fontWeight = FontWeight.Bold)
                            Text(
                                opt.boon.description,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                            )
                            Text(
                                "↓ Enemies: ${opt.bane.name} — ${opt.bane.description}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error.copy(alpha = 0.9f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun StoreOverlay(
    snapshot: RunSnapshot,
    balance: Int,
    currencyName: String,
    onBuy: (com.lifeops.app.game.run.StoreOffer) -> Unit,
    onSkip: () -> Unit,
) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center
    ) {
        Card(Modifier.fillMaxWidth().padding(24.dp)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Store", style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text(if (snapshot.devRun) "Free" else "$balance $currencyName",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary)
                }
                Text(
                    if (snapshot.devRun) "Dev run — take anything to try it. Nothing here is kept."
                    else "Spend banked $currencyName to unlock something real — kept for future runs.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                snapshot.storeOptions.forEach { offer ->
                    val affordable = balance >= offer.cost
                    FilledTonalButton(
                        onClick = { onBuy(offer) },
                        enabled = affordable,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(offer.name, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                Text(
                                    "${offer.cost} · ${offer.categoryLabel}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (affordable) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.error
                                )
                            }
                            Text(
                                offer.description,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
                OutlinedButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) { Text("Skip") }
            }
        }
    }
}

@Composable
internal fun OverflowOverlay(
    snapshot: RunSnapshot,
    onChoose: (com.lifeops.app.game.run.OverflowOption) -> Unit,
) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center
    ) {
        Card(Modifier.fillMaxWidth().padding(24.dp)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Overflow", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "Past the level cap — take an instant boost.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                snapshot.overflowOptions.forEach { opt ->
                    FilledTonalButton(onClick = { onChoose(opt) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text(opt.label, fontWeight = FontWeight.Bold)
                            Text(
                                opt.description,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SummaryOverlay(
    snapshot: RunSnapshot,
    /** Non-null only on a defeat the player can still afford to revive from; the button's price. */
    reviveCost: Int?,
    onRevive: () -> Unit,
    onPlayAgain: () -> Unit,
    onLeave: () -> Unit,
) {
    // A standard run ends only in defeat; a bounded dev run can end in victory once it clears its sets.
    val won = snapshot.status == RunStatus.VICTORY
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.72f)),
        contentAlignment = Alignment.Center
    ) {
        Card(Modifier.fillMaxWidth().padding(24.dp)) {
            Column(
                Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    if (won) "Dev Run Complete" else "Overrun",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (won) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
                Text("Score ${snapshot.score}", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (won) "Cleared ${snapshot.maxSets ?: snapshot.tier} sets · wave ${snapshot.wave}/${snapshot.totalWaves} · level ${snapshot.level}"
                    else "Reached set ${snapshot.tier + 1} · wave ${snapshot.wave}/${snapshot.totalWaves} · level ${snapshot.level}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                if (snapshot.revives > 0) {
                    Text(
                        "Revived ${snapshot.revives}×",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                Spacer(Modifier.height(6.dp))
                if (reviveCost != null) {
                    Button(onClick = onRevive, modifier = Modifier.fillMaxWidth()) {
                        Text("Revive — $reviveCost⚡")
                    }
                }
                Button(onClick = onPlayAgain, modifier = Modifier.fillMaxWidth()) { Text("Back to Loadout") }
                OutlinedButton(onClick = onLeave, modifier = Modifier.fillMaxWidth()) { Text("Leave") }
            }
        }
    }
}
