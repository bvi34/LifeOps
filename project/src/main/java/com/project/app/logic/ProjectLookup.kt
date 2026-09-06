package com.project.app.logic

/**
 * Finding the thing a caller named.
 *
 * A screen never needs this: it hands back the id of the row somebody tapped. A **connection route**
 * does, because the caller on the other end is Advisor relaying a sentence — "add a card to the
 * Kestrel board" carries a name, not a UUID. Turning that name into a row is a decision with a wrong
 * answer, so it lives here and is tested rather than being improvised inside a route handler.
 *
 * The rule is the same one Lore already uses for `[[double brackets]]`: **an ambiguous name resolves
 * to nothing, not to whichever row came first.** Two projects called "Draft" and a route that picked
 * one of them would write into the wrong one silently, and silently is the whole problem — a caller
 * told "which one?" can ask; a caller told nothing cannot.
 */
object ProjectLookup {

    /** What a name turned out to mean. */
    sealed interface Match<out T> {
        data class Found<T>(val value: T) : Match<T>

        /** Nothing of that name. */
        data object None : Match<Nothing>

        /** More than one, and no way to tell which was meant. [names] is what they are called. */
        data class Ambiguous(val names: List<String>) : Match<Nothing>
    }

    /**
     * Resolve [reference] against [candidates] by id first, then by name.
     *
     * Id first because an id is unambiguous by construction and a caller that has one means it. The
     * name comparison is case- and space-insensitive, which is how somebody types a name they read
     * on a screen; it is otherwise **exact**, with no prefix or fuzzy matching — "Kes" finding "The
     * Kestrel" is a guess, and a route that guesses writes into the wrong project the first time two
     * of them start with the same letters.
     */
    fun <T> resolve(
        reference: String,
        candidates: List<T>,
        idOf: (T) -> String,
        nameOf: (T) -> String
    ): Match<T> {
        val wanted = reference.trim()
        if (wanted.isEmpty()) return Match.None

        candidates.firstOrNull { idOf(it) == wanted }?.let { return Match.Found(it) }

        val byName = candidates.filter { nameOf(it).trim().equals(wanted, ignoreCase = true) }
        return when {
            byName.isEmpty() -> Match.None
            byName.size == 1 -> Match.Found(byName.single())
            else -> Match.Ambiguous(byName.map { nameOf(it) })
        }
    }

    /**
     * The column a card should land in when the caller did not name one.
     *
     * The board's first column that is not the finished one — which is where work starts on every
     * default board this app makes ("Ideas", "Backlog", "Questions", "To do"). Falling back to the
     * finished column would file new work as already done, so a board that somehow has only
     * finished columns gets nothing rather than that.
     */
    fun <T> defaultColumn(columns: List<T>, isDone: (T) -> Boolean): T? =
        columns.firstOrNull { !isDone(it) }
}
