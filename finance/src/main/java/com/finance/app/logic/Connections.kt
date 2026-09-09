package com.finance.app.logic

/**
 * Where an account's numbers come from, and what that source can and cannot tell you.
 *
 * The two providers are not interchangeable and the app is better for saying so out loud rather than
 * flattening them into "a bank". The difference shows up on screen — a Mercury bill is always
 * predicted, because Mercury has no statement to read a due date off — and pretending otherwise
 * would mean either hiding a real limitation or inventing a due date.
 */
enum class Provider(
    val key: String,
    val label: String,
    /** Whether this provider reports statement due dates ([Bills.Source.STATEMENT]). */
    val hasStatements: Boolean,
    /** Whether a connection here can fall out of authorisation and need signing in again. */
    val canExpire: Boolean
) {
    /**
     * Plaid — the aggregator, and the only way USAA and most retail banks are reachable at all.
     *
     * It brings statement due dates with it, which is the single most valuable thing in this app,
     * and it brings re-authentication: a bank can decide at any time that the person should sign in
     * again, and until they do the data stops updating. Neither of those is optional; they come
     * together.
     */
    PLAID("plaid", "Plaid", hasStatements = true, canExpire = true),

    /**
     * Mercury — read directly, with a token Mercury issued to its own customer.
     *
     * Nothing in the middle, nothing that expires, and read-only enforced by the token rather than
     * by which products were asked for. What it does not have is liabilities, because it is a
     * business bank with deposit accounts: there is no statement, so every Mercury bill is predicted.
     */
    MERCURY("mercury", "Mercury", hasStatements = false, canExpire = false);

    companion object {
        fun fromKey(key: String?): Provider =
            entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: PLAID
    }
}

/**
 * One institution this household connected, as the app holds it.
 *
 * There is no token on this class, deliberately and permanently. A [Connection] is the part that is
 * safe to write down, show, log and back up; the part that is not lives behind the Keystore and is
 * fetched by [id] when a request is about to be made. Keeping them in separate types is what makes
 * "the backup cannot leak a token" a property of the code rather than a rule to remember.
 */
data class Connection(
    val id: String,
    val provider: Provider,
    val displayName: String,
    val institutionId: String? = null,
    val itemId: String? = null,
    val addedAt: Long = 0L,
    val lastSyncedAt: Long? = null,
    /**
     * The bank wants the person to sign in again.
     *
     * A state, not an error: it happens every few months on a healthy connection, the data already
     * held stays perfectly good, and the only thing that stops is updating. Presenting it as a
     * failure teaches people to ignore failures.
     */
    val needsReauth: Boolean = false,
    val lastError: String? = null
) {
    /** What the Connections screen leads with for this row. */
    fun status(): Status = when {
        needsReauth -> Status.NEEDS_SIGN_IN
        lastError != null -> Status.PROBLEM
        lastSyncedAt == null -> Status.NEVER_REFRESHED
        else -> Status.OK
    }

    enum class Status { OK, NEEDS_SIGN_IN, PROBLEM, NEVER_REFRESHED }
}
