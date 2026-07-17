package com.lifeops.app.game.core

/**
 * Deterministic RNG for a run. Runs are week-seeded (DESIGN.md §7): hashing a closed week's
 * snapshot into the seed makes the scoreboard a record of which weeks were legendary, and makes
 * a run reproducible for testing. A small xorshift keeps it dependency-free and identical across
 * JVM and device — [java.util.Random] would do too, but this documents intent and never changes
 * algorithm out from under a stored seed.
 */
class RunSeed(seed: Long) {
    private var state: Long = if (seed == 0L) 0x9E3779B97F4A7C15uL.toLong() else seed

    private fun nextLong(): Long {
        var x = state
        x = x xor (x shl 13)
        x = x xor (x ushr 7)
        x = x xor (x shl 17)
        state = x
        return x
    }

    /** Uniform float in [0, 1). */
    fun nextFloat(): Float {
        val bits = (nextLong() ushr 40).toInt() // top 24 bits
        return bits / 16_777_216f
    }

    /** Uniform int in [0, bound). */
    fun nextInt(bound: Int): Int {
        require(bound > 0)
        return (nextFloat() * bound).toInt().coerceIn(0, bound - 1)
    }

    fun nextFloat(min: Float, max: Float): Float = min + nextFloat() * (max - min)

    /** True with probability [p]. */
    fun chance(p: Float): Boolean = nextFloat() < p

    companion object {
        /**
         * Fold an arbitrary week key + snapshot signature into a seed. Any stable string works;
         * we mix it with a splitmix-style avalanche so adjacent weeks don't produce adjacent runs.
         */
        fun fromWeek(weekKey: String): Long {
            var h = 0xCBF29CE484222325uL.toLong()
            for (c in weekKey) {
                h = h xor c.code.toLong()
                h *= 0x100000001B3uL.toLong()
            }
            // splitmix64 finalizer
            var z = h + -0x61c8864680b583ebL
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
            return z xor (z ushr 31)
        }
    }
}
