package com.advisor.app.logic

import kotlin.math.sqrt

/**
 * The tiny bit of vector arithmetic semantic retrieval needs — cosine similarity and L2
 * normalization — kept pure and dependency-free so it is fully JVM-testable, exactly like the lexical
 * [Retriever] it sits beside. No linear-algebra library: the vectors are small (a few hundred dims)
 * and we only ever need a dot product and a norm.
 */
object EmbeddingMath {

    /**
     * Cosine similarity of two vectors, in `[-1, 1]`. Returns `0` for a zero-length or
     * mismatched-dimension vector rather than throwing — a degenerate embedding should score as
     * "unrelated", not crash retrieval.
     */
    fun cosine(a: FloatArray, b: FloatArray): Double {
        if (a.size != b.size || a.isEmpty()) return 0.0
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            val x = a[i].toDouble()
            val y = b[i].toDouble()
            dot += x * y
            na += x * x
            nb += y * y
        }
        if (na == 0.0 || nb == 0.0) return 0.0
        return dot / (sqrt(na) * sqrt(nb))
    }

    /**
     * A unit-length copy of [v] (L2). Pre-normalizing stored vectors makes cosine a plain dot product;
     * we keep [cosine] robust anyway so callers need not remember to normalize. A zero vector is
     * returned unchanged (it has no direction to preserve).
     */
    fun normalize(v: FloatArray): FloatArray {
        var sum = 0.0
        for (x in v) sum += x.toDouble() * x
        if (sum == 0.0) return v.copyOf()
        val inv = (1.0 / sqrt(sum)).toFloat()
        val out = FloatArray(v.size)
        for (i in v.indices) out[i] = v[i] * inv
        return out
    }
}
