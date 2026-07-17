@file:OptIn(ExperimentalMaterial3Api::class)

package com.lifeops.app.ui.screens.game

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeops.app.game.content.ChallengeMode
import com.lifeops.app.game.content.EnemyType
import com.lifeops.app.game.content.StartingWeapon
import com.lifeops.app.game.core.PickupKind
import com.lifeops.app.game.core.Vec2
import com.lifeops.app.game.run.Loadout
import com.lifeops.app.game.run.RunEngine
import com.lifeops.app.game.run.RunInput
import com.lifeops.app.game.run.RunSnapshot
import com.lifeops.app.game.run.RunStatus
import com.lifeops.app.ui.components.AppHeader
import com.lifeops.app.ui.components.BackNavIcon
import kotlinx.coroutines.isActive
import kotlin.math.min

@Composable
fun RunScreen(viewModel: RunViewModel, onBack: () -> Unit) {
    val engine by viewModel.engine.collectAsStateWithLifecycle()
    val activeEngine = engine
    if (activeEngine == null) {
        LoadoutView(viewModel, onBack)
    } else {
        RunView(activeEngine, viewModel, onBack)
    }
}

// --- Loadout -------------------------------------------------------------------------------------

@Composable
private fun LoadoutView(viewModel: RunViewModel, onBack: () -> Unit) {
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
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StartingWeapon.values().forEach { w ->
                                val selected = ui.weapon == w
                                val mod = Modifier.weight(1f)
                                if (selected) {
                                    Button(onClick = { viewModel.selectWeapon(w) }, modifier = mod) { Text(w.displayName) }
                                } else {
                                    OutlinedButton(onClick = { viewModel.selectWeapon(w) }, modifier = mod) { Text(w.displayName) }
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
                    title = "Max Health",
                    subtitle = "1 : 1 · run HP ${Loadout.maxHealthFor(ui.commitment.maxHealth).toInt()}",
                    resource = Loadout.resolve(Loadout.Role.MAX_HEALTH, ui.resources),
                    committed = ui.commitment.maxHealth,
                    step = 10,
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

// --- Live run ------------------------------------------------------------------------------------

@Composable
private fun RunView(engine: RunEngine, viewModel: RunViewModel, onBack: () -> Unit) {
    var snapshot by remember(engine) { mutableStateOf(engine.snapshot()) }
    val move = remember(engine) { mutableStateOf(Vec2.ZERO) }
    var joyCenter by remember(engine) { mutableStateOf<Offset?>(null) }
    var joyKnob by remember(engine) { mutableStateOf(Offset.Zero) }

    // Frame loop — the only Android-timed part; the engine step itself is pure.
    androidx.compose.runtime.LaunchedEffect(engine) {
        var last = 0L
        while (isActive) {
            val now = withFrameNanos { it }
            val dt = if (last == 0L) 0f else (now - last) / 1_000_000_000f
            last = now
            engine.step(dt, RunInput(move.value))
            snapshot = engine.snapshot()
        }
    }

    Box(Modifier.fillMaxSize().background(ARENA_BG)) {
        val joyMaxRadius = 130f
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(engine) {
                    detectDragGestures(
                        onDragStart = { pos ->
                            joyCenter = pos
                            joyKnob = pos
                        },
                        onDrag = { change, _ ->
                            joyKnob = change.position
                            val c = joyCenter ?: change.position
                            val dx = change.position.x - c.x
                            val dy = change.position.y - c.y
                            val len = kotlin.math.hypot(dx, dy)
                            move.value = if (len < 1f) Vec2.ZERO
                            else Vec2(dx, dy) * (min(len, joyMaxRadius) / len / joyMaxRadius)
                        },
                        onDragEnd = { joyCenter = null; move.value = Vec2.ZERO },
                        onDragCancel = { joyCenter = null; move.value = Vec2.ZERO }
                    )
                }
        ) {
            val scale = min(size.width / snapshot.arena.x, size.height / snapshot.arena.y)
            val originX = (size.width - snapshot.arena.x * scale) / 2f
            val originY = (size.height - snapshot.arena.y * scale) / 2f
            fun sx(x: Float) = originX + x * scale
            fun sy(y: Float) = originY + y * scale

            // Arena floor.
            drawRect(
                color = ARENA_FLOOR,
                topLeft = Offset(sx(0f), sy(0f)),
                size = androidx.compose.ui.geometry.Size(snapshot.arena.x * scale, snapshot.arena.y * scale)
            )

            snapshot.pickups.forEach { pk ->
                drawCircle(
                    color = when (pk.kind) {
                        PickupKind.XP -> XP_COLOR
                        PickupKind.GOLD -> GOLD_COLOR
                        PickupKind.HEALTH -> HEALTH_COLOR
                    },
                    radius = 5f * scale,
                    center = Offset(sx(pk.pos.x), sy(pk.pos.y))
                )
            }

            snapshot.enemies.forEach { e ->
                val c = Offset(sx(e.pos.x), sy(e.pos.y))
                drawCircle(color = enemyColor(e.type), radius = e.radius * scale, center = c)
                // Health arc as a shrinking inner dot.
                drawCircle(
                    color = Color.Black.copy(alpha = 0.25f),
                    radius = e.radius * scale * (1f - e.healthFrac) * 0.9f,
                    center = c
                )
            }

            snapshot.projectiles.forEach { p ->
                drawCircle(color = PROJECTILE_COLOR, radius = 3.5f * scale, center = Offset(sx(p.x), sy(p.y)))
            }

            // Player.
            val pc = Offset(sx(snapshot.playerPos.x), sy(snapshot.playerPos.y))
            drawCircle(color = PLAYER_COLOR, radius = 14f * scale, center = pc)
            drawCircle(color = Color.White.copy(alpha = 0.85f), radius = 5f * scale, center = pc)

            if (snapshot.strained) {
                drawRect(color = STRAIN_TINT, topLeft = Offset.Zero, size = size)
            }

            // Virtual joystick.
            joyCenter?.let { c ->
                drawCircle(color = Color.White.copy(alpha = 0.10f), radius = joyMaxRadius, center = c)
                drawCircle(color = Color.White.copy(alpha = 0.30f), radius = 34f, center = joyKnob)
            }
        }

        RunHud(snapshot, Modifier.align(Alignment.TopStart))

        when (snapshot.status) {
            RunStatus.LEVEL_UP -> LevelUpOverlay(snapshot) { engine.choose(it) }
            RunStatus.VICTORY, RunStatus.DEFEAT -> SummaryOverlay(
                snapshot = snapshot,
                onPlayAgain = { viewModel.exitRun() },
                onLeave = { viewModel.exitRun(); onBack() }
            )
            RunStatus.RUNNING -> {}
        }
    }
}

@Composable
private fun RunHud(snapshot: RunSnapshot, modifier: Modifier = Modifier) {
    Column(modifier.padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HudChip("Wave", "${snapshot.wave}/${snapshot.totalWaves}")
            HudChip("Lvl", "${snapshot.level}/${snapshot.levelCap}")
            HudChip("Gold", "${snapshot.gold}")
            HudChip("Score", "${snapshot.score}")
        }
        if (snapshot.challengeModeName != ChallengeMode.NONE.name) {
            Spacer(Modifier.height(6.dp))
            HudChip("Mode", snapshot.challengeModeName)
        }
        Spacer(Modifier.height(8.dp))
        Meter(
            fraction = if (snapshot.playerMaxHealth > 0f) (snapshot.playerHealth / snapshot.playerMaxHealth) else 0f,
            color = HEALTH_COLOR,
            label = "HP ${snapshot.playerHealth.toInt()}/${snapshot.playerMaxHealth.toInt()}"
        )
        Spacer(Modifier.height(4.dp))
        Meter(
            fraction = if (snapshot.xpToNext > 0f) (snapshot.xp / snapshot.xpToNext) else 0f,
            color = XP_COLOR,
            label = "XP"
        )
        if (snapshot.held.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                snapshot.held.forEach { h ->
                    Box(
                        Modifier
                            .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text("${h.name} ${h.rank}", style = MaterialTheme.typography.labelSmall, color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
private fun HudChip(label: String, value: String) {
    Box(
        Modifier
            .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.6f))
            Text(value, style = MaterialTheme.typography.labelMedium, color = Color.White, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun Meter(fraction: Float, color: Color, label: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(16.dp)
            .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .background(color.copy(alpha = 0.8f), RoundedCornerShape(8.dp))
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            modifier = Modifier.align(Alignment.Center)
        )
    }
}

@Composable
private fun LevelUpOverlay(snapshot: RunSnapshot, onChoose: (com.lifeops.app.game.run.LevelUpOption) -> Unit) {
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
                    FilledTonalButton(onClick = { onChoose(opt) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text(opt.label, fontWeight = FontWeight.Bold)
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
private fun SummaryOverlay(snapshot: RunSnapshot, onPlayAgain: () -> Unit, onLeave: () -> Unit) {
    val victory = snapshot.status == RunStatus.VICTORY
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
                    if (victory) "Cleared" else "Overrun",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (victory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
                Text("Score ${snapshot.score}", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Reached wave ${snapshot.wave}/${snapshot.totalWaves} · level ${snapshot.level}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                Spacer(Modifier.height(6.dp))
                Button(onClick = onPlayAgain, modifier = Modifier.fillMaxWidth()) { Text("Back to Loadout") }
                OutlinedButton(onClick = onLeave, modifier = Modifier.fillMaxWidth()) { Text("Leave") }
            }
        }
    }
}

private fun enemyColor(type: EnemyType): Color = when (type) {
    EnemyType.SHAMBLER -> Color(0xFF7CB342)
    EnemyType.HUSK -> Color(0xFFEF6C00)
    EnemyType.ABOMINATION -> Color(0xFF8E24AA)
}

private val ARENA_BG = Color(0xFF101014)
private val ARENA_FLOOR = Color(0xFF1B1B22)
private val PLAYER_COLOR = Color(0xFF42A5F5)
private val PROJECTILE_COLOR = Color(0xFFFFF176)
private val XP_COLOR = Color(0xFF66BB6A)
private val GOLD_COLOR = Color(0xFFFFCA28)
private val HEALTH_COLOR = Color(0xFFEF5350)
private val STRAIN_TINT = Color(0x22FF00FF)
