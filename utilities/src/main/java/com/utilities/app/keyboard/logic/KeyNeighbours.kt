package com.utilities.app.keyboard.logic

import kotlin.math.abs

/**
 * Which letters sit next to which, worked out from the layout rather than typed out by hand.
 *
 * A correction is a guess about what somebody's thumb did, and the overwhelmingly likeliest thing a
 * thumb does wrong is land one key over. So `sog` is very probably `dog` and only distantly `log`,
 * even though both are one letter away and `log` is the commoner word — which is a thing the
 * keyboard can only know if it knows where the keys are.
 *
 * It is derived from [KeyLayouts] for the same reason the layouts are data in the first place: a
 * hand-written neighbour table is a second description of the keyboard, and a second description is
 * a thing that drifts. Move a key and this moves with it.
 *
 * Two letters are neighbours when their caps are on the same row or on rows next to each other and
 * their centres are closer than three quarters of their combined width — which on QWERTY makes `q`
 * a neighbour of `w` and `a` but not of `e`, and puts `n` under `h` where the thumb finds it. The
 * widths come from the layout, so the shift key's extra width pushing `z` to the right is accounted
 * for without anybody having to remember that it does.
 */
class KeyNeighbours private constructor(private val neighbours: Map<Char, Set<Char>>) {

    /** Whether [a] and [b] are different keys that touch. */
    fun adjacent(a: Char, b: Char): Boolean = a != b && neighbours[a]?.contains(b) == true

    /** What is next to [letter], for a test to read and for nothing else. */
    fun of(letter: Char): Set<Char> = neighbours[letter].orEmpty()

    companion object {

        /** How close two centres have to be, as a fraction of the two keys' combined width. */
        private const val REACH = 0.75f

        /** The letters page, which is the only one anybody is corrected on. */
        val QWERTY: KeyNeighbours by lazy { of(KeyLayouts.of(Layer.LETTERS)) }

        fun of(layout: KeyboardLayout): KeyNeighbours {
            val caps = ArrayList<Cap>()
            layout.rows.forEachIndexed { rowIndex, row ->
                val total = row.keys.sumOf { it.weight.toDouble() }.toFloat()
                if (total <= 0f) return@forEachIndexed
                var left = 0f
                row.keys.forEach { key ->
                    val width = key.weight / total
                    val letter = key.output?.takeIf { it.length == 1 && it[0].isLetter() }?.get(0)
                    if (letter != null) caps.add(Cap(letter.lowercaseChar(), rowIndex, left + width / 2f, width))
                    left += width
                }
            }

            val neighbours = HashMap<Char, MutableSet<Char>>()
            caps.forEach { one ->
                caps.forEach { other ->
                    if (one.letter != other.letter && touching(one, other)) {
                        neighbours.getOrPut(one.letter) { HashSet() }.add(other.letter)
                    }
                }
            }
            return KeyNeighbours(neighbours)
        }

        private fun touching(one: Cap, other: Cap): Boolean =
            abs(one.row - other.row) <= 1 && abs(one.centre - other.centre) < (one.width + other.width) * REACH

        private class Cap(val letter: Char, val row: Int, val centre: Float, val width: Float)
    }
}
