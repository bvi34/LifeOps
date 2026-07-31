package com.lifeops.app.game.core

/**
 * Bounds runaway modifier chains (DESIGN.md invariant #3: "degrade, don't crash"). When a
 * Phase-2 combo goes exponential — an on-hit effect that spawns projectiles that trigger more
 * on-hit effects — the resolver refuses to keep expanding within a frame instead of ANR-ing.
 *
 * Two independent caps:
 *  - a per-frame effect **budget** (total effects resolved this frame), and
 *  - a **recursion depth** cap (how deep one effect's follow-ons may nest).
 *
 * Exceeding either sets [strained] for the frame; the renderer reads it to draw a "reality
 * strain" flourish rather than silently dropping work. Reset once per frame via [beginFrame].
 */
class EffectResolver(
    private val perFrameBudget: Int = DEFAULT_BUDGET,
    private val maxDepth: Int = DEFAULT_MAX_DEPTH,
) {
    private var spentThisFrame = 0
    private var depth = 0
    var strained = false
        private set

    fun beginFrame() {
        spentThisFrame = 0
        depth = 0
        strained = false
    }

    /** Effects resolved so far this frame — exposed for tests and debug HUD. */
    val spent: Int get() = spentThisFrame

    /**
     * Runs [effect] iff there is budget and depth headroom, counting it against both caps and
     * restoring depth afterward. Returns true if it ran, false if throttled. Nested resolves
     * inside [effect] naturally see the incremented depth.
     */
    fun resolve(effect: () -> Unit): Boolean {
        if (spentThisFrame >= perFrameBudget || depth >= maxDepth) {
            strained = true
            return false
        }
        spentThisFrame++
        depth++
        try {
            effect()
        } finally {
            depth--
        }
        return true
    }

    companion object {
        const val DEFAULT_BUDGET = 2000
        const val DEFAULT_MAX_DEPTH = 8
    }
}
