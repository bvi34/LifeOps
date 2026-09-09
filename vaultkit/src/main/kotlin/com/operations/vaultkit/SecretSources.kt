package com.operations.vaultkit

import com.operations.backupkit.AppId

/**
 * An app that can hand the vault everything it holds, on demand.
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

    /** Which app this is, for the report the reset screen shows. */
    val owner: AppId

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
 * The registry of apps that can refill the vault, and the one call that asks all of them.
 *
 * Same shape and the same reasoning as the backup contributors in `:backupkit`: the sandbox knows
 * which apps exist, each app knows what it holds, and the thing in the middle knows neither. An app
 * registers once at start-up ([register]) and is asked only when a vault has to be rebuilt.
 */
object SecretSources {

    private val sources = LinkedHashMap<AppId, SecretSource>()

    /** Register (or replace) the source for one app. Called from that app's `install`. */
    fun register(source: SecretSource) {
        synchronized(sources) { sources[source.owner] = source }
    }

    /** Which apps could refill the vault, in registration order. */
    val owners: List<AppId> get() = synchronized(sources) { sources.keys.toList() }

    /**
     * What a refill did, per app.
     *
     * Kept per app rather than as a total because the total is the least useful number on the
     * screen: "Finance 3, Citation 2" tells somebody their bank connections came back, and "0
     * everywhere" tells them the thing they were afraid of is in fact true.
     */
    data class Refill(val byApp: Map<AppId, Int>) {

        val filed: Int get() = byApp.values.sum()

        /** The apps that had something to give. */
        val contributed: List<AppId> get() = byApp.filterValues { it > 0 }.keys.toList()

        val empty: Boolean get() = filed == 0

        /** "Finance 3, Citation 2" — what the reset screen prints. */
        fun summary(): String = byApp.filterValues { it > 0 }
            .entries
            .joinToString(", ") { (app, count) -> "${app.defaultDisplayName} $count" }
    }

    /**
     * Ask every registered app to file what it holds.
     *
     * One app throwing does not stop the others: this runs after somebody has just lost their vault,
     * and the worst possible behaviour would be to abandon eight apps' credentials because the ninth
     * has a bug. A source that fails counts as zero and the rest carry on.
     */
    suspend fun refileAll(): Refill {
        val snapshot = synchronized(sources) { sources.values.toList() }
        val counts = LinkedHashMap<AppId, Int>()
        for (source in snapshot) {
            counts[source.owner] = runCatching { source.refile() }.getOrDefault(0)
        }
        return Refill(counts)
    }

    /** Test seam: forget every registered source. */
    fun reset() {
        synchronized(sources) { sources.clear() }
    }
}
