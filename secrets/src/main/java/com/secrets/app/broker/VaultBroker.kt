package com.secrets.app.broker

import com.operations.backupkit.AppId
import com.operations.vaultkit.SecretRef
import com.operations.vaultkit.SecretsBroker
import com.operations.vaultkit.VaultDocument
import com.operations.vaultkit.VaultItem
import com.operations.vaultkit.VaultItemKind
import com.operations.vaultkit.VaultState
import com.secrets.app.data.VaultStore
import java.util.UUID

/**
 * The vault as the other apps see it: [SecretsBroker], implemented over the real store.
 *
 * Registered once at start-up ([com.secrets.app.SecretsApp.install]) with
 * [com.operations.vaultkit.SecretsAccess], which is where Finance and Citation find it.
 *
 * ## What an app can and cannot do here
 *
 * It can read back what it filed, file something, and forget it. It cannot list the vault, cannot
 * read another app's refs — well, it *could*, since a ref is just a string and every module is in
 * one process — and the honest thing to say about that is: this is not a sandbox, it is a shape. Ten
 * apps written by the same person in the same process cannot be isolated from each other by an
 * interface, and pretending otherwise would be theatre. What the shape buys is that reaching further
 * has to be *written down*: there is no listing call to be tempted by, and a module reading
 * `citation/oreilly/pin` would have to name it.
 *
 * ## What a write costs the caller
 *
 * [write] and [forget] are synchronous: they change the document and re-seal and rewrite the vault
 * file before returning, on whatever thread the calling app was on — which for a screen saving a
 * token is usually the main one. That is deliberate. A "write" that returned before it had written
 * could not honestly report whether the vault took it, and the queue in
 * [com.operations.vaultkit.SecretsAccess] depends on that answer being true.
 *
 * The cost is bounded by the size of the vault: one AES pass and one small file write, single-digit
 * milliseconds for a household's worth of secrets, and no key derivation (the header is reused —
 * see `VaultEnvelope.reseal`). A vault large enough for that to be felt is a vault this app was not
 * built for.
 *
 * ## Titles
 *
 * A mirrored item is created with the label the app supplied and never re-titled afterwards, even if
 * the app supplies a different one on a later write. Somebody who renamed "Finance — USAA token" to
 * "The joint account" in the Secrets list should not find it renamed back the next time a token is
 * refreshed. The value is the app's; the name is the household's.
 */
class VaultBroker(private val store: VaultStore) : SecretsBroker {

    override val state: VaultState get() = store.state.value

    override fun read(ref: SecretRef): String? =
        store.document.value?.managed(ref)?.secret?.takeIf { it.isNotEmpty() }

    override fun write(ref: SecretRef, value: String, label: String, owner: AppId): Boolean {
        if (value.isEmpty()) return forget(ref)
        val now = System.currentTimeMillis()
        return store.mutateBlocking { document ->
            val existing = document.managed(ref)
            if (existing != null) {
                if (existing.secret == value) document else {
                    document.upsert(existing.copy(secret = value, updatedAt = now), now)
                }
            } else {
                document.upsert(newItem(ref, value, label, owner, now), now)
            }
        }
    }

    override fun forget(ref: SecretRef): Boolean {
        val document: VaultDocument = store.document.value ?: return false
        val existing = document.managed(ref) ?: return state == VaultState.UNLOCKED
        val now = System.currentTimeMillis()
        return store.mutateBlocking { it.delete(existing.id, now) }
    }

    private fun newItem(
        ref: SecretRef,
        value: String,
        label: String,
        owner: AppId,
        now: Long
    ) = VaultItem(
        id = UUID.randomUUID().toString(),
        kind = VaultItemKind.API_KEY,
        title = label.ifBlank { ref.format() },
        secret = value,
        // The address is kept on the item so the Secrets list can show what it is for, and so a
        // household reading their own vault can see that this row is Finance's rather than something
        // they typed and forgot.
        ref = ref.format(),
        managedBy = owner.key,
        createdAt = now,
        updatedAt = now
    )
}
