@file:OptIn(ExperimentalMaterial3Api::class)

package com.lifeops.app.ui.screens.game

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeops.app.game.content.ChallengeMode
import com.lifeops.app.game.content.StoreCatalog
import com.lifeops.app.game.run.Loadout
import com.lifeops.app.ui.components.AppHeader
import com.lifeops.app.ui.components.BackNavIcon

/**
 * Choosing what to take into a run, and committing the resources it costs.
 */

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LoadoutView(viewModel: RunViewModel, onBack: () -> Unit) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val energy = Loadout.energyResource(ui.resources)
    val energyBalance = energy?.currentValue ?: 0
    val affordable = energyBalance >= Loadout.ENERGY_COST

    Scaffold(topBar = { AppHeader(navigationIcon = { BackNavIcon(onBack) }) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text("Loadout", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "Complete real tasks to earn resources; every run spends them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Weapon", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ui.availableWeapons.forEach { w ->
                                val selected = ui.weapon == w
                                if (selected) {
                                    Button(onClick = { viewModel.selectWeapon(w) }) { Text(w.displayName) }
                                } else {
                                    OutlinedButton(onClick = { viewModel.selectWeapon(w) }) { Text(w.displayName) }
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            ui.weapon.blurb,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Challenge", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        ChallengeMode.ALL.forEach { mode ->
                            val selected = ui.challengeMode.id == mode.id
                            if (selected) {
                                Button(onClick = { viewModel.selectChallengeMode(mode) }, modifier = Modifier.fillMaxWidth()) {
                                    Text(mode.name)
                                }
                            } else {
                                OutlinedButton(onClick = { viewModel.selectChallengeMode(mode) }, modifier = Modifier.fillMaxWidth()) {
                                    Text(mode.name)
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            ui.challengeMode.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            // Owned mutators (bought from the store) can be self-imposed for a run (DESIGN.md §9).
            val ownedMutators = StoreCatalog.unlockedMutators(ui.unlockedIds)
            if (ownedMutators.isNotEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Modifiers", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Text(
                                "Self-impose an owned mutator for this run.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                            Spacer(Modifier.height(8.dp))
                            ownedMutators.forEach { m ->
                                val on = ui.activeMutatorIds.contains(m.id)
                                if (on) {
                                    Button(onClick = { viewModel.toggleMutator(m.id) }, modifier = Modifier.fillMaxWidth()) {
                                        Text("✓ ${m.name}")
                                    }
                                } else {
                                    OutlinedButton(onClick = { viewModel.toggleMutator(m.id) }, modifier = Modifier.fillMaxWidth()) {
                                        Text(m.name)
                                    }
                                }
                                Text(
                                    m.description,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                )
                                Spacer(Modifier.height(4.dp))
                            }
                        }
                    }
                }
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Entry cost", style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Text(
                                "${Loadout.ENERGY_COST} ${energy?.name ?: "Energy"}",
                                style = MaterialTheme.typography.titleSmall,
                                color = if (affordable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                        }
                        Text(
                            "Balance: $energyBalance",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        if (!affordable) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Locked out at zero — close a week to bank more Energy.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            item {
                CommitCard(
                    title = "Level Cap",
                    subtitle = "≈ ${Loadout.UNITS_PER_LEVEL_CAP} banked per extra level · run cap ${Loadout.levelCapFor(ui.commitment.levelCap)}",
                    resource = Loadout.resolve(Loadout.Role.LEVEL_CAP, ui.resources),
                    committed = ui.commitment.levelCap,
                    step = Loadout.UNITS_PER_LEVEL_CAP,
                    onChange = { viewModel.setCommitment(ui.commitment.copy(levelCap = it)) }
                )
            }
            item {
                CommitCard(
                    title = "Hearts",
                    subtitle = "${Loadout.UNITS_PER_HEART} banked per extra heart · ${Loadout.heartsFor(ui.commitment.maxHealth)} hearts",
                    resource = Loadout.resolve(Loadout.Role.MAX_HEALTH, ui.resources),
                    committed = ui.commitment.maxHealth,
                    step = Loadout.UNITS_PER_HEART,
                    onChange = { viewModel.setCommitment(ui.commitment.copy(maxHealth = it)) }
                )
            }
            item {
                CommitCard(
                    title = "Starting Gold",
                    subtitle = "1 : 1 · in-run purse (spent gold dies with the run)",
                    resource = Loadout.resolve(Loadout.Role.STARTING_GOLD, ui.resources),
                    committed = ui.commitment.gold,
                    step = 5,
                    onChange = { viewModel.setCommitment(ui.commitment.copy(gold = it)) }
                )
            }

            ui.message?.let { msg ->
                item {
                    Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }

            item {
                Button(
                    onClick = { viewModel.startRun() },
                    enabled = affordable,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Start Run") }
            }

            // Weekly dev run: an unlimited-resources sandbox, once a week, that ends after a set 4
            // and leaves nothing permanent. Uses whatever weapon/challenge/mutators are selected above.
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Dev Run", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text(
                            "Once a week: unlimited resources, ends after set ${com.lifeops.app.game.run.RunConfig.DEV_RUN_SETS}. " +
                                "Free store, nothing you earn is permanent — a sandbox to try builds.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { viewModel.startDevRun() },
                            enabled = ui.devRunAvailable,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (ui.devRunAvailable) "Start Dev Run" else "Dev Run — used this week") }
                    }
                }
            }
        }
    }
}

@Composable
private fun CommitCard(
    title: String,
    subtitle: String,
    resource: com.lifeops.app.data.model.GameResource?,
    committed: Int,
    step: Int,
    onChange: (Int) -> Unit,
) {
    val balance = resource?.currentValue ?: 0
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "${resource?.name ?: "—"} · balance $balance",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                IconButton(
                    onClick = { onChange((committed - step).coerceAtLeast(0)) },
                    enabled = committed > 0
                ) { Icon(Icons.Default.Remove, contentDescription = "Less") }
                Text(committed.toString(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                IconButton(
                    onClick = { onChange((committed + step).coerceAtMost(balance)) },
                    enabled = committed + step <= balance || committed < balance
                ) { Icon(Icons.Default.Add, contentDescription = "More") }
            }
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }
    }
}
