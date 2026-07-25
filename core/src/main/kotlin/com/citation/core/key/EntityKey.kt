package com.citation.core.key

/**
 * A partitioned, Jira-style entity key: `{namespace}-{Type}-{seq}` — e.g. `ER-Book-3`, `ER-Note-88`.
 *
 * The scheme exists so **every app on the LifeOps spine mints keys offline with zero coordination**.
 * The namespace segment is the minting app's own; because each peer owns a distinct namespace, two
 * apps can both allocate sequence `3` for a `Book` and never collide (`ER-Book-3` vs `LO-Book-3`).
 * There is no shared allocator to reach — a hard requirement for an offline-first reader that must
 * keep working with no connection.
 *
 * Provenance is *baked into the key*: reading `ER-Book-3` tells you which app authored it and what
 * it is, without a lookup. That is what lets the sync seam route and reconcile records by key alone.
 *
 * @property namespace the minting app's partition (Citation reader = [CITATION_NAMESPACE]).
 * @property type the entity type segment (`Book`, `Note`, `Highlight`, …).
 * @property sequence the per-(namespace, type) monotonic ordinal, minted locally.
 */
data class EntityKey(
    val namespace: String,
    val type: String,
    val sequence: Long
) {
    init {
        require(namespace.isNotBlank()) { "namespace must not be blank" }
        require(type.isNotBlank()) { "type must not be blank" }
        require(!namespace.contains('-')) { "namespace must not contain '-': $namespace" }
        require(!type.contains('-')) { "type must not contain '-': $type" }
        require(sequence >= 0) { "sequence must be non-negative: $sequence" }
    }

    /** Canonical `NS-Type-seq` string form. */
    override fun toString(): String = "$namespace-$type-$sequence"

    companion object {
        /** Citation's own partition. `ER` = the eReader peer on the LifeOps spine. */
        const val CITATION_NAMESPACE = "ER"

        /** LifeOps center's partition, for recognising down-synced (center-authored) keys. */
        const val LIFEOPS_NAMESPACE = "LO"

        /**
         * Parse [raw] back into an [EntityKey]. The sequence is the last `-`-delimited segment and
         * the namespace the first; anything between is the (possibly hyphen-free) type. Returns
         * `null` on a malformed key rather than throwing, so callers can treat unknown strings as
         * "not one of ours".
         */
        fun parse(raw: String): EntityKey? {
            val trimmed = raw.trim()
            val parts = trimmed.split('-')
            if (parts.size != 3) return null
            val (ns, type, seqStr) = parts
            val seq = seqStr.toLongOrNull() ?: return null
            if (ns.isBlank() || type.isBlank() || seq < 0) return null
            return EntityKey(ns, type, seq)
        }
    }
}

/** The entity types Citation mints keys for. Kept as constants so the type segment stays stable. */
object EntityType {
    const val BOOK = "Book"
    const val NOTE = "Note"
    const val HIGHLIGHT = "Highlight"
    const val CHAPTER = "Chapter"
}
