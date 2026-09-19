package com.operations.sandbox.update

import androidx.test.core.app.ApplicationProvider
import com.operations.vaultkit.SecretOwner
import com.operations.vaultkit.SecretRef
import com.operations.vaultkit.SecretsAccess
import com.operations.vaultkit.SecretsBroker
import com.operations.vaultkit.VaultState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The shell's own half of the vault seam.
 *
 * The container holds one credential — the GitHub token the updater checks releases with — and
 * until now it held it exactly the way Finance held its bank tokens before Secrets existed: in
 * `EncryptedSharedPreferences`, behind a key bound to this phone's hardware, deliberately outside
 * the archive. Which meant a restore onto a new phone brought back every setting on the Updates tab
 * and not the token, and the suite quietly lost the ability to tell anybody it had an update.
 *
 * So these are the same tests Finance has, from the shell's side, and the one that matters is
 * [a token mirrored on one phone is read back on the next one].
 */
@RunWith(RobolectricTestRunner::class)
// A plain Application rather than the suite's own. Robolectric would otherwise run
// `SandboxApplication.onCreate`, which installs eleven apps and schedules their WorkManager jobs —
// none of which this class is testing, and one of which (WorkManager, with no initializer on the
// JVM) throws before the first assertion. The shell's token store needs a Context and nothing else.
@Config(sdk = [34], application = android.app.Application::class)
class UpdateTokenVaultTest {

    /**
     * A vault that is nothing but a map. The real one is tested in :secrets; what is under test
     * here is only whether the shell reaches it, and with what.
     */
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

    private val ref = UpdatePrefs.TOKEN_REF

    /**
     * A store over a plain preferences file — see the note on [UpdatePrefs]'s `override`. The
     * encryption of the local copy is androidx's to test; what is under test here is what this
     * class does with the vault.
     */
    private fun prefs(): UpdatePrefs {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        return UpdatePrefs(context, context.getSharedPreferences(LOCAL_STORE, android.content.Context.MODE_PRIVATE))
    }

    private fun localToken(): String? =
        ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSharedPreferences(LOCAL_STORE, android.content.Context.MODE_PRIVATE)
            .getString("github_token", null)

    /**
     * Empty the shell's own store without telling the vault anything — the state a restore onto a
     * new phone leaves behind, and one no production code path produces.
     */
    private fun wipeLocalStoreOnly() {
        ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSharedPreferences(LOCAL_STORE, android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Before
    fun setUp() {
        SecretsAccess.reset()
        // Robolectric keeps one application per test class, so the store outlives a test.
        wipeLocalStoreOnly()
    }

    @After
    fun tearDown() = SecretsAccess.reset()

    private companion object {
        const val LOCAL_STORE = "test_sandbox_update_secrets"
    }

    @Test
    fun `a saved token reaches both stores`() {
        val vault = FakeVault()
        SecretsAccess.register(vault)

        prefs().token = "ghp_abc"

        assertEquals("ghp_abc", prefs().token)
        assertEquals("ghp_abc", vault.items[ref.format()])
    }

    @Test
    fun `the vault row says it belongs to the sandbox, not to an app`() {
        val vault = FakeVault()
        SecretsAccess.register(vault)

        prefs().token = "ghp_abc"

        assertEquals(SecretOwner.SHELL, vault.owners[ref.format()])
        assertEquals("Operations Sandbox — GitHub update token", vault.labels[ref.format()])
        // The filing name is stable and is what an archive written today will still be read by.
        assertEquals("sandbox/self/github-token", ref.format())
    }

    @Test
    fun `a token mirrored on one phone is read back on the next one`() {
        // The whole point of the change, in one test. Phone one files the token; phone two is a
        // fresh install whose local store is empty and whose vault came out of the archive.
        val vault = FakeVault()
        SecretsAccess.register(vault)
        prefs().token = "ghp_abc"

        // The restore: the local store is gone (it was never in the archive), the vault is not.
        wipeLocalStoreOnly()
        assertNull("the working copy really is empty", localToken())

        assertEquals("ghp_abc", prefs().token)
    }

    @Test
    fun `a read-through puts the value back in the local store`() {
        val vault = FakeVault()
        SecretsAccess.register(vault)
        prefs().token = "ghp_abc"
        wipeLocalStoreOnly()

        prefs().token

        // Rehydrated, so the second check of the morning costs nothing and works with the vault
        // shut again — which it will be, since the vault locks on every process start.
        assertEquals("ghp_abc", localToken())
    }

    @Test
    fun `clearing the token forgets it in the vault too`() {
        val vault = FakeVault()
        SecretsAccess.register(vault)
        prefs().token = "ghp_abc"

        prefs().token = null

        assertNull(prefs().token)
        assertTrue(vault.items.isEmpty())
    }

    @Test
    fun `a phone with no vault behaves exactly as it did before`() {
        // No broker registered at all. Every call has to be a no-op that leaves the updater working.
        prefs().token = "ghp_abc"

        assertEquals("ghp_abc", prefs().token)
        assertEquals("ghp_abc", localToken())
    }

    @Test
    fun `a write while the vault is shut is queued rather than lost`() {
        val vault = FakeVault(VaultState.LOCKED)
        SecretsAccess.register(vault)

        prefs().token = "ghp_abc"

        // The local store answers, so the updater is unaffected; the mirror is waiting, which is
        // the thing the home screen now says out loud.
        assertEquals("ghp_abc", prefs().token)
        assertEquals(1, SecretsAccess.pendingCount)

        vault.state = VaultState.UNLOCKED
        assertEquals(1, SecretsAccess.flushPending())
        assertEquals("ghp_abc", vault.items[ref.format()])
    }

    @Test
    fun `an unlock puts the token back before anything asks for it`() {
        // The pull above works only when something reads the token *while the vault is open*, and
        // the launch check on a restored phone runs before anybody has typed a passphrase. So the
        // vault pushes on unlock, and this is the shell's half of that round.
        val vault = FakeVault()
        SecretsAccess.register(vault)
        prefs().token = "ghp_abc"
        wipeLocalStoreOnly()

        assertEquals(1, prefs().restockFromVault())

        assertEquals("ghp_abc", localToken())
        assertEquals("and the second unlock of the day has nothing to do", 0, prefs().restockFromVault())
    }

    @Test
    fun `an unlock never writes over a token typed on this phone`() {
        val vault = FakeVault()
        SecretsAccess.register(vault)
        prefs().token = "ghp_typed_here"
        vault.items[ref.format()] = "ghp_older_copy"

        assertEquals(0, prefs().restockFromVault())
        assertEquals("ghp_typed_here", localToken())
    }

    @Test
    fun `a rebuilt vault gets the token filed again`() {
        // The forgotten-passphrase path: the vault is gone, the token is still twelve inches away
        // in the shell's own store, and there is no reason for the rebuild to leave the updater
        // unable to check for releases.
        prefs().token = "ghp_abc"
        val vault = FakeVault()
        SecretsAccess.register(vault)

        assertEquals(1, prefs().refileIntoVault())

        assertEquals("ghp_abc", vault.items[ref.format()])
        assertEquals(SecretOwner.SHELL, vault.owners[ref.format()])
    }

    @Test
    fun `a shell with no token to give refiles nothing`() {
        SecretsAccess.register(FakeVault())

        // Reporting a success of zero would be worse than saying there was nothing — see the reset
        // screen, which prints exactly what each source handed over.
        assertEquals(0, prefs().refileIntoVault())
    }
}
