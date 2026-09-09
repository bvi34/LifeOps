package com.operations.vaultkit

import com.operations.backupkit.AppId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The seam every other app reaches the vault through, exercised without a vault: a fake broker that
 * can be locked and unlocked stands in for :secrets.
 *
 * The queue is the interesting half. Finance re-authorising a connection at eight in the morning,
 * before anybody has opened Secrets, is the case that would silently put the suite back where it
 * started — a credential in a device-bound store and nowhere else — so it has more tests here than
 * the straightforward path does.
 */
class SecretsAccessTest {

    private class FakeBroker(override var state: VaultState = VaultState.UNLOCKED) : SecretsBroker {
        val written = LinkedHashMap<String, String>()
        val labels = LinkedHashMap<String, String>()
        val owners = LinkedHashMap<String, AppId>()

        override fun read(ref: SecretRef): String? =
            if (state == VaultState.UNLOCKED) written[ref.format()] else null

        override fun write(ref: SecretRef, value: String, label: String, owner: AppId): Boolean {
            if (state != VaultState.UNLOCKED) return false
            written[ref.format()] = value
            labels[ref.format()] = label
            owners[ref.format()] = owner
            return true
        }

        override fun forget(ref: SecretRef): Boolean {
            if (state != VaultState.UNLOCKED) return false
            written.remove(ref.format())
            return true
        }
    }

    private val token = SecretRef("finance", "usaa", "access-token")

    @After
    fun tearDown() = SecretsAccess.reset()

    @Test
    fun `with no vault installed, everything is a no-op and nothing throws`() {
        SecretsAccess.reset()

        assertEquals(VaultState.ABSENT, SecretsAccess.state)
        assertFalse(SecretsAccess.available)
        assertNull(SecretsAccess.read(token))
        assertFalse(SecretsAccess.remember(token, "abc", "Finance — USAA", AppId.FINANCE))
    }

    @Test
    fun `an unlocked vault takes writes and gives them back`() {
        val broker = FakeBroker()
        SecretsAccess.register(broker)

        assertTrue(SecretsAccess.remember(token, "access-abc", "Finance — USAA token", AppId.FINANCE))

        assertEquals("access-abc", SecretsAccess.read(token))
        assertEquals("Finance — USAA token", broker.labels[token.format()])
        assertEquals(AppId.FINANCE, broker.owners[token.format()])
        assertEquals(0, SecretsAccess.pendingCount)
    }

    @Test
    fun `a write while locked is queued, readable, and lands on unlock`() {
        val broker = FakeBroker(VaultState.LOCKED)
        SecretsAccess.register(broker)

        assertFalse("nothing lands while the vault is shut", SecretsAccess.remember(token, "fresh", "t", AppId.FINANCE))
        assertEquals(1, SecretsAccess.pendingCount)
        assertEquals("the app can still read back what it just wrote", "fresh", SecretsAccess.read(token))

        broker.state = VaultState.UNLOCKED
        assertEquals(1, SecretsAccess.flushPending())

        assertEquals("fresh", broker.written[token.format()])
        assertEquals(0, SecretsAccess.pendingCount)
    }

    @Test
    fun `two writes to one ref while locked collapse to the later one`() {
        SecretsAccess.register(FakeBroker(VaultState.LOCKED))

        SecretsAccess.remember(token, "first", "t", AppId.FINANCE)
        SecretsAccess.remember(token, "second", "t", AppId.FINANCE)

        assertEquals(1, SecretsAccess.pendingCount)
        assertEquals("second", SecretsAccess.read(token))
    }

    @Test
    fun `a forgetting while locked hides the vault's stale copy and applies on unlock`() {
        val broker = FakeBroker()
        SecretsAccess.register(broker)
        SecretsAccess.remember(token, "old", "t", AppId.FINANCE)

        broker.state = VaultState.LOCKED
        assertFalse(SecretsAccess.forget(token))
        assertNull("the app has thrown this away; the vault's copy is stale", SecretsAccess.read(token))

        broker.state = VaultState.UNLOCKED
        SecretsAccess.flushPending()

        assertFalse(broker.written.containsKey(token.format()))
    }

    @Test
    fun `the queue is capped rather than unbounded`() {
        SecretsAccess.register(FakeBroker(VaultState.LOCKED))

        repeat(SecretsAccess.PENDING_LIMIT + 20) { i ->
            SecretsAccess.remember(SecretRef("finance", "c$i", "token"), "v$i", "t", AppId.FINANCE)
        }

        assertEquals(SecretsAccess.PENDING_LIMIT, SecretsAccess.pendingCount)
    }

    @Test
    fun `flushing does nothing while the vault is still shut, and keeps the queue`() {
        SecretsAccess.register(FakeBroker(VaultState.LOCKED))
        SecretsAccess.remember(token, "fresh", "t", AppId.FINANCE)

        assertEquals(0, SecretsAccess.flushPending())
        assertEquals(1, SecretsAccess.pendingCount)
    }

    @Test
    fun `read-through hands the app back what the vault kept, and re-caches it`() {
        // The restore. The app's own encrypted store came back empty; the vault came back full.
        val broker = FakeBroker()
        SecretsAccess.register(broker)
        broker.write(token, "survived-the-restore", "Finance — USAA token", AppId.FINANCE)

        var local: String? = null
        val value = ManagedSecrets.readThrough(
            ref = token,
            local = { local },
            rehydrate = { local = it }
        )

        assertEquals("survived-the-restore", value)
        assertEquals("and the next read never touches the vault at all", "survived-the-restore", local)
    }

    @Test
    fun `read-through prefers the local copy and does not consult the vault`() {
        val broker = FakeBroker()
        SecretsAccess.register(broker)
        broker.write(token, "stale-vault-copy", "t", AppId.FINANCE)

        var rehydrated = false
        val value = ManagedSecrets.readThrough(
            ref = token,
            local = { "local-copy" },
            rehydrate = { rehydrated = true }
        )

        assertEquals("local-copy", value)
        assertFalse(rehydrated)
    }

    @Test
    fun `read-through with nothing anywhere is null, not a crash`() {
        SecretsAccess.register(FakeBroker(VaultState.LOCKED))

        assertNull(ManagedSecrets.readThrough(token, local = { null }, rehydrate = { }))
    }

    @Test
    fun `remembering a blank value forgets instead of filing an empty secret`() {
        val broker = FakeBroker()
        SecretsAccess.register(broker)
        ManagedSecrets.remember(token, "abc", "t", AppId.FINANCE)

        ManagedSecrets.remember(token, "   ", "t", AppId.FINANCE)

        assertFalse(broker.written.containsKey(token.format()))
    }

    @Test
    fun `the label is the one the list will show`() {
        assertEquals("Finance — USAA access token", ManagedSecrets.label(AppId.FINANCE, "USAA access token"))
    }
}
