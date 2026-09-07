package com.project.app.logic

/**
 * What is left of Project's own lookup once the shareable half moved out.
 *
 * Resolving a *name* to a row — id first, then an exact name, and an ambiguous name resolving to
 * neither — is `com.operations.connectkit.NameLookup` now, because every app that serves routes asks
 * the same question first and three copies of that rule is three chances for one of them to start
 * guessing. What stays here is the one decision that is about a board rather than about names.
 */
object ProjectLookup {

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
