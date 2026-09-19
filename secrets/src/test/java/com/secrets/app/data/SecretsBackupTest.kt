package com.secrets.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.operations.backupkit.BackupSink
import com.operations.backupkit.BackupSource
import com.operations.vaultkit.VaultEnvelope
import com.operations.vaultkit.VaultItem
import com.operations.vaultkit.VaultJson
import com.secrets.app.backup.SecretsBackupContributor
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The archive, and the two restores.
 *
 * This is the contributor that breaks the suite's rule — it puts credentials in the backup — so the
 * tests here are about the two things that make that safe and the one thing that makes it useful:
 *
 *  - what travels is the *sealed* file, and nothing in the archive opens it;
 *  - the device shortcut, which *would* open it, does not travel;
 *  - restoring onto a phone with no vault installs it, and restoring onto a phone that has one does
 *    not overwrite it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SecretsBackupTest {

    private lateinit var context: Context
    private lateinit var files: VaultFileStore

    private val passphrase get() = "a passphrase nobody guesses".toCharArray()

    /** A backup archive as a map, which is all a contributor can tell about one anyway. */
    private class FakeArchive : BackupSink, BackupSource {
        val entries = LinkedHashMap<String, ByteArray>()

        override fun entry(relativePath: String): OutputStream = object : ByteArrayOutputStream() {
            override fun close() {
                entries[relativePath] = toByteArray()
                super.close()
            }
        }

        override fun list(): List<String> = entries.keys.toList()

        override fun open(relativePath: String): InputStream? = entries[relativePath]?.inputStream()
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        files = VaultFileStore(context)
        files.deleteAll()
    }

    @Test
    fun `the archive holds the sealed vault and nothing that opens it`() = runTest {
        val store = VaultStore(context)
        store.create(passphrase)
        store.mutate { it.upsert(item("Bank", "elderflower-4417"), 10) }
        store.enableDeviceUnlock()

        val archive = FakeArchive()
        SecretsBackupContributor(context).backup(archive)

        val vault = archive.entries[SecretsBackupContributor.VAULT_ENTRY]
        assertNotNull("the vault travels", vault)
        assertTrue(VaultEnvelope.looksLikeVault(vault!!))
        assertFalse(
            "and the secret in it is not readable from the archive",
            String(vault, Charsets.ISO_8859_1).contains("elderflower-4417")
        )
        assertTrue(
            "the device key store must never be in an archive",
            archive.list().none { it.contains("secure_secrets_device") }
        )
    }

    @Test
    fun `restoring onto a phone with no vault installs it, and the passphrase still opens it`() = runTest {
        val original = VaultStore(context)
        original.create(passphrase)
        original.mutate { it.upsert(item("Bank", "hunter2"), 10) }
        val archive = FakeArchive()
        SecretsBackupContributor(context).backup(archive)

        // The new phone.
        files.deleteAll()
        assertFalse(VaultStore(context).exists)

        SecretsBackupContributor(context).restore(archive)

        val restored = VaultStore(context)
        assertTrue(restored.exists)
        assertEquals(VaultStore.UnlockResult.UNLOCKED, restored.unlock(passphrase))
        assertEquals("hunter2", restored.document.value?.live?.single()?.secret)
    }

    @Test
    fun `restoring over a live vault stages it instead of overwriting`() = runTest {
        val old = VaultStore(context)
        old.create(passphrase)
        old.mutate { it.upsert(item("Bank", "from-the-backup"), 10) }
        val archive = FakeArchive()
        SecretsBackupContributor(context).backup(archive)

        // Time passes and this phone's vault moves on.
        old.mutate { it.upsert(item("Broadband", "added-since"), 20) }

        SecretsBackupContributor(context).restore(archive)

        val live = VaultStore(context)
        assertEquals(VaultStore.UnlockResult.UNLOCKED, live.unlock(passphrase))
        assertEquals(
            "nothing added since the backup may be lost",
            setOf("Bank", "Broadband"),
            live.document.value!!.live.map { it.title }.toSet()
        )
        assertEquals("the archived vault waits to be merged", 1, live.stagedRestores.size)
    }

    @Test
    fun `a staged vault merges, and its passphrase is the one it was sealed with`() = runTest {
        val store = VaultStore(context)
        store.create(passphrase)
        store.mutate { it.upsert(item("Bank", "hunter2"), 10) }
        val archive = FakeArchive()
        SecretsBackupContributor(context).backup(archive)

        // The passphrase changes after the backup was taken — the case that makes a merge a
        // conversation rather than a file copy.
        store.changePassphrase(passphrase, "the newer passphrase".toCharArray())
        store.mutate { it.upsert(item("Broadband", "added-since"), 20) }
        SecretsBackupContributor(context).restore(archive)

        val live = VaultStore(context)
        live.unlock("the newer passphrase".toCharArray())
        val staged = live.stagedRestores.single()

        assertNull("the new passphrase does not open the old vault", live.mergeStaged(staged, "the newer passphrase".toCharArray()))

        val outcome = live.mergeStaged(staged, passphrase)
        assertNotNull(outcome)
        assertEquals(0, outcome!!.added)
        assertEquals(
            "both halves are still there",
            setOf("Bank", "Broadband"),
            live.document.value!!.live.map { it.title }.toSet()
        )
    }

    @Test
    fun `an archive entry that is not a vault is ignored rather than written`() = runTest {
        val archive = FakeArchive()
        archive.entry(SecretsBackupContributor.VAULT_ENTRY).use { it.write("not a vault".toByteArray()) }

        SecretsBackupContributor(context).restore(archive)

        assertFalse(VaultStore(context).exists)
    }

    @Test
    fun `a vault with no items still round trips`() = runTest {
        VaultStore(context).create(passphrase)
        val archive = FakeArchive()
        SecretsBackupContributor(context).backup(archive)
        files.deleteAll()

        SecretsBackupContributor(context).restore(archive)

        val restored = VaultStore(context)
        assertEquals(VaultStore.UnlockResult.UNLOCKED, restored.unlock(passphrase))
        assertEquals(emptyList<VaultItem>(), restored.document.value!!.live)
        assertNotNull(VaultJson.encode(restored.document.value!!))
    }

    private fun item(title: String, secret: String) = VaultItem(
        id = UUID.randomUUID().toString(),
        title = title,
        secret = secret,
        createdAt = 1,
        updatedAt = 1
    )
}
