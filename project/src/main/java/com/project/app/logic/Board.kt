package com.project.app.logic

/** One column of the implementation board. */
data class BoardColumn(
    val id: String,
    val name: String,
    val sortOrder: Int,
    /**
     * The most cards that should sit here at once, or null for no limit.
     *
     * A limit is a warning, never a refusal. A board that refuses to accept the card in your hand
     * teaches you to lie to it — you park the work somewhere it doesn't belong and the board stops
     * describing reality, which was the only thing it was for.
     */
    val wipLimit: Int?,
    /** The terminal column. Cards here are finished, and a limit on it would be meaningless. */
    val isDone: Boolean
)

/** One card on the board. */
data class BoardCard(
    val id: String,
    val columnId: String,
    val title: String,
    val notes: String?,
    val sortOrder: Int,
    /** The piece of the outline this card is the work for, when it is work on one. */
    val outlineNodeId: String? = null,
    /** The document this card is about, when it is about one. */
    val docId: String? = null,
    /**
     * The day this is due, as an epoch day — or null, which is most cards.
     *
     * A day rather than an instant, because a deadline is a date on a calendar and not a moment;
     * and a fact about the work rather than a plan for your time, which is the distinction that
     * keeps this from being a second planner. See `logic/Due`.
     */
    val dueOn: Long? = null,
    val createdAt: Long = 0L,
    val doneAt: Long? = null
)

/** A column with its cards, ready to draw. */
data class BoardLane(val column: BoardColumn, val cards: List<BoardCard>) {
    val count: Int get() = cards.size

    /** Over its own limit. The done column is never over: finishing things is not a problem. */
    val isOverLimit: Boolean
        get() = !column.isDone && column.wipLimit?.let { cards.size > it } == true

    /** At the limit exactly — worth colouring differently from over it. */
    val isAtLimit: Boolean
        get() = !column.isDone && column.wipLimit?.let { cards.size == it } == true
}

/** What the board says about the project as a whole. */
data class BoardStats(
    val total: Int,
    val done: Int,
    val inFlight: Int,
    val overLimitColumns: List<String>
) {
    val progress: Float? get() = if (total == 0) null else done.toFloat() / total
}

/**
 * The kanban half of the app: lanes, limits, and moving a card without corrupting the order of the
 * two columns it touches.
 *
 * The only genuinely tricky operation is [move], and it is tricky for an unglamorous reason: a
 * card's position is a per-column integer, so moving one card changes the stored order of every
 * card below it in the source column *and* every card below its new position in the destination.
 * Doing that in the ViewModel means doing it again, slightly differently, the next time a drag
 * gesture or a menu item needs it. Doing it here means it is done once and tested.
 */
object Board {

    /** The columns a new project starts with — the vocabulary follows the kind of project it is. */
    fun defaultColumns(kind: ProjectKind): List<Pair<String, Boolean>> = when (kind) {
        ProjectKind.WRITING -> listOf("Ideas" to false, "Drafting" to false, "Revising" to false, "Done" to true)
        ProjectKind.SOFTWARE -> listOf("Backlog" to false, "In progress" to false, "Review" to false, "Shipped" to true)
        ProjectKind.RESEARCH -> listOf("Questions" to false, "Reading" to false, "Writing up" to false, "Settled" to true)
        ProjectKind.GENERAL -> listOf("To do" to false, "Doing" to false, "Done" to true)
    }

    /** Lanes in column order, each holding its cards in card order. */
    fun lanes(columns: List<BoardColumn>, cards: List<BoardCard>): List<BoardLane> {
        val byColumn = cards.groupBy { it.columnId }
        return columns
            .sortedWith(compareBy({ it.sortOrder }, { it.name.lowercase() }))
            .map { column ->
                BoardLane(
                    column = column,
                    cards = byColumn[column.id].orEmpty()
                        .sortedWith(compareBy({ it.sortOrder }, { it.title.lowercase() }))
                )
            }
    }

    /**
     * Cards that reference a column that no longer exists.
     *
     * They are not shown by [lanes] — there is no lane to show them in — so they have to be
     * findable some other way, or deleting a column silently swallows the work that was in it. The
     * board screen offers to move them back into the first column.
     */
    fun orphans(columns: List<BoardColumn>, cards: List<BoardCard>): List<BoardCard> {
        val known = columns.map { it.id }.toSet()
        return cards.filter { it.columnId !in known }
    }

    /**
     * Move [cardId] into [toColumnId] at [toIndex], and return every card whose stored position
     * changed — including the moved one.
     *
     * [toIndex] is clamped rather than validated: a drop below the last card, or onto an empty
     * lane, both mean "put it at the end", and neither is a programming error worth an exception.
     * Moving a card into the column it is already in is a reorder, handled by the same path.
     */
    fun move(
        columns: List<BoardColumn>,
        cards: List<BoardCard>,
        cardId: String,
        toColumnId: String,
        toIndex: Int,
        now: Long = 0L
    ): List<BoardCard> {
        val card = cards.firstOrNull { it.id == cardId } ?: return emptyList()
        val destination = columns.firstOrNull { it.id == toColumnId } ?: return emptyList()

        val sourceRun = lanes(columns, cards).firstOrNull { it.column.id == card.columnId }?.cards.orEmpty()
        val destinationRun = lanes(columns, cards).firstOrNull { it.column.id == toColumnId }?.cards.orEmpty()

        val changed = LinkedHashMap<String, BoardCard>()

        // Landing in (or leaving) the done column is what stamps doneAt. It is stored rather than
        // inferred from the column so a card keeps the day it was finished even if the board is
        // later reorganised around it.
        val movedBase = card.copy(
            columnId = toColumnId,
            doneAt = when {
                destination.isDone -> card.doneAt ?: now
                else -> null
            }
        )

        if (card.columnId == toColumnId) {
            val run = destinationRun.toMutableList()
            val from = run.indexOfFirst { it.id == cardId }
            if (from < 0) return emptyList()
            run.removeAt(from)
            run.add(toIndex.coerceIn(0, run.size), movedBase)
            run.forEachIndexed { position, item ->
                val original = cards.first { it.id == item.id }
                val updated = item.copy(sortOrder = position)
                if (updated != original) changed[updated.id] = updated
            }
            return changed.values.toList()
        }

        // Close the gap the card left behind.
        sourceRun.filter { it.id != cardId }.forEachIndexed { position, item ->
            if (item.sortOrder != position) changed[item.id] = item.copy(sortOrder = position)
        }

        val run = destinationRun.toMutableList()
        run.add(toIndex.coerceIn(0, run.size), movedBase)
        run.forEachIndexed { position, item ->
            val original = cards.firstOrNull { it.id == item.id }
            val updated = if (item.id == cardId) movedBase.copy(sortOrder = position) else item.copy(sortOrder = position)
            if (updated != original) changed[updated.id] = updated
        }

        return changed.values.toList()
    }

    /** The next free position in a column — where a newly added card lands. */
    fun nextSortOrder(cards: List<BoardCard>, columnId: String): Int =
        (cards.filter { it.columnId == columnId }.maxOfOrNull { it.sortOrder } ?: -1) + 1

    /** Headline numbers for the board, and the names of any columns carrying too much at once. */
    fun stats(lanes: List<BoardLane>): BoardStats {
        val total = lanes.sumOf { it.count }
        val done = lanes.filter { it.column.isDone }.sumOf { it.count }
        return BoardStats(
            total = total,
            done = done,
            inFlight = total - done,
            overLimitColumns = lanes.filter { it.isOverLimit }.map { it.column.name }
        )
    }
}
