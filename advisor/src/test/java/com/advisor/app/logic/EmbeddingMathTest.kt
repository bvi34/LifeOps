package com.advisor.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class EmbeddingMathTest {

    @Test
    fun identical_vectors_have_cosine_one() {
        val v = floatArrayOf(1f, 2f, 3f)
        assertEquals(1.0, EmbeddingMath.cosine(v, v), 1e-9)
    }

    @Test
    fun orthogonal_vectors_have_cosine_zero() {
        assertEquals(0.0, EmbeddingMath.cosine(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)), 1e-9)
    }

    @Test
    fun opposite_vectors_have_cosine_minus_one() {
        assertEquals(-1.0, EmbeddingMath.cosine(floatArrayOf(1f, 1f), floatArrayOf(-1f, -1f)), 1e-9)
    }

    @Test
    fun scale_does_not_change_cosine() {
        val a = floatArrayOf(1f, 2f, 3f)
        val b = floatArrayOf(10f, 20f, 30f)
        assertEquals(1.0, EmbeddingMath.cosine(a, b), 1e-9)
    }

    @Test
    fun zero_and_mismatched_vectors_score_zero_not_crash() {
        assertEquals(0.0, EmbeddingMath.cosine(floatArrayOf(0f, 0f), floatArrayOf(1f, 1f)), 1e-9)
        assertEquals(0.0, EmbeddingMath.cosine(floatArrayOf(1f), floatArrayOf(1f, 2f)), 1e-9)
        assertEquals(0.0, EmbeddingMath.cosine(FloatArray(0), FloatArray(0)), 1e-9)
    }

    @Test
    fun normalize_yields_unit_length() {
        val n = EmbeddingMath.normalize(floatArrayOf(3f, 4f))
        val len = sqrt((n[0] * n[0] + n[1] * n[1]).toDouble())
        assertEquals(1.0, len, 1e-6)
        // Direction preserved: cosine with the original is 1.
        assertEquals(1.0, EmbeddingMath.cosine(n, floatArrayOf(3f, 4f)), 1e-6)
    }

    @Test
    fun normalize_of_zero_is_safe() {
        val n = EmbeddingMath.normalize(floatArrayOf(0f, 0f))
        assertTrue(n.all { it == 0f })
    }
}
