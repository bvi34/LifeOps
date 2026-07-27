@file:OptIn(ExperimentalMaterial3Api::class)

package com.lifeops.app.ui.screens.game

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeops.app.game.content.ChallengeMode
import com.lifeops.app.game.content.EnemyType
import com.lifeops.app.game.content.StartingWeapon
import com.lifeops.app.game.content.StoreCatalog
import com.lifeops.app.game.content.StructureType
import com.lifeops.app.game.core.PickupKind
import com.lifeops.app.game.core.Vec2
import com.lifeops.app.game.run.EffectKind
import com.lifeops.app.game.run.Loadout
import com.lifeops.app.game.run.RunEngine
import com.lifeops.app.game.run.RunInput
import com.lifeops.app.game.run.RunSnapshot
import com.lifeops.app.game.run.RunStatus
import com.lifeops.app.ui.components.AppHeader
import com.lifeops.app.ui.components.BackNavIcon
import kotlinx.coroutines.isActive
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

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

@OptIn(ExperimentalLayoutApi::class)
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RunView(engine: RunEngine, viewModel: RunViewModel, onBack: () -> Unit) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    var snapshot by remember(engine) { mutableStateOf(engine.snapshot()) }
    val move = remember(engine) { mutableStateOf(Vec2.ZERO) }
    var joyCenter by remember(engine) { mutableStateOf<Offset?>(null) }
    var joyKnob by remember(engine) { mutableStateOf(Offset.Zero) }
    // Selected build tool: null = just move; otherwise tapping the arena places this structure.
    var buildType by remember(engine) { mutableStateOf<StructureType?>(null) }
    val worldSize = snapshot.worldSize

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

    // Log the run to the scoreboard once it ends. Keyed on status so it fires exactly on the
    // transition into a terminal state; the ViewModel guards against a double write regardless.
    androidx.compose.runtime.LaunchedEffect(snapshot.status) {
        // A win is final; a defeat is final only once the player can no longer buy back into it —
        // while a revive is still affordable the write waits, so a revived run logs once, with its
        // final score. Accepting the defeat (leaving the summary) records it via the exit handlers.
        if (snapshot.status == RunStatus.VICTORY ||
            (snapshot.status == RunStatus.DEFEAT && !viewModel.canRevive())
        ) {
            viewModel.recordRunEnd(snapshot)
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
                // Tap-to-place when a build tool is selected (drag still moves via the block above).
                .pointerInput(buildType, worldSize) {
                    val type = buildType
                    if (type != null) {
                        detectTapGestures { tap ->
                            val scale = min(size.width / worldSize.x, size.height / worldSize.y)
                            val ox = (size.width - worldSize.x * scale) / 2f
                            val oy = (size.height - worldSize.y * scale) / 2f
                            engine.placeStructure(type, Vec2((tap.x - ox) / scale, (tap.y - oy) / scale))
                        }
                    }
                }
        ) {
            val scale = min(size.width / snapshot.worldSize.x, size.height / snapshot.worldSize.y)
            val originX = (size.width - snapshot.worldSize.x * scale) / 2f
            val originY = (size.height - snapshot.worldSize.y * scale) / 2f
            fun sx(x: Float) = originX + x * scale
            fun sy(y: Float) = originY + y * scale

            // Dead margin over the whole world, then the lit active region on top: expanding the
            // arena lights up more of the margin (the "widen the space" unlock).
            drawRect(
                color = LOCKED_COLOR,
                topLeft = Offset(sx(0f), sy(0f)),
                size = androidx.compose.ui.geometry.Size(snapshot.worldSize.x * scale, snapshot.worldSize.y * scale)
            )
            val amin = snapshot.activeMin
            val amax = snapshot.activeMax
            drawRect(
                color = FLOOR_COLOR,
                topLeft = Offset(sx(amin.x), sy(amin.y)),
                size = androidx.compose.ui.geometry.Size((amax.x - amin.x) * scale, (amax.y - amin.y) * scale)
            )
            // Faint placement grid over the active region (Geometry Wars vibe + future turret pads).
            var gx = amin.x
            while (gx <= amax.x + 0.5f) { drawLine(GRID_LINE, Offset(sx(gx), sy(amin.y)), Offset(sx(gx), sy(amax.y)), 1f); gx += snapshot.cellSize }
            var gy = amin.y
            while (gy <= amax.y + 0.5f) { drawLine(GRID_LINE, Offset(sx(amin.x), sy(gy)), Offset(sx(amax.x), sy(gy)), 1f); gy += snapshot.cellSize }
            // Bright boundary so the current edge reads clearly.
            drawRect(
                color = ARENA_EDGE,
                topLeft = Offset(sx(amin.x), sy(amin.y)),
                size = androidx.compose.ui.geometry.Size((amax.x - amin.x) * scale, (amax.y - amin.y) * scale),
                style = Stroke(2f * scale)
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

            // Placed defenses. Turret = teal box with a barrel toward its target; Barricade = crate.
            // Both darken as they take damage.
            val struct = snapshot.cellSize * scale
            snapshot.structures.forEach { s ->
                val c = Offset(sx(s.pos.x), sy(s.pos.y))
                val half = struct * (if (s.type.isTurret) 0.34f else 0.42f)
                val base = when (s.type) {
                    StructureType.TURRET -> TURRET_COLOR    // artifact auto-turret
                    StructureType.SENTRY -> SENTRY_COLOR    // static gold turret
                    StructureType.BARRICADE -> BARRICADE_COLOR
                    StructureType.DECOY -> DECOY_COLOR      // aggro lure
                }
                // Auto-turrets fade toward transparent as their TTL runs out, telegraphing the despawn.
                val alpha = if (s.artifactTurret) (0.35f + 0.65f * s.ttlFrac) else 1f
                val col = lerp(Color(0xFF3A1010), base, s.healthFrac.coerceIn(0.15f, 1f)).copy(alpha = alpha)
                drawRect(color = col, topLeft = Offset(c.x - half, c.y - half), size = androidx.compose.ui.geometry.Size(half * 2, half * 2))
                if (s.type.isTurret) {
                    val a = atan2(s.aim.y, s.aim.x)
                    drawLine(Color(0xFFB0BEC5).copy(alpha = alpha), c, c + Offset(cos(a), sin(a)) * (half * 1.8f), strokeWidth = 3f * scale)
                }
                // Decoy: a bright beacon core so it reads as a lure, not a wall.
                if (s.type == StructureType.DECOY) {
                    drawCircle(color = Color.White.copy(alpha = 0.85f), radius = half * 0.45f, center = c)
                }
            }

            // Sown proximity mines: a small dark disc with a warning core, and a faint trigger ring
            // once armed. They read as hazards on the floor, distinct from turrets/pickups.
            snapshot.mines.forEach { m ->
                val c = Offset(sx(m.pos.x), sy(m.pos.y))
                if (m.armed) {
                    drawCircle(color = MINE_COLOR.copy(alpha = 0.18f), radius = m.triggerRadius * scale, center = c, style = Stroke(1f * scale))
                }
                drawCircle(color = Color(0xFF2A2A30), radius = 6f * scale, center = c)
                drawCircle(color = if (m.armed) MINE_COLOR else MINE_COLOR.copy(alpha = 0.45f), radius = 3f * scale, center = c)
            }

            snapshot.enemies.forEach { e ->
                val c = Offset(sx(e.pos.x), sy(e.pos.y))
                val r = e.radius * scale
                val facing = atan2(e.facing.y, e.facing.x)
                // Silhouette flashes toward white on a fresh hit.
                val body = lerp(enemyColor(e.type), Color.White, e.hitFlashFrac)
                drawPath(enemyPath(e.type, c, r, facing), color = body)
                // A thin HP arc hugging the silhouette — only while damaged.
                if (e.healthFrac < 0.999f) {
                    val pad = 3f * scale
                    val d = (r + pad) * 2f
                    drawArc(
                        color = HP_ARC_COLOR,
                        startAngle = -90f,
                        sweepAngle = 360f * e.healthFrac,
                        useCenter = false,
                        topLeft = Offset(c.x - r - pad, c.y - r - pad),
                        size = androidx.compose.ui.geometry.Size(d, d),
                        style = Stroke(width = 2.5f * scale)
                    )
                }
            }

            // Transient effects: an expanding white ring where an enemy fell, or a filled orange
            // fireball for an explosive round's blast (sized to its actual radius).
            snapshot.effects.forEach { fx ->
                val c = Offset(sx(fx.pos.x), sy(fx.pos.y))
                when (fx.kind) {
                    EffectKind.EXPLOSION -> {
                        val r = fx.worldRadius * scale * (0.7f + fx.ageFrac * 0.35f)
                        drawCircle(color = EXPLOSION_COLOR.copy(alpha = (1f - fx.ageFrac) * 0.5f), radius = r, center = c)
                        drawCircle(color = EXPLOSION_COLOR.copy(alpha = 1f - fx.ageFrac), radius = r, center = c, style = Stroke(width = 2.5f * scale))
                    }
                    EffectKind.DEATH_BURST -> {
                        val r = fx.worldRadius * scale * (0.6f + fx.ageFrac * 1.7f)
                        drawCircle(
                            color = Color.White.copy(alpha = (1f - fx.ageFrac) * 0.6f),
                            radius = r, center = c, style = Stroke(width = 2f * scale)
                        )
                    }
                }
            }

            snapshot.projectiles.forEach { p ->
                drawCircle(color = PROJECTILE_COLOR, radius = 3.5f * scale, center = Offset(sx(p.x), sy(p.y)))
            }
            snapshot.enemyProjectiles.forEach { p ->
                drawCircle(color = ENEMY_PROJECTILE_COLOR, radius = 4f * scale, center = Offset(sx(p.x), sy(p.y)))
            }

            // Player: an oriented silhouette pointing where it's aiming, a weapon nub whose shape
            // reads the weapon, plus aim guide, muzzle flash, and a red hurt flash.
            val pc = Offset(sx(snapshot.playerPos.x), sy(snapshot.playerPos.y))
            val pr = 14f * scale
            val aim = atan2(snapshot.playerAim.y, snapshot.playerAim.x)
            val aimDir = Offset(cos(aim), sin(aim))

            // Faint aim guide out to the reticle.
            val guideEnd = pc + aimDir * (46f * scale)
            drawLine(
                color = Color.White.copy(alpha = 0.22f),
                start = pc + aimDir * pr,
                end = guideEnd,
                strokeWidth = 1.5f * scale
            )
            drawCircle(color = Color.White.copy(alpha = 0.35f), radius = 3.5f * scale, center = guideEnd, style = Stroke(1.5f * scale))

            // Weapon nub: Sniper = long thin barrel, Gatling = short wide barrel, Shotgun = stubby double.
            val gatling = snapshot.weapon == StartingWeapon.GATLING
            val nubLen = when (snapshot.weapon) {
                StartingWeapon.SNIPER -> 1.95f
                StartingWeapon.GATLING -> 1.35f
                StartingWeapon.SHOTGUN -> 1.15f
                StartingWeapon.HAND_CANNON -> 1.9f
                StartingWeapon.SMG -> 1.3f
            }
            val nubTip = pc + aimDir * (pr * nubLen)
            val nubWidth = when (snapshot.weapon) {
                StartingWeapon.SNIPER -> 3f
                StartingWeapon.GATLING -> 6f
                StartingWeapon.SHOTGUN -> 8f
                StartingWeapon.HAND_CANNON -> 5f
                StartingWeapon.SMG -> 5f
            }
            drawLine(
                color = Color(0xFFB0BEC5),
                start = pc,
                end = nubTip,
                strokeWidth = nubWidth * scale
            )

            // Body silhouette (flashes red when hurt; dims while invulnerable), then a bright core.
            val iAlpha = if (snapshot.playerInvuln) 0.5f else 1f
            val body = lerp(PLAYER_COLOR, HURT_FLASH_COLOR, snapshot.playerHurtFrac).copy(alpha = iAlpha)
            drawPath(playerPath(pc, pr, aim), color = body)
            drawCircle(color = Color.White.copy(alpha = 0.9f * iAlpha), radius = 4.5f * scale, center = pc)

            // Muzzle flash at the barrel tip on each shot.
            if (snapshot.playerMuzzleFrac > 0f) {
                drawCircle(
                    color = MUZZLE_COLOR.copy(alpha = snapshot.playerMuzzleFrac),
                    radius = (if (gatling) 6f else 8f) * scale * (0.6f + snapshot.playerMuzzleFrac),
                    center = nubTip
                )
            }

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

        // Bottom control cluster: the build palette, on-demand reload, and the optional
        // arena-expansion buy all share one bottom-aligned FlowRow. Keeping them in a single
        // wrapping row lets the buttons flow onto extra lines on narrow screens instead of
        // overlapping (the palette and the expansion buy previously collided in the center).
        if (snapshot.status == RunStatus.RUNNING) {
            FlowRow(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 28.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Only gold-buy defenses appear here; the turret is now the auto-deployed Turret artifact.
                StructureType.values().filter { it.buildable }.forEach { type ->
                    val selected = buildType == type
                    val cost = snapshot.structureCosts[type] ?: type.cost
                    val affordable = snapshot.gold >= cost
                    val onClick = { buildType = if (selected) null else type }
                    if (selected) {
                        Button(onClick = onClick) { Text("${type.displayName} ${cost}g") }
                    } else {
                        OutlinedButton(onClick = onClick, enabled = affordable) { Text("${type.displayName} ${cost}g") }
                    }
                }
                // Reload on demand (auto-reload still fires on an empty magazine). Shows live progress.
                val reloadLabel = when {
                    snapshot.reloading -> "Reloading ${(snapshot.reloadFrac * 100).toInt()}%"
                    else -> "Reload ${snapshot.ammo}/${snapshot.magazine}"
                }
                OutlinedButton(
                    onClick = { engine.reload() },
                    enabled = !snapshot.reloading && snapshot.ammo < snapshot.magazine
                ) { Text(reloadLabel) }

                // Widen the arena for gold (the "unlock to widen the space" §7 sink).
                snapshot.expand?.let { prompt ->
                    Button(
                        onClick = { engine.buyExpansion() },
                        enabled = prompt.affordable
                    ) {
                        Text("Expand Arena — ${prompt.cost}g")
                    }
                }
            }
        }

        when (snapshot.status) {
            RunStatus.LEVEL_UP -> LevelUpOverlay(snapshot) { engine.choose(it) }
            RunStatus.SET_BONUS -> SetBonusOverlay(snapshot) { engine.chooseSetBonus(it) }
            RunStatus.STORE -> {
                val budget = Loadout.modifierBudgetResource(ui.resources)
                StoreOverlay(
                    snapshot = snapshot,
                    balance = budget?.currentValue ?: 0,
                    currencyName = budget?.name ?: "Modifier Budget",
                    onBuy = { viewModel.purchaseStore(it) },
                    onSkip = { viewModel.skipStore() },
                )
            }
            RunStatus.OVERFLOW -> OverflowOverlay(snapshot) { engine.chooseOverflow(it) }
            RunStatus.VICTORY, RunStatus.DEFEAT -> SummaryOverlay(
                snapshot = snapshot,
                // Revive is offered only on a defeat the player can still afford to buy back into.
                reviveCost = if (snapshot.status == RunStatus.DEFEAT && viewModel.canRevive())
                    viewModel.reviveCost else null,
                onRevive = { viewModel.revive() },
                // Leaving the summary accepts the defeat: record before exiting (guarded, so a run
                // already logged as final is not written twice).
                onPlayAgain = { viewModel.recordRunEnd(snapshot); viewModel.exitRun() },
                onLeave = { viewModel.recordRunEnd(snapshot); viewModel.exitRun(); onBack() }
            )
            RunStatus.RUNNING -> {}
        }
    }
}

@Composable
private fun RunHud(snapshot: RunSnapshot, modifier: Modifier = Modifier) {
    Column(modifier.padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HudChip("Set", "${snapshot.tier + 1}")
            HudChip("Wave", "${snapshot.wave}/${snapshot.totalWaves}")
            HudChip("Lvl", "${snapshot.level}/${snapshot.levelCap}")
            HudChip("Gold", "${snapshot.gold}")
            HudChip("Score", "${snapshot.score}")
        }
        if (snapshot.tempBuffs.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                snapshot.tempBuffs.forEach { b ->
                    Box(
                        Modifier
                            .background(MUZZLE_COLOR.copy(alpha = 0.25f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            "${b.label}  ${b.secondsLeft.toInt() + 1}s",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White
                        )
                    }
                }
            }
        }
        if (snapshot.challengeModeName != ChallengeMode.NONE.name) {
            Spacer(Modifier.height(6.dp))
            HudChip("Mode", snapshot.challengeModeName)
        }
        snapshot.boss?.let { boss ->
            Spacer(Modifier.height(8.dp))
            Meter(fraction = boss.healthFrac, color = BOSS_COLOR, label = "${boss.name}  ${(boss.healthFrac * 100).toInt()}%")
        }
        Spacer(Modifier.height(8.dp))
        // Discrete hearts instead of an HP bar.
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(snapshot.playerMaxHits) { i ->
                Text(
                    if (i < snapshot.playerHits) "♥" else "♡",
                    color = if (i < snapshot.playerHits) HEALTH_COLOR else Color.White.copy(alpha = 0.3f),
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Meter(
            fraction = if (snapshot.xpToNext > 0f) (snapshot.xp / snapshot.xpToNext) else 0f,
            color = XP_COLOR,
            label = "XP"
        )
        Spacer(Modifier.height(4.dp))
        // Ammo: while reloading, the bar fills with reload progress; otherwise it shows rounds left.
        Meter(
            fraction = if (snapshot.reloading) snapshot.reloadFrac
            else if (snapshot.magazine > 0) snapshot.ammo.toFloat() / snapshot.magazine else 0f,
            color = if (snapshot.reloading) MUZZLE_COLOR else AMMO_COLOR,
            label = if (snapshot.reloading) "Reloading…" else "Ammo ${snapshot.ammo}/${snapshot.magazine}"
        )
        // Spin-up bar (Gatling): shows the fire-rate wind-up while engaged.
        if (snapshot.spinUp && snapshot.spinFrac > 0.01f) {
            Spacer(Modifier.height(4.dp))
            Meter(fraction = snapshot.spinFrac, color = SPIN_COLOR, label = "Spin ${(snapshot.spinFrac * 100).toInt()}%")
        }
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
private fun SetBonusOverlay(
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
private fun StoreOverlay(
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
                    Text("$balance $currencyName", style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary)
                }
                Text(
                    "Spend banked $currencyName to unlock something real — kept for future runs.",
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
private fun OverflowOverlay(
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
private fun SummaryOverlay(
    snapshot: RunSnapshot,
    /** Non-null only on a defeat the player can still afford to revive from; the button's price. */
    reviveCost: Int?,
    onRevive: () -> Unit,
    onPlayAgain: () -> Unit,
    onLeave: () -> Unit,
) {
    // Endless mode ends only in defeat — you hold out as long as you can.
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
                    "Overrun",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )
                Text("Score ${snapshot.score}", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Reached set ${snapshot.tier + 1} · wave ${snapshot.wave}/${snapshot.totalWaves} · level ${snapshot.level}",
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

private fun enemyColor(type: EnemyType): Color = when (type) {
    EnemyType.SHAMBLER -> Color(0xFF7CB342)
    EnemyType.HUSK -> Color(0xFFEF6C00)
    EnemyType.SPITTER -> Color(0xFF26C6DA)
    EnemyType.RUSHER -> Color(0xFFFFEE58)
    EnemyType.BRUTE -> Color(0xFFC62828)
    EnemyType.ABOMINATION -> Color(0xFF8E24AA)
    EnemyType.SPITTER_BOSS -> Color(0xFF00ACC1)
    EnemyType.RUSHER_BOSS -> Color(0xFFF9A825)
}

/**
 * Per-type silhouette as polar vertices (angleOffset radians, radiusMultiplier), rotated by
 * [facing] so each enemy points where it's heading. Distinct shapes let type read at a glance:
 * Shambler = a dart, Husk = a hexagon, Abomination = a spiked star.
 */
private fun enemyVerts(type: EnemyType): List<Pair<Float, Float>> = when (type) {
    // Dart: a sharp nose at facing (0 rad) with two swept-back tails.
    EnemyType.SHAMBLER -> listOf(0f to 1.35f, 2.5f to 0.95f, Math.PI.toFloat() to 0.45f, -2.5f to 0.95f)
    // Hexagon.
    EnemyType.HUSK -> (0 until 6).map { (it * (2.0 * Math.PI / 6.0)).toFloat() to 1f }
    // Diamond — a hovering ranged spitter.
    EnemyType.SPITTER -> listOf(0f to 1.3f, (Math.PI / 2).toFloat() to 0.9f, Math.PI.toFloat() to 1.3f, (3 * Math.PI / 2).toFloat() to 0.9f)
    // Chevron — a fast, sharp rusher.
    EnemyType.RUSHER -> listOf(0f to 1.5f, 2.1f to 1.0f, Math.PI.toFloat() to 0.3f, -2.1f to 1.0f)
    // Pentagon — a bulky brute.
    EnemyType.BRUTE -> (0 until 5).map { (it * (2.0 * Math.PI / 5.0)).toFloat() to 1f }
    // Twelve-point spiked star.
    EnemyType.ABOMINATION -> (0 until 12).map {
        (it * (2.0 * Math.PI / 12.0)).toFloat() to if (it % 2 == 0) 1.2f else 0.62f
    }
    // Boss variants: bigger stars so they read as bosses of their archetype.
    EnemyType.SPITTER_BOSS -> (0 until 8).map { (it * (2.0 * Math.PI / 8.0)).toFloat() to if (it % 2 == 0) 1.3f else 0.7f }
    EnemyType.RUSHER_BOSS -> (0 until 10).map { (it * (2.0 * Math.PI / 10.0)).toFloat() to if (it % 2 == 0) 1.35f else 0.6f }
}

private fun enemyPath(type: EnemyType, center: Offset, rScreen: Float, facing: Float): Path {
    val path = Path()
    enemyVerts(type).forEachIndexed { i, (a, rm) ->
        val x = center.x + cos(facing + a) * rScreen * rm
        val y = center.y + sin(facing + a) * rScreen * rm
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
}

/** The player silhouette: a rounded arrowhead pointing along [facing] (the aim direction). */
private val PLAYER_VERTS = listOf(0f to 1.35f, 2.3f to 1.05f, Math.PI.toFloat() to 0.7f, -2.3f to 1.05f)

private fun playerPath(center: Offset, rScreen: Float, facing: Float): Path {
    val path = Path()
    PLAYER_VERTS.forEachIndexed { i, (a, rm) ->
        val x = center.x + cos(facing + a) * rScreen * rm
        val y = center.y + sin(facing + a) * rScreen * rm
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
}

private val ARENA_BG = Color(0xFF101014)
private val FLOOR_COLOR = Color(0xFF1A2230)
private val LOCKED_COLOR = Color(0xFF0C0C10)
private val GRID_LINE = Color(0x14FFFFFF)
private val ARENA_EDGE = Color(0xFF4FC3F7)
private val TURRET_COLOR = Color(0xFF26A69A)
private val SENTRY_COLOR = Color(0xFF5C6BC0)
private val BARRICADE_COLOR = Color(0xFF8D6E63)
private val PLAYER_COLOR = Color(0xFF42A5F5)
private val PROJECTILE_COLOR = Color(0xFFFFF176)
private val ENEMY_PROJECTILE_COLOR = Color(0xFFFF5252)
private val XP_COLOR = Color(0xFF66BB6A)
private val GOLD_COLOR = Color(0xFFFFCA28)
private val HEALTH_COLOR = Color(0xFFEF5350)
private val STRAIN_TINT = Color(0x22FF00FF)
private val HP_ARC_COLOR = Color(0xFFECEFF1)
private val BOSS_COLOR = Color(0xFFAB47BC)
private val MUZZLE_COLOR = Color(0xFFFFF59D)
private val EXPLOSION_COLOR = Color(0xFFFF7043)
private val MINE_COLOR = Color(0xFFFFA726)
private val DECOY_COLOR = Color(0xFFAB47BC)
private val HURT_FLASH_COLOR = Color(0xFFEF5350)
private val AMMO_COLOR = Color(0xFF90A4AE)
private val SPIN_COLOR = Color(0xFFFFB300)
