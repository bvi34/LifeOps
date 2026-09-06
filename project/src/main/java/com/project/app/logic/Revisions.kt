package com.project.app.logic

/**
 * Why a version of a document was kept.
 *
 * The reason is stored rather than inferred, because it is the only thing that makes a list of
 * timestamps readable. "Yesterday, 14:02" tells you nothing; "before you pasted a chapter in,
 * yesterday" is the sentence that lets somebody pick the right one to go back to.
 *
 * [key] is what the database stores — a string rather than an ordinal, so a reason can be added
 * later without silently re-labelling every version already kept.
 */
enum class RevisionReason(val key: String, val label: String) {
    /** Somebody asked for this one, before doing something they wanted to be able to undo. */
    MANUAL("manual", "Saved by hand"),

    /** Taken before replacing the document with pasted Markdown — the destructive import. */
    IMPORT("import", "Before pasting Markdown in"),

    /** Taken before rebuilding the flattened tables in a document. */
    REPAIR("repair", "Before rebuilding tables"),

    /**
     * Taken before going back to an earlier version.
     *
     * Restoring is itself a whole-document rewrite, and the most likely moment to want the thing
     * you have just replaced — so a restore is undoable for exactly the same reason a paste is.
     */
    RESTORE("restore", "Before restoring an earlier version");

    companion object {
        fun fromKey(key: String?): RevisionReason = entries.firstOrNull { it.key == key } ?: MANUAL
    }
}

/**
 * One kept version of a document: what it was, not what changed.
 *
 * Deliberately a snapshot rather than a diff. A diff chain is smaller and is the wrong shape here —
 * reading one back would mean replaying every edit since the beginning, and a single corrupt link
 * would cost every version after it. What this exists to protect against is losing writing, so each
 * version stands on its own.
 */
data class DocRevision(
    val id: String,
    val docId: String,
    val reason: RevisionReason,
    /** Words of prose in the snapshot, so the list can say what going back would cost. */
    val wordCount: Int,
    val savedAt: Long
)

/**
 * The rules about which versions are worth keeping, and which are worth throwing away.
 *
 * All of it is pure: what a snapshot *is* lives in `data/`, but every decision about whether to take
 * one and what to drop afterwards is decided here and tested on the JVM.
 */
object Revisions {

    /**
     * How many versions of one document to keep.
     *
     * A cap rather than everything, because this is a writing app on a phone and the alternative is
     * a table that only ever grows. Twenty is chosen to comfortably outlast a working session: the
     * versions that matter are the ones from the last few things you tried, and a version from four
     * hundred edits ago is not one anybody goes back to by name.
     */
    const val KEEP = 20

    /**
     * Whether [blocks] are worth keeping a version of.
     *
     * A document with nothing written in it has nothing to lose, and a brand-new one starts with a
     * single empty paragraph — so without this, opening a document and immediately pasting into it
     * would file a version of nothing and put it at the top of the list.
     */
    fun worthKeeping(blocks: List<DocBlock>): Boolean =
        blocks.any { it.type == BlockType.DIVIDER || it.text.isNotBlank() }

    /**
     * Whether [blocks] say anything different from [previous].
     *
     * Compared by what a block *is* — its type, its text, its ticked state, in order — and never by
     * id, because ids are minted per snapshot and comparing them would call every version different
     * from every other. Used to stop a second identical version being filed when somebody restores
     * twice, or pastes back exactly what was already there.
     */
    fun differ(blocks: List<DocBlock>, previous: List<DocBlock>): Boolean {
        if (blocks.size != previous.size) return true
        return blocks.zip(previous).any { (a, b) ->
            a.type != b.type || a.text != b.text || a.checked != b.checked
        }
    }

    /**
     * The versions to delete so that at most [keep] remain, newest kept.
     *
     * Returns ids rather than doing anything, so the caller decides when in its transaction they
     * go. Ordering is by [DocRevision.savedAt] and then by id, so two versions filed in the same
     * millisecond still have a defined order and pruning is deterministic rather than dependent on
     * whatever order the database handed them back.
     */
    fun prunable(revisions: List<DocRevision>, keep: Int = KEEP): List<String> =
        revisions
            .sortedWith(compareByDescending<DocRevision> { it.savedAt }.thenByDescending { it.id })
            .drop(keep.coerceAtLeast(0))
            .map { it.id }
}
