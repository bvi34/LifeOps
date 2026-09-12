package com.operations.vaultkit

import com.operations.backupkit.AppId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one-credential half of the push: [ManagedSecrets.restock].
 *
 * Two properties, and the second is the one that could lose data if it were wrong: an empty local
 * slot is filled from the vault, and a full one is *never* touched. The working copy is always at
 * least as new as the vault's — a token refreshed this morning while the vault was shut is sitting
 * in the pending queue, not in the file — so a restock that preferred the vault would hand an app
 * back the credential it had already replaced.
 */
class ManagedSecretsRestockTest {

    private class FakeVault(override var state: VaultState = VaultState.UNLOCKED) : SecretsBroker {
        val items = LinkedHashMap<String, String>()

        override fun read(ref: SecretRef): String? =
            if (state == VaultState.UNLOCKED) items[ref.format()] else null

        override fun write(ref: SecretRef, value: String, label: String, owner: SecretOwner): Boolean {
            if (state != VaultState.UNLOCKED) return false
            items[ref.format()] = value
            return true
        }

        override fun forget(ref: SecretRef): Boolean {
            if (state != VaultState.UNLOCKED) return false
            items.remove(ref.format())
            return true
        }
    }

    private val ref = SecretRef("finance", "usaa", "access-token")

    /** An app's own store, as one nullable string. */
    private var local: String? = null

    private fun restock() = ManagedSecrets.restock(ref, local = { local }, save = { local = it })

    @After
    fun tearDown() {
        SecretsAccess.reset()
        local = null
    }

    @Test
    fun `an empty store is filled from the vault`() {
        val vault = FakeVault().apply { items[ref.format()] = "access-token-1" }
        SecretsAccess.register(vault)

        assertTrue(restock())
        assertEquals("access-token-1", local)
    }

    @Test
    fun `a store that already has one is left exactly as it was`() {
        val vault = FakeVault().apply { items[ref.format()] = "the-vaults-older-copy" }
        SecretsAccess.register(vault)
        local = "refreshed-this-morning"

        assertFalse(restock())
        assertEquals("refreshed-this-morning", local)
    }

    @Test
    fun `a blank local value counts as empty`() {
        val vault = FakeVault().apply { items[ref.format()] = "access-token-1" }
        SecretsAccess.register(vault)
        local = "   "

        assertTrue(restock())
        assertEquals("access-token-1", local)
    }

    @Test
    fun `a vault that does not hold it leaves the store empty rather than blank`() {
        SecretsAccess.register(FakeVault())

        assertFalse(restock())
        assertNull(local)
    }

    @Test
    fun `a shut vault answers nothing, and the next unlock is what fills the store`() {
        val vault = FakeVault(VaultState.LOCKED).apply { items[ref.format()] = "access-token-1" }
        SecretsAccess.register(vault)

        assertFalse(restock())
        assertNull(local)

        vault.state = VaultState.UNLOCKED
        assertTrue(restock())
        assertEquals("access-token-1", local)
    }

    @Test
    fun `a phone with no vault is exactly where it was`() {
        assertFalse(restock())
        assertNull(local)
    }

    @Test
    fun `a credential written while the vault was shut wins over the vault's older copy`() {
        // The ordering that makes the unlock sequence safe: the pending queue is flushed first, so
        // by the time anything is restocked the vault holds this morning's value rather than
        // yesterday's. Here the app's write is still queued, and the queue answers a read.
        val vault = FakeVault(VaultState.LOCKED).apply { items[ref.format()] = "yesterdays" }
        SecretsAccess.register(vault)
        ManagedSecrets.remember(ref, "this-mornings", ManagedSecrets.label(AppId.FINANCE, "token"), AppId.FINANCE)

        vault.state = VaultState.UNLOCKED
        assertEquals(1, SecretsAccess.flushPending())

        assertTrue(restock())
        assertEquals("this-mornings", local)
    }
}
