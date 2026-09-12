package com.operations.sandbox.cloud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.operations.backupkit.AppId
import com.operations.backupkit.cloud.CloudBackupFrequency
import com.operations.backupkit.cloud.CloudBackupRetention
import com.operations.backupkit.cloud.TargetCheck
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
 * The container's second credential, and the settings around it.
 *
 * The mirroring half is word for word the case `UpdateTokenVaultTest` makes for the GitHub token,
 * and it matters more here: a household whose restore quietly dropped the signature would have a
 * new phone that looks exactly like a working one and has not uploaded a backup since the day they
 * switched phones. Everything else in this class is about defaults — the ones that decide what an
 * unconfigured install does with somebody's mobile data and storage bill.
 */
@RunWith(RobolectricTestRunner::class)
// A plain Application rather than the suite's own, for the reason `UpdateTokenVaultTest` gives:
// `SandboxApplication.onCreate` installs eleven apps and registers background work, none of which
// this class is testing.
@Config(sdk = [33], application = android.app.Application::class)
class CloudBackupPrefsTest {

    /** A vault that is nothing but a map — the real one is tested in :secrets. */
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

    private val ref = CloudBackupPrefs.SAS_REF

    private val sas = "sv=2022-11-02&sp=rwdlac&se=2026-12-31T23:59:59Z&sig=abc%2Fdef"

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** A store over a plain preferences file — see the note on [CloudBackupPrefs]'s `override`. */
    private fun prefs(): CloudBackupPrefs =
        CloudBackupPrefs(context, context.getSharedPreferences(LOCAL_STORE, Context.MODE_PRIVATE))

    private fun localSas(): String? =
        context.getSharedPreferences(LOCAL_STORE, Context.MODE_PRIVATE).getString("azure_sas", null)

    /** The state a restore onto a new phone leaves: the device-bound store gone, the vault not. */
    private fun wipeLocalStoreOnly() {
        context.getSharedPreferences(LOCAL_STORE, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Before
    fun setUp() {
        SecretsAccess.reset()
        // Robolectric keeps one application per test class, so both stores outlive a test.
        wipeLocalStoreOnly()
        context.getSharedPreferences("sandbox_cloud_backup", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() = SecretsAccess.reset()

    @Test
    fun `an unconfigured install uploads nothing, on wi-fi only, keeping a week`() {
        val prefs = prefs()
        assertFalse(prefs.enabled)
        assertTrue(prefs.wifiOnly)
        assertEquals(CloudBackupFrequency.DAILY, prefs.frequency)
        assertEquals(CloudBackupRetention.DEFAULT_KEEP, prefs.keep)
        // Everything, because an automatic backup that quietly omitted an app would be discovered
        // on the one day it mattered.
        assertEquals(AppId.entries.toSet(), prefs.selectedApps)
        assertTrue(prefs.target() is TargetCheck.Incomplete)
        assertFalse(prefs.isDue())
    }

    @Test
    fun `a configured destination reads back as one`() {
        val prefs = prefs().apply {
            account = " HouseHold "
            container = "backups"
            prefix = "/phones/pixel/"
            sasToken = sas
        }
        val check = prefs.target()
        assertTrue("expected a usable target, got $check", check is TargetCheck.Ready)
        assertEquals("household / backups / phones/pixel/", (check as TargetCheck.Ready).target.describe())
    }

    @Test
    fun `a signature pasted as a whole url is stored as the token it contains`() {
        val prefs = prefs()
        prefs.sasToken = "https://household.blob.core.windows.net/backups?$sas"
        assertEquals(sas, prefs.sasToken)
    }

    @Test
    fun `a saved signature reaches both stores, filed to the sandbox`() {
        val vault = FakeVault()
        SecretsAccess.register(vault)

        prefs().sasToken = sas

        assertEquals(sas, prefs().sasToken)
        assertEquals(sas, vault.items[ref.format()])
        assertEquals(SecretOwner.SHELL, vault.owners[ref.format()])
        assertEquals("Operations Sandbox — Azure backup signature", vault.labels[ref.format()])
        // The filing name is stable, and is what an archive written today will still be read by.
        assertEquals("sandbox/self/azure-backup-sas", ref.format())
    }

    @Test
    fun `a signature mirrored on one phone is read back on the next one`() {
        val vault = FakeVault()
        SecretsAccess.register(vault)
        prefs().sasToken = sas

        // The restore: the local store is gone (it was never in the archive), the vault is not.
        wipeLocalStoreOnly()
        assertNull("the working copy really is empty", localSas())

        assertEquals(sas, prefs().sasToken)
        // And it was put back, so the next upload costs no vault read and works with it shut again.
        assertEquals(sas, localSas())
    }

    @Test
    fun `clearing the signature forgets it in the vault too`() {
        val vault = FakeVault()
        SecretsAccess.register(vault)
        prefs().sasToken = sas

        prefs().sasToken = null

        assertNull(prefs().sasToken)
        assertTrue(vault.items.isEmpty())
    }

    @Test
    fun `a phone with no vault uploads exactly as it would have`() {
        prefs().sasToken = sas

        assertEquals(sas, prefs().sasToken)
        assertEquals(sas, localSas())
    }

    @Test
    fun `a write while the vault is shut is queued rather than lost`() {
        val vault = FakeVault(VaultState.LOCKED)
        SecretsAccess.register(vault)

        prefs().sasToken = sas

        assertEquals(sas, prefs().sasToken)
        assertEquals(1, SecretsAccess.pendingCount)

        vault.state = VaultState.UNLOCKED
        assertEquals(1, SecretsAccess.flushPending())
        assertEquals(sas, vault.items[ref.format()])
    }

    @Test
    fun `a rebuilt vault gets the signature filed again`() {
        val vault = FakeVault()
        SecretsAccess.register(vault)
        prefs().sasToken = sas
        vault.items.clear()

        assertEquals(1, prefs().refileIntoVault())
        assertEquals(sas, vault.items[ref.format()])
        // Nothing to refile when there is nothing held.
        wipeLocalStoreOnly()
        assertEquals(0, prefs().refileIntoVault())
    }

    @Test
    fun `the selected apps are stored by key, and a key this build never heard of is dropped`() {
        val prefs = prefs()
        prefs.selectedApps = setOf(AppId.LIFEOPS, AppId.FINANCE)
        assertEquals(setOf(AppId.LIFEOPS, AppId.FINANCE), prefs.selectedApps)

        context.getSharedPreferences("sandbox_cloud_backup", Context.MODE_PRIVATE)
            .edit().putStringSet("apps", setOf("lifeops", "orchard")).commit()
        assertEquals(setOf(AppId.LIFEOPS), prefs.selectedApps)
    }

    @Test
    fun `a run is owed once the interval has passed, and only while it is switched on`() {
        val prefs = prefs().apply {
            enabled = true
            frequency = CloudBackupFrequency.WEEKLY
            lastRunAt = System.currentTimeMillis() - 2 * 24 * 60 * 60 * 1000L
        }
        assertFalse(prefs.isDue())
        prefs.lastRunAt = System.currentTimeMillis() - 8 * 24 * 60 * 60 * 1000L
        assertTrue(prefs.isDue())
        prefs.enabled = false
        assertFalse(prefs.isDue())
    }

    private companion object {
        const val LOCAL_STORE = "test_sandbox_cloud_backup_secrets"
    }
}
