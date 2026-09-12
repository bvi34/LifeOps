package com.operations.vaultkit

import java.util.concurrent.CopyOnWriteArrayList

/**
 * What state the vault is in, from the point of view of an app that wants a secret out of it.
 *
 * The three cases are genuinely different and no caller should collapse them: a household with no
 * vault has not opted into any of this and must not be nagged; a locked vault is a "come back when
 * it is open"; an unlocked one is the only case where a read can succeed.
 */
enum class VaultState { ABSENT, LOCKED, UNLOCKED }

/**
 * The vault, as the rest of the suite sees it. Implemented once, in :secrets.
 *
 * Deliberately three calls and no listing. An app can read the secret it filed, write it, and
 * forget it; it cannot enumerate the vault, cannot see another app's secrets, and cannot ask whether
 * a ref it does not own exists. That is not a permission system — everything is in one process and a
 * determined module could reach further — it is the shape of the seam, so that reaching further is a
 * thing somebody has to *write* rather than a thing that happens by default.
 */
interface SecretsBroker {

    val state: VaultState

    /** The value filed at [ref], or null if the vault is locked, absent, or holds nothing there. */
    fun read(ref: SecretRef): String?

    /**
     * File [value] at [ref], creating the item if it is new.
     *
     * [label] is what a person will see in the Secrets list ("USAA — access token"), and [owner] is
     * who the item is shown as belonging to — a hosted app, or the container itself (see
     * [SecretOwner]). Returns false if the vault could not take it.
     */
    fun write(ref: SecretRef, value: String, label: String, owner: SecretOwner): Boolean

    /** Forget what is filed at [ref]. Returns false if the vault could not be written to. */
    fun forget(ref: SecretRef): Boolean
}

/**
 * The one place an app finds the vault: a process-wide holder that :secrets registers itself with.
 *
 * ## Why a holder and not a dependency
 *
 * Because the dependency arrow would be wrong in every direction. Finance cannot depend on :secrets
 * (a bank client that pulls in a password manager's UI), :secrets cannot depend on Finance (it would
 * have to depend on every app in the suite), and there is no container in the middle: the sandbox's `Application`
 * installs both, but a hosted app resolves what it needs when a screen asks, not when the process
 * starts. So the seam is the same shape as LifeOps' completion bus — a static registration and a
 * null check — and the honest name for it is a service locator.
 *
 * ## What happens while the vault is shut
 *
 * The interesting half. A read while locked returns null, which every caller already handles: it is
 * the same answer they get on a phone where nothing was ever connected.
 *
 * A *write* that cannot land — the vault is locked, or there is no vault yet — is the case that would
 * quietly break the promise this whole app exists for. Finance re-authorises a connection at eight in
 * the morning, the vault has not been opened since
 * the phone booted, the token lands only in Finance's own keystore store — and the next restore
 * loses it, exactly as before. So writes that cannot land are **queued in memory** and flushed the
 * next time the vault is unlocked ([flushPending]).
 *
 * That queue is memory-only, and deliberately: a pending write is a plaintext secret, and the one
 * place this suite will not put a plaintext secret is a file. Kill the process before the vault is
 * next opened and the queue is gone — the app's own store still has the token, so nothing is broken
 * that was not already, and the mirror happens on the next write. [PENDING_LIMIT] caps it, because
 * an unbounded in-memory pile of secrets is its own kind of bug.
 *
 * A household with no vault at all queues too, and that is on purpose rather than an oversight:
 * making a vault flushes the queue, so somebody who connects a bank in the morning and creates a
 * vault in the afternoon gets that connection filed without having to touch Finance again.
 *
 * ## Saying so
 *
 * The queue above is correct and, for a long time, invisible. A credential written while the vault
 * was shut sat in memory until somebody happened to open Secrets — and if the process died first it
 * was simply gone, silently, which is the exact outcome the queue exists to prevent. Nothing asked
 * anybody to unlock, because nothing outside Secrets could see that there was a reason to.
 *
 * So the queue is observable: [watch] takes a listener that is told the vault's state and how many
 * writes are waiting, every time either changes. The sandbox's home screen uses it to put a line
 * under the clock — the one place in the suite somebody is guaranteed to pass on the way to
 * anything else.
 *
 * The shape is LifeOps' completion bus, for the same reasons: facts rather than requests, a listener
 * cannot break a write, and registration lasts as long as the process. Listeners are called on
 * whichever thread moved the vault, so they must be cheap and must not block — the home screen's
 * hands its two numbers to Compose and returns.
 */
object SecretsAccess {

    /**
     * More than the suite could plausibly queue between a boot and an unlock — ten apps with a
     * handful of connections each — and small enough that a leak shows up as a refusal rather than
     * as memory.
     */
    const val PENDING_LIMIT = 128

    @Volatile
    private var broker: SecretsBroker? = null

    private val pending = LinkedHashMap<String, Pending>()

    /** One write (or forget) that could not land because the vault was not open. */
    private data class Pending(
        val ref: SecretRef,
        /** The value to file, or null for a queued forgetting. */
        val value: String?,
        val label: String,
        /** Null for a forgetting, which does not need to know who owned the item. */
        val owner: SecretOwner?
    )

    /** Called once by :secrets. Passing null (tests, or a teardown) leaves every app back where it was. */
    fun register(broker: SecretsBroker?) {
        this.broker = broker
        announceChanged()
    }

    /**
     * Told when the vault opens, shuts, appears, goes away, or when the number of writes waiting on
     * it changes.
     *
     * Deliberately carries the two facts and nothing else. A watcher is not handed the broker, the
     * refs that are queued, or their values — a listener list is exactly the wrong place to widen a
     * seam whose whole design is "no listing" — and "the vault is shut and three things are waiting"
     * is the entirety of what anything outside Secrets needs in order to say something useful.
     */
    fun interface VaultWatcher {
        fun onVaultChanged(state: VaultState, pending: Int)
    }

    private val watchers = CopyOnWriteArrayList<VaultWatcher>()

    /** Start listening. Idempotent; the caller gets the current state immediately. */
    fun watch(watcher: VaultWatcher) {
        if (watcher !in watchers) watchers += watcher
        runCatching { watcher.onVaultChanged(state, pendingCount) }
    }

    fun unwatch(watcher: VaultWatcher) {
        watchers -= watcher
    }

    /**
     * Tell every watcher where things stand.
     *
     * Called from here whenever the queue moves, and from :secrets' store whenever the vault's state
     * does — the store owns that transition and this object only reads it, so it cannot notice one
     * on its own.
     *
     * Each watcher is called inside `runCatching`, for the same reason the completion bus does it: a
     * home screen mid-recomposition must not be able to turn Finance saving a token into a crash in
     * Finance.
     */
    fun announceChanged() {
        if (watchers.isEmpty()) return
        val current = state
        val waiting = pendingCount
        watchers.forEach { watcher -> runCatching { watcher.onVaultChanged(current, waiting) } }
    }

    val state: VaultState get() = broker?.state ?: VaultState.ABSENT

    /** Is there a vault at all? What a settings screen asks before offering to mirror anything. */
    val available: Boolean get() = state != VaultState.ABSENT

    /**
     * Read [ref], consulting the pending queue first.
     *
     * The queue comes first because it is *newer*: a token written while the vault was locked is the
     * live one, and the vault — if it happens to have been unlocked since — may still hold the value
     * it replaced.
     */
    fun read(ref: SecretRef): String? {
        val queued = synchronized(pending) { pending[ref.format()] }
        // A queued *forgetting* answers null here rather than falling through to the vault: the app
        // has already thrown this secret away, and the vault's copy is the stale one.
        if (queued != null) return queued.value
        return broker?.read(ref)
    }

    /**
     * Mirror [value] into the vault, or queue it if the vault is not open.
     *
     * Returns true if it landed in the vault now. Callers do not need to care — the point of the
     * queue is that they do not have to — but a settings screen that wants to say "saved to your
     * vault" versus "will be saved when you next unlock" has the answer here.
     */
    fun remember(ref: SecretRef, value: String, label: String, owner: SecretOwner): Boolean {
        val landed = broker?.write(ref, value, label, owner) == true
        if (landed) synchronized(pending) { pending.remove(ref.format()) }
        else enqueue(Pending(ref, value, label, owner))
        announceChanged()
        return landed
    }

    /** Forget [ref], or queue the forgetting. Same contract as [remember]. */
    fun forget(ref: SecretRef): Boolean {
        val landed = broker?.forget(ref) == true
        if (landed) synchronized(pending) { pending.remove(ref.format()) }
        else enqueue(Pending(ref, null, "", null))
        announceChanged()
        return landed
    }

    /**
     * Apply everything that was queued while the vault was shut. Called by :secrets on unlock.
     *
     * Returns how many landed. A write that fails again is dropped rather than retried forever: the
     * only reason it can fail with an open vault is a vault that will not save, and a queue that
     * survives that is a queue that grows.
     */
    fun flushPending(): Int {
        val current = broker ?: return 0
        if (current.state != VaultState.UNLOCKED) return 0
        val queued = synchronized(pending) { pending.values.toList().also { pending.clear() } }
        return queued.count { op ->
            val value = op.value
            val owner = op.owner
            if (value == null || owner == null) current.forget(op.ref)
            else current.write(op.ref, value, op.label, owner)
        }.also { announceChanged() }
    }

    /** How many writes are waiting for an unlock. Shown on the unlock screen, so the queue is visible. */
    val pendingCount: Int get() = synchronized(pending) { pending.size }

    private fun enqueue(op: Pending) {
        synchronized(pending) {
            if (pending.size >= PENDING_LIMIT && !pending.containsKey(op.ref.format())) return
            // Re-inserting rather than replacing in place, so the newest write is also the newest in
            // the queue's order. Two writes to one ref collapse to the later one, which is what they
            // mean.
            pending.remove(op.ref.format())
            pending[op.ref.format()] = op
        }
    }

    /** Test seam: forget the broker, everything queued, and everybody listening. */
    fun reset() {
        broker = null
        synchronized(pending) { pending.clear() }
        watchers.clear()
    }
}
