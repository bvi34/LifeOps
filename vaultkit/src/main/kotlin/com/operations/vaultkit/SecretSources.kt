package com.operations.vaultkit

/**
 * An app that can hand the vault everything it holds — and take back everything it is missing.
 *
 * Two directions, one interface, because they are the two halves of the same seam and the same apps
 * implement both. [refile] is for the day the *vault* came up empty; [rehydrate] is for the day the
 * *apps* did, which is every restore onto a new phone.
 *
 * Mirroring normally happens one credential at a time, as it is written ([ManagedSecrets.remember]).
 * That covers every ordinary day and misses exactly one: **the vault is gone and has to be built
 * again.** A forgotten passphrase, a corrupt file with no readable generation behind it, a household
 * that deleted the vault and changed their mind. In every one of those cases the credentials
 * themselves are still sitting in each app's own encrypted store on this phone — they were never in
 * the vault's custody, only copied into it — so the right answer is not "reconnect nine things", it
 * is "ask each app to file what it has again".
 *
 * That is what [refile] is. It is the *reverse* of the read-through: instead of the vault answering
 * an app that lost its copy, each app answers a vault that lost its copy.
 *
 * ## The other direction
 *
 * [rehydrate] is the case the vault was actually built for: a restore. The archive carried the
 * sealed vault and every app's data, and carried no app's credentials — they were device-bound and
 * are gone with the old phone. So on every unlock each app is asked to take back what the vault is
 * holding for it, rather than waiting to be asked one credential at a time by whatever happens to
 * run next.
 *
 * ## What it cannot bring back
 *
 * Anything a person typed into Secrets. The logins, the notes, the card numbers, the wifi password
 * — the vault was the only place those existed, and it is gone. Refilling restores the *managed*
 * items and nothing else, and the screen that offers it says so in those words before it does
 * anything.
 *
 * It also brings back nothing at all on a phone whose apps are themselves empty — a fresh install, a
 * restore where the vault was the thing that failed. There is nothing to refill from, and the app
 * says that too rather than reporting a success of zero.
 */
interface SecretSource {

    /** Who this is, for the report the reset screen shows. Usually an app; once, the shell. */
    val owner: SecretOwner

    /**
     * Take back from the vault everything this app is missing, and return how many refs it restored.
     *
     * The mirror image of [refile], for the mirror-image disaster: the *apps* are the ones that came
     * up empty, because this is a new phone and their credential stores are device-bound and did not
     * travel. The vault did travel, inside the archive, and the moment somebody unlocks it every app
     * is asked this question.
     *
     * Without it the credentials still come back, but only one at a time and only when something
     * happens to ask while the vault is open — so the first background sync of the morning, running
     * against a shut vault, reports a connection that looks as though it was never set up. Pushing
     * on unlock turns "eventually, if you open the right screen" into "as soon as you type your
     * passphrase".
     *
     * Two rules, both of which [ManagedSecrets.restock] enforces for an implementation that uses it:
     *
     *  - **Never overwrite.** A local value is the working copy and is at least as new as the
     *    vault's. Only an *empty* local slot is filled.
     *  - **Enumerate from the app's own data, not from its credential store.** After a restore the
     *    credential store is exactly what is empty; the connections, catalogues and accounts are in
     *    the database that *did* travel, and they are what says which refs to ask for.
     *
     * Called with the vault open, on a background thread, and it must be cheap enough to run on
     * every unlock — which is the other reason it is idempotent: the second unlock of the day should
     * find nothing to do and say so.
     */
    suspend fun rehydrate(): Int

    /**
     * File everything this app currently holds into the vault, and return how many refs it wrote.
     *
     * Suspending because the honest implementations need a database read: an app knows its
     * credentials by connection id and knows the *name* of that connection only by looking it up,
     * and a refiled item called `7f3a-…` would be a worse vault than one called "USAA".
     *
     * Called with the vault open. Implementations do not need to check — a write that cannot land is
     * queued by [SecretsAccess] like any other — but they should be cheap and idempotent, because
     * nothing stops this being run twice.
     */
    suspend fun refile(): Int
}

/**
 * The registry of apps that can refill the vault — and be refilled *by* it — and the two calls that
 * ask all of them.
 *
 * Same shape and the same reasoning as the backup contributors in `:backupkit`: the sandbox knows
 * which apps exist, each app knows what it holds, and the thing in the middle knows neither. An app
 * registers once at start-up ([register]) and is asked only when a vault has to be rebuilt.
 */
object SecretSources {

    private val sources = LinkedHashMap<SecretOwner, SecretSource>()

    /** Register (or replace) the source for one app. Called from that app's `install`. */
    fun register(source: SecretSource) {
        synchronized(sources) { sources[source.owner] = source }
    }

    /** Who could refill the vault, in registration order. */
    val owners: List<SecretOwner> get() = synchronized(sources) { sources.keys.toList() }

    /**
     * What a round did, per app — used for both directions, because both are the same shape: a
     * count of refs per owner.
     *
     * Kept per app rather than as a total because the total is the least useful number on the
     * screen: "Finance 3, Citation 2" tells somebody their bank connections came back, and "0
     * everywhere" tells them the thing they were afraid of is in fact true.
     */
    data class Refill(val byOwner: Map<SecretOwner, Int>) {

        val filed: Int get() = byOwner.values.sum()

        /** The apps that had something to give. */
        val contributed: List<SecretOwner> get() = byOwner.filterValues { it > 0 }.keys.toList()

        val empty: Boolean get() = filed == 0

        /** "Finance 3, Citation 2" — what the reset screen prints. */
        fun summary(): String = byOwner.filterValues { it > 0 }
            .entries
            .joinToString(", ") { (owner, count) -> "${owner.displayName} $count" }
    }

    /**
     * Ask every registered app to file what it holds.
     *
     * One app throwing does not stop the others: this runs after somebody has just lost their vault,
     * and the worst possible behaviour would be to abandon eight apps' credentials because the ninth
     * has a bug. A source that fails counts as zero and the rest carry on.
     */
    suspend fun refileAll(): Refill = ask { it.refile() }

    /**
     * Ask every registered app to take back what the vault is holding for it.
     *
     * Called on every unlock (see `VaultStore`), which is why it must be cheap when there is nothing
     * to do: an app whose store is already full answers 0 without touching the vault at all.
     *
     * The same failure isolation as [refileAll], for a sharper reason: this runs on the first unlock
     * after a restore, and one app throwing must not be able to leave the other eight without their
     * credentials.
     */
    suspend fun rehydrateAll(): Refill = ask { it.rehydrate() }

    /**
     * One round over every registered source.
     *
     * A source that throws counts as zero and the rest carry on: both callers run at a moment when
     * somebody has just lost something, and abandoning eight apps' credentials because the ninth has
     * a bug would be the worst possible behaviour.
     */
    private suspend fun ask(round: suspend (SecretSource) -> Int): Refill {
        val snapshot = synchronized(sources) { sources.values.toList() }
        val counts = LinkedHashMap<SecretOwner, Int>()
        for (source in snapshot) {
            counts[source.owner] = runCatching { round(source) }.getOrDefault(0)
        }
        return Refill(counts)
    }

    /** Test seam: forget every registered source. */
    fun reset() {
        synchronized(sources) { sources.clear() }
    }
}
