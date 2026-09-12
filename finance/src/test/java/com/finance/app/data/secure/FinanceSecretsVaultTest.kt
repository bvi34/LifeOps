package com.finance.app.data.secure

import androidx.test.core.app.ApplicationProvider
import com.finance.app.logic.Endpoints
import com.operations.backupkit.AppId
import com.operations.vaultkit.SecretOwner
import com.operations.vaultkit.SecretRef
import com.operations.vaultkit.SecretsAccess
import com.operations.vaultkit.SecretsBroker
import com.operations.vaultkit.VaultState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Finance's half of the vault seam: what it files, what it reads back, and what happens on the phone
 * that replaced the one it was set up on.
 *
 * The vault itself is a fake here — a map with a lock on it — because what is under test is not the
 * cryptography (that is :vaultkit's own suite) but the four decisions this app makes: which refs it
 * uses, that a write goes to both places, that a read prefers the local copy, and that a read which
 * finds nothing locally comes back from the vault and is written back down.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FinanceSecretsVaultTest {

    private class FakeVault(override var state: VaultState = VaultState.UNLOCKED) : SecretsBroker {
        val items = LinkedHashMap<String, String>()
        val labels = LinkedHashMap<String, String>()
        val owners = LinkedHashMap<String, SecretOwner>()

        override fun read(ref: SecretRef): String? =
            if (state == VaultState.UNLOCKED) items[ref.format()] else null

        override fun write(ref: SecretRef, value: String, label: String, owner: SecretOwner): Boolean {
            if (state != VaultState.UNLOCKED) return false
            items[ref.format()] = value
            labels[ref.format()] = label
            owners[ref.format()] = owner
            return true
        }

        override fun forget(ref: SecretRef): Boolean {
            if (state != VaultState.UNLOCKED) return false
            items.remove(ref.format())
            return true
        }
    }

    private lateinit var vault: FakeVault

    @Before
    fun setUp() {
        vault = FakeVault()
        SecretsAccess.register(vault)
    }

    @After
    fun tearDown() = SecretsAccess.reset()

    /**
     * A store over a plain preferences file — see the note on [FinanceSecrets.override]. The
     * encryption of the local copy is androidx's to test; what is under test here is what this class
     * does with the vault.
     */
    private fun secrets(): FinanceSecrets {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        return FinanceSecrets(context, context.getSharedPreferences(LOCAL_STORE, android.content.Context.MODE_PRIVATE))
    }

    private fun freshInstall(): FinanceSecrets = secrets().also { it.forgetAll() }

    /**
     * Empty this app's own encrypted store without telling the vault anything — the state a restore
     * onto a new phone leaves behind, and one no production code path produces.
     */
    private fun wipeLocalStoreOnly() {
        ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSharedPreferences(LOCAL_STORE, android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private companion object {
        const val LOCAL_STORE = "test_finance_local_store"
    }

    @Test
    fun `a token is written to this app's own store and mirrored into the vault`() {
        val secrets = freshInstall()

        secrets.setToken("conn-1", "access-abc", label = "USAA")

        assertEquals("access-abc", secrets.token("conn-1"))
        assertEquals("access-abc", vault.items["finance/conn-1/access-token"])
        assertEquals("Finance — USAA access token", vault.labels["finance/conn-1/access-token"])
        assertEquals(SecretOwner.of(AppId.FINANCE), vault.owners["finance/conn-1/access-token"])
    }

    @Test
    fun `the Plaid keys and their environment all travel`() {
        val secrets = freshInstall()

        secrets.plaidKeys = FinanceSecrets.PlaidKeys(
            clientId = "client-123",
            secret = "secret-456",
            environment = Endpoints.PlaidEnvironment.PRODUCTION
        )

        assertEquals("client-123", vault.items["finance/self/plaid-client-id"])
        assertEquals("secret-456", vault.items["finance/self/plaid-secret"])
        assertEquals(
            "keys restored without their environment fail against the wrong host",
            Endpoints.PlaidEnvironment.PRODUCTION.key,
            vault.items["finance/self/plaid-environment"]
        )
    }

    /** The restore. This app's own store is empty; the vault came back in the archive. */
    @Test
    fun `a credential comes back out of the vault and is written into the local store`() {
        freshInstall().setToken("conn-1", "access-abc", label = "USAA")
        // A new phone: the Keystore-backed store did not travel, the vault did. Emptying the
        // underlying file rather than calling forgetAll() is what makes this the restore case —
        // forgetAll() would tell the vault to forget the token too, which is the *other* scenario.
        wipeLocalStoreOnly()
        val newPhone = secrets()

        assertEquals("access-abc", newPhone.token("conn-1"))
        // Read again with the vault shut: the value is in the local store now, so the connection
        // works whether or not anybody unlocks anything again.
        vault.state = VaultState.LOCKED
        assertEquals("access-abc", newPhone.token("conn-1"))
    }

    @Test
    fun `without a vault the app behaves exactly as it did before`() {
        SecretsAccess.reset()
        val secrets = freshInstall()

        secrets.setToken("conn-1", "access-abc")
        assertEquals("access-abc", secrets.token("conn-1"))

        secrets.forget("conn-1")
        assertNull(secrets.token("conn-1"))
    }

    @Test
    fun `removing a connection forgets the vault's copy too`() {
        val secrets = freshInstall()
        secrets.setToken("conn-1", "access-abc")

        secrets.forget("conn-1")

        assertNull(secrets.token("conn-1"))
        assertFalse(vault.items.containsKey("finance/conn-1/access-token"))
    }

    @Test
    fun `disconnecting everything clears both stores`() {
        val secrets = freshInstall()
        secrets.setToken("conn-1", "a")
        secrets.setToken("conn-2", "b")
        secrets.plaidKeys = FinanceSecrets.PlaidKeys("c", "d", Endpoints.PlaidEnvironment.SANDBOX)

        secrets.forgetAll()

        assertTrue("the vault must not keep tokens for connections that are gone", vault.items.isEmpty())
        assertNull(secrets.token("conn-1"))
        assertNull(secrets.plaidKeys)
    }

    @Test
    fun `a token set while the vault is shut lands the next time it is opened`() {
        val secrets = freshInstall()
        vault.state = VaultState.LOCKED

        secrets.setToken("conn-1", "access-abc", label = "USAA")

        assertEquals("the local store took it either way", "access-abc", secrets.token("conn-1"))
        vault.state = VaultState.UNLOCKED
        assertEquals(1, SecretsAccess.flushPending())
        assertEquals("access-abc", vault.items["finance/conn-1/access-token"])
    }

    @Test
    fun `refiling hands the vault everything this store holds, named where it can be`() {
        val secrets = freshInstall()
        secrets.setToken("conn-1", "access-abc")
        secrets.setToken("conn-2", "access-def")
        secrets.plaidKeys = FinanceSecrets.PlaidKeys("client", "secret", Endpoints.PlaidEnvironment.SANDBOX)
        // The vault is the thing that was lost, so empty it and leave this store alone — which is
        // exactly the state a forgotten passphrase leaves behind.
        vault.items.clear()

        val filed = secrets.refileIntoVault { id -> if (id == "conn-1") "USAA" else null }

        assertEquals(5, filed)
        assertEquals("access-abc", vault.items["finance/conn-1/access-token"])
        assertEquals("access-def", vault.items["finance/conn-2/access-token"])
        assertEquals("client", vault.items["finance/self/plaid-client-id"])
        assertEquals("Finance — USAA access token", vault.labels["finance/conn-1/access-token"])
        assertEquals(
            "a connection whose name could not be looked up is still filed, just generically",
            "Finance — Connection access token",
            vault.labels["finance/conn-2/access-token"]
        )
    }

    @Test
    fun `refiling an empty store files nothing and says so`() {
        val secrets = freshInstall()
        vault.items.clear()

        assertEquals(0, secrets.refileIntoVault())
        assertTrue(vault.items.isEmpty())
    }

    @Test
    fun `this app's ref segment is its AppId key`() {
        // The Secrets list shows "managed by Finance" by resolving this string through AppId; a
        // typo here would file every token under an app that does not exist.
        val secrets = freshInstall()
        secrets.setToken("conn-1", "access-abc")

        val ref = SecretRef.parse(vault.items.keys.single())
        assertEquals(AppId.FINANCE.key, ref?.app)
    }
}


