package com.project.app.logic

/**
 * How far one piece of the outline has got.
 *
 * The ladder is deliberately about *drafting*, not about doing: an outline node is a thing that
 * gets written and then rewritten, and "in progress / done" cannot tell a first draft from a
 * revised one. [CUT] is the important entry — cut material stays in the tree, keeps its words, and
 * stops counting towards anything. Deleting it instead is how people lose the scene they cut in
 * August and wanted back in October.
 */
enum class OutlineStatus(val key: String, val label: String) {
    IDEA("idea", "Idea"),
    OUTLINED("outlined", "Outlined"),
    DRAFTING("drafting", "Drafting"),
    DRAFTED("drafted", "Drafted"),
    REVISED("revised", "Revised"),
    DONE("done", "Done"),
    CUT("cut", "Cut");

    /** Cut material is still in the tree, but it is not part of the work any more. */
    val counts: Boolean get() = this != CUT

    /** Whether this piece is finished, for the "12 of 40" count. */
    val isComplete: Boolean get() = this == DONE || this == REVISED

    companion object {
        fun fromKey(key: String?): OutlineStatus = entries.firstOrNull { it.key == key } ?: IDEA
    }
}

/** One node of the outline tree, framework-free. */
data class OutlineNode(
    val id: String,
    val parentId: String?,
    val title: String,
    val synopsis: String?,
    val status: OutlineStatus,
    /** 0 means "no target set", which is different from a target of zero words. */
    val targetWords: Int,
    val actualWords: Int,
    val sortOrder: Int
)

/** Words and pieces under a node, its descendants included. */
data class OutlineTotals(
    val targetWords: Int,
    val actualWords: Int,
    val pieces: Int,
    val piecesComplete: Int,
    val cut: Int
) {
    operator fun plus(other: OutlineTotals) = OutlineTotals(
        targetWords = targetWords + other.targetWords,
        actualWords = actualWords + other.actualWords,
        pieces = pieces + other.pieces,
        piecesComplete = piecesComplete + other.piecesComplete,
        cut = cut + other.cut
    )

    /**
     * How far along, 0..1 — by words when a target was set, by finished pieces otherwise.
     *
     * Null rather than zero when there is nothing to measure against: a project with no targets and
     * no pieces has no progress, and a 0% bar is a claim that it is behind rather than unmeasured.
     */
    val progress: Float?
        get() = when {
            targetWords > 0 -> (actualWords.toFloat() / targetWords).coerceIn(0f, 1f)
            pieces > 0 -> piecesComplete.toFloat() / pieces
            else -> null
        }

    /** Words still owed against the target, never negative — overshooting is not a debt. */
    val wordsRemaining: Int get() = (targetWords - actualWords).coerceAtLeast(0)

    companion object {
        val EMPTY = OutlineTotals(0, 0, 0, 0, 0)
    }
}

/** One node as a flat, indented row — what the outline screen actually draws. */
data class OutlineRow(
    val node: OutlineNode,
    val depth: Int,
    /** "2.3.1" — where the row sits, for reading aloud. Not an id, and never stored. */
    val number: String,
    val hasChildren: Boolean,
    /** This node plus everything under it. For a leaf, that is just itself. */
    val totals: OutlineTotals
)

/**
 * The outline tree: flattening it, rolling it up, and the four structural edits (up, down, indent,
 * outdent) that are the whole of how an outline gets rearranged.
 *
 * All of it is pure. The tree lives in Room as a parent pointer plus a sort order — the storage
 * shape that survives an arbitrary reshuffle without renumbering the world — and every question a
 * screen asks of it ("how deep is this", "what is 2.3.1", "how many words are under Act II") is
 * answered here, on the JVM, where it can be tested.
 *
 * The walk itself — including the two guarantees that matter, that orphans are drawn rather than
 * hidden and that a cycle cannot spin — belongs to [Tree], which the docs' folder tree uses too.
 * What is specific to an outline, and therefore lives here, is the *rollup*: words and finished
 * pieces gathered from the leaves up.
 */
object Outline {

    /** Siblings are drawn in stored order, ties broken by title so the list never jitters. */
    private val ORDER: Comparator<OutlineNode> =
        compareBy({ it.sortOrder }, { it.title.lowercase() })

    private fun idOf(node: OutlineNode) = node.id
    private fun parentOf(node: OutlineNode) = node.parentId

    /** Depth-first, in sort order: the tree as a list of rows with their rollups. */
    fun flatten(nodes: List<OutlineNode>): List<OutlineRow> {
        if (nodes.isEmpty()) return emptyList()
        val rollups = rollups(nodes)
        return Tree.flatten(nodes, ::idOf, ::parentOf, ORDER).map { row ->
            OutlineRow(
                node = row.item,
                depth = row.depth,
                number = row.number,
                hasChildren = row.hasChildren,
                totals = rollups[row.item.id] ?: OutlineTotals.EMPTY
            )
        }
    }

    /**
     * Totals for every node, its descendants folded in.
     *
     * A node's own words count only when it is a *leaf*: a chapter that holds scenes has no words
     * of its own, and adding a parent's stale `actualWords` to its children's would double-count
     * the manuscript. Cut material contributes to [OutlineTotals.cut] and to nothing else.
     */
    fun rollups(nodes: List<OutlineNode>): Map<String, OutlineTotals> {
        val children = Tree.childMap(nodes, ::idOf, ::parentOf, ORDER)
        val out = HashMap<String, OutlineTotals>(nodes.size)
        val visiting = HashSet<String>()

        fun totalsFor(node: OutlineNode): OutlineTotals {
            out[node.id]?.let { return it }
            // A cycle would otherwise recurse for ever; treat the repeat as contributing nothing.
            if (!visiting.add(node.id)) return OutlineTotals.EMPTY

            val kids = children[node.id].orEmpty()
            val own = when {
                !node.status.counts -> OutlineTotals(0, 0, 0, 0, 1)
                kids.isEmpty() -> OutlineTotals(
                    targetWords = node.targetWords,
                    actualWords = node.actualWords,
                    pieces = 1,
                    piecesComplete = if (node.status.isComplete) 1 else 0,
                    cut = 0
                )
                // A grouping node contributes its target (an act can be given a length) but not its
                // words — those belong to the scenes underneath it.
                else -> OutlineTotals(node.targetWords, 0, 0, 0, 0)
            }
            val total = kids.fold(own) { acc, kid -> acc + totalsFor(kid) }
            visiting.remove(node.id)
            out[node.id] = total
            return total
        }

        nodes.forEach { totalsFor(it) }
        return out
    }

    /** The whole project's totals: every root's rollup, added up. */
    fun projectTotals(nodes: List<OutlineNode>): OutlineTotals {
        val rollups = rollups(nodes)
        val roots = Tree.childMap(nodes, ::idOf, ::parentOf, ORDER)[null].orEmpty()
        return roots.fold(OutlineTotals.EMPTY) { acc, root -> acc + (rollups[root.id] ?: OutlineTotals.EMPTY) }
    }

    /** Ids of [id] and everything beneath it — what a delete actually takes with it. */
    fun subtree(nodes: List<OutlineNode>, id: String): List<String> =
        Tree.subtree(nodes, ::idOf, ::parentOf, id)

    /** The siblings of [node], in the order they are drawn. */
    private fun siblingsOf(nodes: List<OutlineNode>, node: OutlineNode): List<OutlineNode> {
        val known = nodes.mapTo(HashSet()) { it.id }
        val parent = Tree.parentKeyOf(node, ::idOf, ::parentOf, known)
        return Tree.childMap(nodes, ::idOf, ::parentOf, ORDER)[parent].orEmpty()
    }

    /**
     * Move [id] one place up (-1) or down (+1) among its siblings.
     *
     * Returns only the nodes whose stored fields changed, so the caller writes two rows rather than
     * the whole outline. An impossible move (already first, already last, unknown id) returns
     * nothing at all rather than throwing — the arrow was simply greyed out a frame late.
     */
    fun move(nodes: List<OutlineNode>, id: String, delta: Int): List<OutlineNode> {
        val node = nodes.firstOrNull { it.id == id } ?: return emptyList()
        val siblings = siblingsOf(nodes, node)
        val index = siblings.indexOfFirst { it.id == id }
        val target = index + delta
        if (index < 0 || target !in siblings.indices) return emptyList()

        // Rewrite the whole sibling run's sort order rather than swapping two numbers: siblings that
        // arrived from a restore can share a sort order, and swapping equal numbers moves nothing.
        val reordered = siblings.toMutableList()
        reordered.add(target, reordered.removeAt(index))
        return reordered.mapIndexedNotNull { position, sibling ->
            sibling.takeIf { it.sortOrder != position }?.copy(sortOrder = position)
        }
    }

    /**
     * Nest [id] under the sibling above it — the Tab key of every outliner ever written.
     *
     * The first child of a run has nothing to nest under, so indenting it is a no-op. That is the
     * behaviour people expect from a word processor's outline view, and the alternative (nesting it
     * under its parent's previous sibling) moves the row somewhere the user was not looking.
     */
    fun indent(nodes: List<OutlineNode>, id: String): List<OutlineNode> {
        val node = nodes.firstOrNull { it.id == id } ?: return emptyList()
        val siblings = siblingsOf(nodes, node)
        val index = siblings.indexOfFirst { it.id == id }
        if (index <= 0) return emptyList()
        val newParent = siblings[index - 1]
        val lastOrder = nodes.filter { it.parentId == newParent.id }.maxOfOrNull { it.sortOrder } ?: -1
        return listOf(node.copy(parentId = newParent.id, sortOrder = lastOrder + 1))
    }

    /**
     * Lift [id] out to sit directly after its former parent.
     *
     * Everything that followed the old parent shifts down by one, which is why this returns a list:
     * dropping a node into an already-taken sort order is how an outline ends up with two rows that
     * cannot be told apart by position.
     */
    fun outdent(nodes: List<OutlineNode>, id: String): List<OutlineNode> {
        val node = nodes.firstOrNull { it.id == id } ?: return emptyList()
        val parent = node.parentId?.let { parentId -> nodes.firstOrNull { it.id == parentId } } ?: return emptyList()

        val uncles = siblingsOf(nodes, parent).toMutableList()
        val parentIndex = uncles.indexOfFirst { it.id == parent.id }
        if (parentIndex < 0) return emptyList()

        val moved = node.copy(parentId = parent.parentId, sortOrder = parentIndex + 1)
        uncles.add(parentIndex + 1, moved)

        return uncles.mapIndexedNotNull { position, sibling ->
            when {
                sibling.id == moved.id -> moved.copy(sortOrder = position)
                sibling.sortOrder != position -> sibling.copy(sortOrder = position)
                else -> null
            }
        }
    }

    /** The next free sort order under [parentId] — where a newly added node lands. */
    fun nextSortOrder(nodes: List<OutlineNode>, parentId: String?): Int =
        (nodes.filter { it.parentId == parentId }.maxOfOrNull { it.sortOrder } ?: -1) + 1
}
