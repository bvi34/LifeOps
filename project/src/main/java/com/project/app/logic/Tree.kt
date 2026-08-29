package com.project.app.logic

/** One node of a flattened tree: the item, how deep it sits, and where it sits among its siblings. */
data class TreeRow<T>(
    val item: T,
    val depth: Int,
    /** 1-based position at each level. `[2, 3, 1]` reads as "2.3.1". */
    val path: List<Int>,
    val hasChildren: Boolean
) {
    /** "2.3.1" — where the row sits, for reading aloud. Never stored. */
    val number: String get() = path.joinToString(".")
}

/**
 * Walking a parent-pointer tree, once, for everything in this app that has one.
 *
 * Two trees are stored the same way here — the outline's nodes and the docs' folders — and both need
 * the same two guarantees, which are the reason this is a shared file rather than two similar loops:
 *
 * **Orphans are shown, not dropped.** A node whose parent has gone is drawn as a root. The tempting
 * alternative — walk from the real roots and emit whatever you reach — quietly hides rows that still
 * exist in the database, and a document you cannot see is a document you cannot rescue.
 *
 * **A cycle cannot hang the screen.** Parent pointers are edited by a UI and restored from backups,
 * so a loop is possible however carefully the move operations behave. Every walk here emits each
 * node at most once, so the worst a cycle can do is leave part of the tree unreachable rather than
 * spin.
 */
object Tree {

    /**
     * The parent an item is actually drawn under: null when it is a root, when its parent is missing
     * from [known], or when it claims itself as its own parent.
     */
    fun <T> parentKeyOf(
        item: T,
        id: (T) -> String,
        parentId: (T) -> String?,
        known: Set<String>
    ): String? = parentId(item)?.takeIf { it != id(item) && it in known }

    /** Children by parent key (null for the roots), each run in the order it is drawn. */
    fun <T> childMap(
        items: List<T>,
        id: (T) -> String,
        parentId: (T) -> String?,
        comparator: Comparator<T>
    ): Map<String?, List<T>> {
        val known = items.mapTo(HashSet()) { id(it) }
        return items
            .groupBy { parentKeyOf(it, id, parentId, known) }
            .mapValues { (_, siblings) -> siblings.sortedWith(comparator) }
    }

    /** Depth-first, in sibling order. */
    fun <T> flatten(
        items: List<T>,
        id: (T) -> String,
        parentId: (T) -> String?,
        comparator: Comparator<T>
    ): List<TreeRow<T>> {
        if (items.isEmpty()) return emptyList()
        val children = childMap(items, id, parentId, comparator)
        val rows = ArrayList<TreeRow<T>>(items.size)
        val emitted = HashSet<String>(items.size)

        fun walk(parent: String?, depth: Int, prefix: List<Int>) {
            children[parent].orEmpty().forEachIndexed { index, item ->
                val key = id(item)
                if (!emitted.add(key)) return@forEachIndexed
                val path = prefix + (index + 1)
                rows += TreeRow(
                    item = item,
                    depth = depth,
                    path = path,
                    hasChildren = children[key].orEmpty().isNotEmpty()
                )
                walk(key, depth + 1, path)
            }
        }

        walk(null, 0, emptyList())
        return rows
    }

    /** The ids of [rootId] and everything beneath it — what a delete would take with it. */
    fun <T> subtree(
        items: List<T>,
        id: (T) -> String,
        parentId: (T) -> String?,
        rootId: String
    ): List<String> {
        val children = items.groupBy { parentId(it) }
        val out = ArrayList<String>()
        val seen = HashSet<String>()
        fun walk(current: String) {
            if (!seen.add(current)) return
            out += current
            children[current].orEmpty().forEach { walk(id(it)) }
        }
        walk(rootId)
        return out
    }

    /**
     * Whether [itemId] may be re-parented under [newParentId].
     *
     * False when the new parent is the item itself or one of its own descendants — the move that
     * detaches a whole branch from the tree and leaves it reachable only by [flatten]'s
     * orphan handling. Cheaper to refuse in the picker than to repair afterwards.
     */
    fun <T> canReparent(
        items: List<T>,
        id: (T) -> String,
        parentId: (T) -> String?,
        itemId: String,
        newParentId: String?
    ): Boolean {
        if (newParentId == null) return true
        if (newParentId == itemId) return false
        return newParentId !in subtree(items, id, parentId, itemId)
    }
}
