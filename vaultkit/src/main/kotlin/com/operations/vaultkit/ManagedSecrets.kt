package com.operations.vaultkit

import com.operations.backupkit.AppId

/**
 * The three lines every app repeats to put its credentials in the vault, written once.
 *
 * ## The shape of the bargain
 *
 * Before this module, every app in the suite kept its credentials the same way and for the same
 * good reason: in `EncryptedSharedPreferences` behind a hardware-bound Keystore key, deliberately
 * left out of the backup, because a zip file in a cloud drive carrying a standing read grant on
 * somebody's bank account is the worst thing this suite could produce. `FinanceBackupContributor`
 * says so at length, and it was right.
 *
 * The cost of being right was that a restore onto a new phone brought back years of transactions and
 * not one credential — and the same for every catalogue sign-in, every library card, every API key.
 * "Two minutes of reconnecting" per app, per connection, from memory, on a phone that may be the
 * replacement for a lost one.
 *
 * The vault changes what is possible, because it is the one store here whose key is **not** bound to
 * the device: a passphrase the household knows, stretched into a key, wrapping a document that is
 * safe to put in a backup precisely because nothing in the archive can open it. So a credential can
 * now live in two places at once:
 *
 *  - the app's own encrypted store, still device-bound, still excluded from the archive — the
 *    **working copy**, read on every sync, available whether or not anybody has unlocked anything;
 *  - the vault — the **surviving copy**, which travels in the backup and is what a restore has to
 *    hand.
 *
 * [readThrough] is what makes the second one worth having: after a restore the working copy is empty,
 * so the first read falls through to the vault, finds the credential, and puts it back in the local
 * store. Nobody re-authorises anything; they type the master passphrase once, which they had to do
 * anyway to open Secrets.
 *
 * ## What this does not change
 *
 * The archive still contains no readable credential. It contains a vault, and the vault is a
 * ciphertext whose key is not in the file, not on the phone, and not in the backup — it is in
 * somebody's head. That is a different and much better bargain than either "tokens in the zip" or
 * "tokens die with the phone", and it is the entire argument for this app.
 */
object ManagedSecrets {

    /**
     * Read a credential: the local working copy if it has one, otherwise the vault.
     *
     * [local] is the app's own store. [rehydrate] is how to put a value back into it — called only
     * when the vault supplied one the local store was missing, which after a restore is every
     * credential and in normal running is none of them.
     *
     * Returns null when neither has it, which is what a caller already handles: the vault being
     * locked is indistinguishable, and deliberately so, from a connection that was never set up.
     */
    fun readThrough(ref: SecretRef, local: () -> String?, rehydrate: (String) -> Unit): String? {
        local()?.let { return it }
        val fromVault = SecretsAccess.read(ref)?.takeIf { it.isNotBlank() } ?: return null
        rehydrate(fromVault)
        return fromVault
    }

    /**
     * File a credential in the vault under [ref], titled [label] and shown as belonging to [owner].
     *
     * Every app calls this *after* writing its own store, never instead of it. If the vault is shut
     * the write is queued in memory and lands on the next unlock (see [SecretsAccess]); if there is
     * no vault at all it is dropped, and the app is exactly where it was before this module existed.
     */
    fun remember(ref: SecretRef, value: String, label: String, owner: SecretOwner) {
        if (value.isBlank()) {
            forget(ref)
            return
        }
        SecretsAccess.remember(ref, value, label, owner)
    }

    /**
     * The same, for the ten cases out of eleven where the owner is a hosted app.
     *
     * Kept as an overload rather than making every call site write `SecretOwner.of(AppId.FINANCE)`:
     * the apps were here first, an [AppId] is what they have, and the widening exists for the one
     * caller that is not an app (see [SecretOwner]).
     */
    fun remember(ref: SecretRef, value: String, label: String, owner: AppId) {
        remember(ref, value, label, SecretOwner.of(owner))
    }

    /**
     * Forget a credential, because the connection it belonged to is gone.
     *
     * Best effort by construction: a vault that is shut takes the forgetting into the same queue as
     * a write, and a vault that is *absent* cannot be asked at all — which is not a leak, since a
     * vault that never held it has nothing to forget.
     */
    fun forget(ref: SecretRef) {
        SecretsAccess.forget(ref)
    }

    /**
     * The title a mirrored item wears in the Secrets list: `Finance — USAA access token`.
     *
     * Built here rather than at each call site so the list reads as one thing. The em dash is what
     * separates the app from what the secret is; [what] should be the human name of the connection
     * and the credential, not an id, because the id is already in the ref shown underneath.
     */
    fun label(owner: SecretOwner, what: String): String = "${owner.displayName} — $what"

    /** The [AppId] form, for the same reason the [remember] overload exists. */
    fun label(owner: AppId, what: String): String = label(SecretOwner.of(owner), what)
}
