@file:OptIn(ExperimentalMaterial3Api::class)

package com.lifeops.app.ui.screens.game

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.lifeops.app.game.content.EnemyType
import kotlin.math.cos
import kotlin.math.sin

/**
 * How the arena is drawn: the neon primitives, the enemy silhouettes, and every colour the
 * run uses.
 *
 * One file so the look is one decision. A palette scattered through the screen that draws with it is
 * a palette nobody can change without hunting.
 */

internal fun DrawScope.drawGlowLine(color: Color, start: Offset, end: Offset, strokeWidth: Float) {
    drawLine(color.copy(alpha = color.alpha * 0.22f), start, end, strokeWidth * 7f, cap = StrokeCap.Round)
    drawLine(color.copy(alpha = color.alpha * 0.45f), start, end, strokeWidth * 3f, cap = StrokeCap.Round)
    drawLine(color, start, end, strokeWidth, cap = StrokeCap.Round)
}

internal fun DrawScope.drawGlowCircle(color: Color, radius: Float, center: Offset) {
    drawCircle(color.copy(alpha = 0.16f), radius = radius * 3.2f, center = center)
    drawCircle(color.copy(alpha = 0.35f), radius = radius * 1.8f, center = center)
    drawCircle(color = color, radius = radius, center = center)
}

internal fun DrawScope.drawNeonRect(color: Color, topLeft: Offset, width: Float, height: Float, strokeWidth: Float) {
    val size = androidx.compose.ui.geometry.Size(width, height)
    drawRect(color = color.copy(alpha = 0.16f), topLeft = topLeft, size = size, style = Stroke(strokeWidth * 6f))
    drawRect(color = color.copy(alpha = 0.36f), topLeft = topLeft, size = size, style = Stroke(strokeWidth * 2.6f))
    drawRect(color = color, topLeft = topLeft, size = size, style = Stroke(strokeWidth))
}

internal fun DrawScope.drawNeonStarfield(scale: Float) {
    val step = 96f * scale.coerceAtLeast(0.75f)
    var x = step * 0.45f
    var column = 0
    while (x < size.width) {
        var y = step * (0.35f + (column % 3) * 0.19f)
        var row = 0
        while (y < size.height) {
            val tint = if ((row + column) % 2 == 0) NEON_CYAN else NEON_MAGENTA
            drawCircle(tint.copy(alpha = 0.10f), radius = 1.4f * scale, center = Offset(x, y))
            if ((row + column) % 5 == 0) {
                drawLine(tint.copy(alpha = 0.08f), Offset(x - 7f * scale, y), Offset(x + 7f * scale, y), 0.8f * scale)
                drawLine(tint.copy(alpha = 0.08f), Offset(x, y - 7f * scale), Offset(x, y + 7f * scale), 0.8f * scale)
            }
            y += step
            row++
        }
        x += step
        column++
    }
}

internal fun enemyColor(type: EnemyType): Color = when (type) {
    EnemyType.SHAMBLER -> Color(0xFF7CB342)
    EnemyType.HUSK -> Color(0xFFEF6C00)
    EnemyType.SPITTER -> Color(0xFF26C6DA)
    EnemyType.RUSHER -> Color(0xFFFFEE58)
    EnemyType.NEST -> Color(0xFFEC407A)
    EnemyType.ABOMINATION -> Color(0xFF8E24AA)
    EnemyType.SPITTER_BOSS -> Color(0xFF00ACC1)
    EnemyType.RUSHER_BOSS -> Color(0xFFF9A825)
    EnemyType.MOTHER -> Color(0xFFAD1457)
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
    // Lumpy egg-sac — a bumpy octagon that reads as a stationary hatchery.
    EnemyType.NEST -> (0 until 8).map { (it * (2.0 * Math.PI / 8.0)).toFloat() to if (it % 2 == 0) 1.1f else 0.8f }
    // Twelve-point spiked star.
    EnemyType.ABOMINATION -> (0 until 12).map {
        (it * (2.0 * Math.PI / 12.0)).toFloat() to if (it % 2 == 0) 1.2f else 0.62f
    }
    // Boss variants: bigger stars so they read as bosses of their archetype.
    EnemyType.SPITTER_BOSS -> (0 until 8).map { (it * (2.0 * Math.PI / 8.0)).toFloat() to if (it % 2 == 0) 1.3f else 0.7f }
    EnemyType.RUSHER_BOSS -> (0 until 10).map { (it * (2.0 * Math.PI / 10.0)).toFloat() to if (it % 2 == 0) 1.35f else 0.6f }
    // The Mother — a swollen sac ringed with many small bumps, bigger than a Nest so she reads as its boss.
    EnemyType.MOTHER -> (0 until 14).map { (it * (2.0 * Math.PI / 14.0)).toFloat() to if (it % 2 == 0) 1.3f else 0.88f }
}

internal fun enemyPath(type: EnemyType, center: Offset, rScreen: Float, facing: Float): Path {
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

internal fun playerPath(center: Offset, rScreen: Float, facing: Float): Path {
    val path = Path()
    PLAYER_VERTS.forEachIndexed { i, (a, rm) ->
        val x = center.x + cos(facing + a) * rScreen * rm
        val y = center.y + sin(facing + a) * rScreen * rm
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
}

internal val ARENA_BG = Color(0xFF05050E)

internal val FLOOR_COLOR = Color(0xFF081527)

internal val LOCKED_COLOR = Color(0xFF02030A)

internal val NEON_CYAN = Color(0xFF00E5FF)

internal val NEON_MAGENTA = Color(0xFFFF2BD6)

internal val VECTOR_DIAGONAL = Color(0x1414F7FF)

internal val ARENA_EDGE = Color(0xFF00E5FF)

internal val TURRET_COLOR = Color(0xFF26A69A)

internal val SENTRY_COLOR = Color(0xFF5C6BC0)

internal val BARRICADE_COLOR = Color(0xFF8D6E63)

internal val PLAYER_COLOR = Color(0xFF42A5F5)

internal val PROJECTILE_COLOR = Color(0xFFFFF176)

internal val ENEMY_PROJECTILE_COLOR = Color(0xFFFF5252)

internal val XP_COLOR = Color(0xFF66BB6A)

internal val GOLD_COLOR = Color(0xFFFFCA28)

internal val HEALTH_COLOR = Color(0xFFEF5350)

internal val STRAIN_TINT = Color(0x22FF00FF)

internal val HP_ARC_COLOR = Color(0xFFECEFF1)

internal val BOSS_COLOR = Color(0xFFAB47BC)

internal val MUZZLE_COLOR = Color(0xFFFFF59D)

internal val EXPLOSION_COLOR = Color(0xFFFF7043)

internal val MINE_COLOR = Color(0xFFFFA726)

internal val DECOY_COLOR = Color(0xFFAB47BC)

internal val HURT_FLASH_COLOR = Color(0xFFEF5350)

internal val AMMO_COLOR = Color(0xFF90A4AE)

internal val SPIN_COLOR = Color(0xFFFFB300)
