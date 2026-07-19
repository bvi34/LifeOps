package com.lifeops.app.game.run

import com.lifeops.app.game.core.RunSeed
import com.lifeops.app.game.core.Vec2
import kotlin.math.min

/**
 * The play space: a single open screen (Geometry Wars) with no interior obstacles, but one you can
 * widen. A fixed maximum [worldSize] keeps the camera stable; the *active* region is a centred
 * rectangle that starts small and grows a stage at a time when the player buys an expansion (the
 * "unlock to widen the space"). Everything outside the active region is dead margin, rendered dim.
 *
 * Pure and deterministic (no android.*). Player-placed turrets/obstacles (stage 2) will snap to the
 * [cellSize] grid this exposes.
 */
class ArenaState(
    val worldSize: Vec2 = Vec2(760f, 760f),
    val cellSize: Float = 40f,
    private val baseHalf: Vec2 = Vec2(150f, 190f),
    private val stepHalf: Vec2 = Vec2(95f, 85f),
    val maxStage: Int = 3,
    private val costs: List<Int> = listOf(30, 60, 120),
) {
    var stage: Int = 0
        private set

    val center: Vec2 = Vec2(worldSize.x / 2f, worldSize.y / 2f)

    private fun halfX(): Float = min(baseHalf.x + stepHalf.x * stage, worldSize.x / 2f)
    private fun halfY(): Float = min(baseHalf.y + stepHalf.y * stage, worldSize.y / 2f)

    fun min(): Vec2 = Vec2(center.x - halfX(), center.y - halfY())
    fun max(): Vec2 = Vec2(center.x + halfX(), center.y + halfY())

    fun canExpand(): Boolean = stage < maxStage
    fun nextCost(): Int? = if (canExpand()) costs[stage.coerceIn(0, costs.size - 1)] else null

    fun expand(): Boolean {
        if (!canExpand()) return false
        stage++
        return true
    }

    /** Keep a point of radius [r] inside the active region. */
    fun clamp(p: Vec2, r: Float): Vec2 {
        val lo = min()
        val hi = max()
        return Vec2(
            p.x.coerceIn(lo.x + r, hi.x - r),
            p.y.coerceIn(lo.y + r, hi.y - r),
        )
    }

    fun contains(p: Vec2): Boolean {
        val lo = min(); val hi = max()
        return p.x in lo.x..hi.x && p.y in lo.y..hi.y
    }

    /** A point just inside one of the four active edges — where a fresh wave enters. */
    fun randomEdge(rng: RunSeed): Vec2 {
        val lo = min(); val hi = max()
        val inset = 6f
        return when (rng.nextInt(4)) {
            0 -> Vec2(rng.nextFloat(lo.x, hi.x), lo.y + inset)
            1 -> Vec2(rng.nextFloat(lo.x, hi.x), hi.y - inset)
            2 -> Vec2(lo.x + inset, rng.nextFloat(lo.y, hi.y))
            else -> Vec2(hi.x - inset, rng.nextFloat(lo.y, hi.y))
        }
    }
}
