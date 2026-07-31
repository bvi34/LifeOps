package com.lifeops.app.game.core

import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * A tiny immutable 2D vector. The whole run simulation is JVM-pure (no android.*), so it
 * can be unit-tested on the desktop and stepped deterministically. Keep it allocation-cheap
 * but readable — entity counts are in the hundreds, not thousands (see DESIGN.md §11).
 */
data class Vec2(val x: Float, val y: Float) {
    operator fun plus(o: Vec2) = Vec2(x + o.x, y + o.y)
    operator fun minus(o: Vec2) = Vec2(x - o.x, y - o.y)
    operator fun times(s: Float) = Vec2(x * s, y * s)

    fun length(): Float = hypot(x, y)
    fun distanceTo(o: Vec2): Float = hypot(x - o.x, y - o.y)

    /** Unit vector in the same direction, or [ZERO] for a zero-length vector. */
    fun normalized(): Vec2 {
        val len = sqrt(x * x + y * y)
        return if (len <= EPSILON) ZERO else Vec2(x / len, y / len)
    }

    fun clampLength(max: Float): Vec2 {
        val len = length()
        return if (len <= max || len <= EPSILON) this else this * (max / len)
    }

    companion object {
        const val EPSILON = 1e-4f
        val ZERO = Vec2(0f, 0f)
    }
}
