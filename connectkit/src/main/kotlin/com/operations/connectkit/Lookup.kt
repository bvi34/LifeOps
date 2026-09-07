package com.operations.connectkit

/**
 * Finding the thing a caller named.
 *
 * A screen never needs this: it hands back the id of the row somebody tapped. A **route** does,
 * because the caller on the other end is a sentence — "add a card to the Kestrel board", "send me
 * the Wrangler's warranty" — and a sentence carries a name, not a UUID. Turning that name into a row
 * is a decision with a wrong answer, so it lives here, once, and is tested rather than improvised
 * inside each route handler.
 *
 * The rule is the one Project's lore already used for `[[double brackets]]`: **an ambiguous name
 * resolves to nothing, not to whichever row came first.** Two projects called "Draft", or two
 * documents called "Statement", and a route that picked one of them would write into — or hand
 * back — the wrong one silently. Silently is the whole problem: a caller told "which one?" can ask
 * again; a caller told nothing never finds out.
 *
 * It is in the kit rather than in an app because every app that serves routes has the same question
 * to answer first, and three copies of a resolution rule is three chances for one of them to start
 * guessing.
 */
object NameLookup {

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
     * Kestrel" is a guess, and a route that guesses acts on the wrong row the first time two of them
     * start with the same letters.
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
}

/**
 * A resolved reference, or the failure to hand back instead.
 *
 * Route handlers are meant to be thin, and "work out which one they meant, and return the right kind
 * of failure if you cannot" is the fattest thing they would otherwise each do. This is that step
 * with one shape, so a handler reads as `when (val found = ...) { Ok -> do the work; Problem ->
 * found.failure }`.
 */
sealed interface Resolution<out T> {
    data class Ok<T>(val value: T) : Resolution<T>
    data class Problem(val failure: ConnectionResult.Failure) : Resolution<Nothing>
}

/**
 * The three answers a name can have, in the three words the address scheme already has for them.
 *
 * [noun] is what the thing is called in a sentence — "project", "document", "column" — so the
 * message reads as an answer to what was asked rather than as a class name. Ambiguity is
 * `INVALID_PARAMS` rather than `NOT_FOUND` because the thing *was* found, more than once: the caller
 * has to say something different, and the message names the candidates so that they can.
 */
fun <T> NameLookup.Match<T>.orProblem(noun: String, reference: String): Resolution<T> = when (this) {
    is NameLookup.Match.Found -> Resolution.Ok(value)

    is NameLookup.Match.None -> Resolution.Problem(
        ConnectionResult.fail(ConnectionError.NOT_FOUND, "No $noun called '$reference'")
    )

    is NameLookup.Match.Ambiguous -> Resolution.Problem(
        ConnectionResult.fail(
            ConnectionError.INVALID_PARAMS,
            "More than one $noun is called '$reference' (${names.joinToString(", ")}). Use its id."
        )
    )
}
