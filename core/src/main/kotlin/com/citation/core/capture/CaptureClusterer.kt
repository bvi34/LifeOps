package com.citation.core.capture

import com.citation.core.key.EntityKey
import com.citation.core.note.Note

/**
 * Groups captured notes that share an identifier into **provisional sources** — before that source is
 * ever a real book record.
 *
 * A pile of frictionless captures is only useful if the three sentences you saved from the same
 * article collapse into one thing. Clustering is *retroactive* and derived: it reads the identifier
 * each note already froze at capture ([Note] source id = cluster id) and buckets by it. Nothing is
 * persisted beyond the notes themselves — a provisional source is a **view**, recomputed on demand, so
 * there is no second store to keep in sync and no schema to migrate.
 *
 * A cluster stays *provisional* until a member note is bound to a real book (see [CapturePromotion]);
 * at that point the cluster reports its [ProvisionalSource.boundBookKey] and is no longer thin.
 */
object CaptureClusterer {

    /** One note's contribution to a cluster: its key, its frozen cluster id, and any binding. */
    data class Member(
        val noteKey: EntityKey,
        val clusterId: String,
        val displayTitle: String,
        val boundBookKey: EntityKey?
    )

    /**
     * A group of captures sharing one identifier, standing in for a source that may not be a record
     * yet.
     *
     * @property clusterId the shared, self-describing identifier.
     * @property rung the ladder rung this identifier sits on (drives triage).
     * @property displayTitle a representative title (the first non-blank member title).
     * @property memberKeys the notes in this cluster, in input order.
     * @property boundBookKey the book this cluster resolved to, or `null` while still provisional.
     */
    data class ProvisionalSource(
        val clusterId: String,
        val rung: ProvenanceRung,
        val displayTitle: String,
        val memberKeys: List<EntityKey>,
        val boundBookKey: EntityKey?
    ) {
        /** Still unbound to a real record — the state promotion later resolves. */
        val isProvisional: Boolean get() = boundBookKey == null

        /** Best identifier is only an app name or timestamp: surface for triage while unbound. */
        val isThin: Boolean get() = isProvisional &&
            (rung == ProvenanceRung.APP_PACKAGE || rung == ProvenanceRung.TIMESTAMP)
    }

    /** Project a capture note into a clustering [Member], or `null` if it carries no cluster id. */
    fun memberOf(note: Note): Member? {
        val clusterId = note.source.sourceId ?: return null
        return Member(
            noteKey = note.key,
            clusterId = clusterId,
            displayTitle = note.source.title,
            boundBookKey = note.source.bookKey
        )
    }

    /** Cluster a list of capture [Note]s (non-capture / id-less notes are ignored). */
    fun clusterNotes(notes: List<Note>): List<ProvisionalSource> =
        cluster(notes.mapNotNull(::memberOf))

    /**
     * Cluster [members] by their shared cluster id, preserving first-seen order both of clusters and
     * of members within a cluster.
     *
     * A cluster's [ProvisionalSource.boundBookKey] is the single book any member has been bound to —
     * promotion binds every member of a cluster together, so they agree; if for any reason they don't,
     * the first binding seen wins (a bound cluster is never demoted back to provisional).
     */
    fun cluster(members: List<Member>): List<ProvisionalSource> {
        val order = LinkedHashMap<String, MutableList<Member>>()
        members.forEach { order.getOrPut(it.clusterId) { mutableListOf() }.add(it) }
        return order.map { (clusterId, group) ->
            ProvisionalSource(
                clusterId = clusterId,
                rung = ProvenanceRung.ofClusterId(clusterId) ?: ProvenanceRung.TIMESTAMP,
                displayTitle = group.firstOrNull { it.displayTitle.isNotBlank() }?.displayTitle
                    ?: group.first().displayTitle,
                memberKeys = group.map { it.noteKey },
                boundBookKey = group.firstNotNullOfOrNull { it.boundBookKey }
            )
        }
    }
}
