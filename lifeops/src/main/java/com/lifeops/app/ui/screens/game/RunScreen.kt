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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
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
import com.lifeops.app.game.run.chooseOverflow
import com.lifeops.app.game.run.chooseSetBonus
import com.lifeops.app.game.run.reload
import com.lifeops.app.game.run.revive
import com.lifeops.app.game.run.skipStore
import com.lifeops.app.ui.components.AppHeader
import com.lifeops.app.ui.components.BackNavIcon
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.isActive

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
            drawNeonStarfield(scale)
            val amin = snapshot.activeMin
            val amax = snapshot.activeMax
            drawRect(
                color = FLOOR_COLOR,
                topLeft = Offset(sx(amin.x), sy(amin.y)),
                size = androidx.compose.ui.geometry.Size((amax.x - amin.x) * scale, (amax.y - amin.y) * scale)
            )
            // Vector floor: layered cyan/magenta grid with glowing intersections, leaning into the
            // Geometry Wars-inspired wireframe read while keeping the active arena bounds legible.
            var gx = amin.x
            while (gx <= amax.x + 0.5f) {
                val a = if (((gx / snapshot.cellSize).toInt() % 4) == 0) 0.28f else 0.14f
                drawGlowLine(NEON_CYAN.copy(alpha = a), Offset(sx(gx), sy(amin.y)), Offset(sx(gx), sy(amax.y)), 1.25f * scale)
                gx += snapshot.cellSize
            }
            var gy = amin.y
            while (gy <= amax.y + 0.5f) {
                val a = if (((gy / snapshot.cellSize).toInt() % 4) == 0) 0.24f else 0.12f
                drawGlowLine(NEON_MAGENTA.copy(alpha = a), Offset(sx(amin.x), sy(gy)), Offset(sx(amax.x), sy(gy)), 1.1f * scale)
                gy += snapshot.cellSize
            }
            var diag = -((amax.y - amin.y))
            while (diag < (amax.x - amin.x)) {
                val startX = (amin.x + diag).coerceAtLeast(amin.x)
                val startY = (amin.y - diag).coerceIn(amin.y, amax.y)
                val endX = (startX + (amax.y - startY)).coerceAtMost(amax.x)
                val endY = (startY + (endX - startX)).coerceAtMost(amax.y)
                drawLine(VECTOR_DIAGONAL, Offset(sx(startX), sy(startY)), Offset(sx(endX), sy(endY)), 0.75f * scale)
                diag += snapshot.cellSize * 2f
            }
            // Bright boundary so the current edge reads clearly.
            drawNeonRect(
                color = ARENA_EDGE,
                topLeft = Offset(sx(amin.x), sy(amin.y)),
                width = (amax.x - amin.x) * scale,
                height = (amax.y - amin.y) * scale,
                strokeWidth = 2.2f * scale
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
                drawNeonRect(color = col, topLeft = Offset(c.x - half, c.y - half), width = half * 2, height = half * 2, strokeWidth = 1.8f * scale)
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
                val path = enemyPath(e.type, c, r, facing)
                drawPath(path, color = body.copy(alpha = 0.22f), style = Stroke(width = 8f * scale))
                drawPath(path, color = body.copy(alpha = 0.55f), style = Stroke(width = 3f * scale))
                drawPath(path, color = body.copy(alpha = 0.18f))
                drawPath(path, color = body, style = Stroke(width = 1.35f * scale))
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
                drawGlowCircle(PROJECTILE_COLOR, radius = 3.5f * scale, center = Offset(sx(p.x), sy(p.y)))
            }
            snapshot.enemyProjectiles.forEach { p ->
                drawGlowCircle(ENEMY_PROJECTILE_COLOR, radius = 4f * scale, center = Offset(sx(p.x), sy(p.y)))
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
            val ship = playerPath(pc, pr, aim)
            drawPath(ship, color = body.copy(alpha = 0.28f * iAlpha), style = Stroke(width = 10f * scale))
            drawPath(ship, color = body.copy(alpha = 0.65f * iAlpha), style = Stroke(width = 3f * scale))
            drawPath(ship, color = body.copy(alpha = 0.16f * iAlpha))
            drawPath(ship, color = Color.White.copy(alpha = 0.86f * iAlpha), style = Stroke(width = 1.4f * scale))
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
                // A dev run's store is free: everything is affordable and nothing is debited.
                StoreOverlay(
                    snapshot = snapshot,
                    balance = if (snapshot.devRun) Int.MAX_VALUE else budget?.currentValue ?: 0,
                    currencyName = if (snapshot.devRun) "Free" else budget?.name ?: "Modifier Budget",
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

