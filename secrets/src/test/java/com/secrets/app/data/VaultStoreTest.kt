package com.secrets.app.data

import androidx.test.core.app.ApplicationProvider
import com.operations.backupkit.AppId
import com.operations.vaultkit.ManagedSecrets
import com.operations.vaultkit.SecretOwner
import com.operations.vaultkit.SecretRef
import com.operations.vaultkit.SecretSource
import com.operations.vaultkit.SecretSources
import com.operations.vaultkit.PasskeyRequest
import com.operations.vaultkit.Passkeys
import com.operations.vaultkit.SecretsAccess
import com.operations.vaultkit.Totp
import com.operations.vaultkit.TotpConfig
import com.operations.vaultkit.VaultDocument
import com.operations.vaultkit.VaultEnvelope
import com.operations.vaultkit.VaultItem
import com.operations.vaultkit.VaultItemKind
import com.operations.vaultkit.VaultState
import com.operations.vaultkit.WebAuthn
import com.secrets.app.broker.VaultBroker
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.After
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
 * The store, on the JVM.
 *
 * These are the parts that cannot be tested by reasoning about them: what is on disk after a save,
 * what a locked vault will and will not answer, and — the test this whole app was asked for — what a
 * credential does when the phone it was typed into is gone.
 *
 * [android.content.Context] is Robolectric's, which gives a real `filesDir` and a real (in-memory)
 * keystore, so the file store and the broker run exactly as they do on a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class VaultStoreTest {

    private lateinit var store: VaultStore

    private val passphrase get() = "a passphrase nobody guesses".toCharArray()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        // Each test starts from an empty vault directory; Robolectric hands out a fresh data dir per
        // test class, not per test, so this is the reset.
        VaultFileStore(context).deleteAll()
        store = VaultStore(context)
        SecretsAccess.reset()
    }

    @After
    fun tearDown() {
        SecretsAccess.reset()
        SecretSources.reset()
    }

    /**
     * An app that still holds its own credentials, which is the whole premise of a reset: the vault
     * lost them, the app did not.
     */
    private class FakeApp(
        override val owner: SecretOwner,
        /** The refs this app knows it should have — its rows, which travel in the archive. */
        private val holds: Map<SecretRef, String>,
        /** Its own encrypted store: full on the old phone, empty on the new one. */
        val store: MutableMap<SecretRef, String> = holds.toMutableMap()
    ) : SecretSource {

        override suspend fun refile(): Int {
            store.forEach { (ref, value) ->
                SecretsAccess.remember(ref, value, "${owner.displayName} — refiled", owner)
            }
            return store.size
        }

        /** The other direction: fill the empty slots from the vault, and never the full ones. */
        override suspend fun rehydrate(): Int = holds.keys.count { ref ->
            ManagedSecrets.restock(ref, local = { store[ref] }, save = { store[ref] = it })
        }
    }

    @Test
    fun `a new install has no vault, and making one opens it`() = runTest {
        assertEquals(VaultState.ABSENT, store.state.value)
        assertFalse(store.exists)

        assertTrue(store.create(passphrase))

        assertEquals(VaultState.UNLOCKED, store.state.value)
        assertTrue(store.exists)
        assertNotNull(store.document.value)
    }

    @Test
    fun `a vault cannot be made twice over the top of itself`() = runTest {
        assertTrue(store.create(passphrase))

        assertFalse("a second create would destroy the first vault", store.create("another".toCharArray()))
    }

    @Test
    fun `locking drops the document, and the right passphrase brings it back`() = runTest {
        store.create(passphrase)
        store.mutate { it.upsert(item("Bank", "hunter2"), 10) }

        store.lock()
        assertEquals(VaultState.LOCKED, store.state.value)
        assertNull(store.document.value)

        assertEquals(VaultStore.UnlockResult.WRONG_PASSPHRASE, store.unlock("nope".toCharArray()))
        assertEquals(VaultStore.UnlockResult.UNLOCKED, store.unlock(passphrase))
        assertEquals("hunter2", store.document.value?.live?.single()?.secret)
    }

    @Test
    fun `a saved vault survives being reopened by a fresh store - the process restart`() = runTest {
        store.create(passphrase)
        store.mutate { it.upsert(item("Bank", "hunter2"), 10) }

        val second = VaultStore(ApplicationProvider.getApplicationContext())
        assertEquals("a new process finds a locked vault, never an open one", VaultState.LOCKED, second.state.value)
        assertEquals(VaultStore.UnlockResult.UNLOCKED, second.unlock(passphrase))
        assertEquals("hunter2", second.document.value?.live?.single()?.secret)
    }

    @Test
    fun `nothing readable is left on disk`() = runTest {
        store.create(passphrase)
        store.mutate { it.upsert(item("Allotment gate", "elderflower-4417"), 10) }

        val bytes = VaultFileStore(ApplicationProvider.getApplicationContext()).read()!!
        val asText = String(bytes, Charsets.ISO_8859_1)

        assertFalse("the secret is in the file in plain text", asText.contains("elderflower-4417"))
        assertFalse("so is the title", asText.contains("Allotment gate"))
        assertTrue(VaultEnvelope.looksLikeVault(bytes))
    }

    @Test
    fun `a locked vault refuses to be written to`() = runTest {
        store.create(passphrase)
        store.lock()

        assertFalse(store.mutateBlocking { it.upsert(item("Bank", "x"), 10) })
    }

    @Test
    fun `changing the passphrase retires the old one and keeps the contents`() = runTest {
        store.create(passphrase)
        store.mutate { it.upsert(item("Bank", "hunter2"), 10) }

        assertTrue(store.changePassphrase(passphrase, "something else entirely".toCharArray()))
        store.lock()

        assertEquals(VaultStore.UnlockResult.WRONG_PASSPHRASE, store.unlock(passphrase))
        assertEquals(VaultStore.UnlockResult.UNLOCKED, store.unlock("something else entirely".toCharArray()))
        assertEquals("hunter2", store.document.value?.live?.single()?.secret)
    }

    @Test
    fun `changing the passphrase needs the current one`() = runTest {
        store.create(passphrase)

        assertFalse(store.changePassphrase("wrong".toCharArray(), "new passphrase here".toCharArray()))
    }

    @Test
    fun `the auto-lock fires on elapsed time and not before`() = runTest {
        store.create(passphrase)
        val now = System.currentTimeMillis()

        assertFalse(store.shouldAutoLock(now, timeoutMillis = 60_000))
        assertTrue(store.shouldAutoLock(now + 61_000, timeoutMillis = 60_000))
        assertFalse("zero means never", store.shouldAutoLock(now + 10_000_000, timeoutMillis = 0))

        store.lock()
        assertFalse("a shut vault has nothing to lock", store.shouldAutoLock(now + 61_000, 60_000))
    }

    @Test
    fun `destroying leaves nothing behind`() = runTest {
        store.create(passphrase)
        store.mutate { it.upsert(item("Bank", "hunter2"), 10) }

        assertTrue(store.destroy())

        assertEquals(VaultState.ABSENT, store.state.value)
        assertFalse(store.exists)
        assertNull(VaultFileStore(ApplicationProvider.getApplicationContext()).read())
    }

    // --- The broker, which is what the other apps see ---------------------------------------------

    @Test
    fun `an app files a credential and reads it back`() = runTest {
        store.create(passphrase)
        SecretsAccess.register(VaultBroker(store))
        val ref = SecretRef("finance", "usaa", "access-token")

        assertTrue(SecretsAccess.remember(ref, "access-abc", "Finance — USAA token", SecretOwner.of(AppId.FINANCE)))

        assertEquals("access-abc", SecretsAccess.read(ref))
        val item = store.document.value!!.managed(ref)!!
        assertEquals("Finance — USAA token", item.title)
        assertEquals("finance", item.managedBy)
    }

    @Test
    fun `a locked vault tells an app nothing, and keeps the write for later`() = runTest {
        store.create(passphrase)
        SecretsAccess.register(VaultBroker(store))
        val ref = SecretRef("finance", "usaa", "access-token")
        SecretsAccess.remember(ref, "access-abc", "t", SecretOwner.of(AppId.FINANCE))

        store.lock()
        assertNull(SecretsAccess.read(ref))
        assertFalse(SecretsAccess.remember(ref, "access-def", "t", SecretOwner.of(AppId.FINANCE)))
        assertEquals(1, SecretsAccess.pendingCount)

        store.unlock(passphrase)
        // Unlocking flushes the queue — see VaultStore.unlock, which is where that happens.
        assertEquals("access-def", SecretsAccess.read(ref))
        assertEquals(0, SecretsAccess.pendingCount)
    }

    @Test
    fun `a rename in the vault survives the app writing the credential again`() = runTest {
        store.create(passphrase)
        val broker = VaultBroker(store)
        val ref = SecretRef("finance", "usaa", "access-token")
        broker.write(ref, "one", "Finance — USAA token", SecretOwner.of(AppId.FINANCE))
        val id = store.document.value!!.managed(ref)!!.id
        store.mutate { document ->
            document.upsert(document.item(id)!!.copy(title = "The joint account"), 20)
        }

        broker.write(ref, "two", "Finance — USAA token", SecretOwner.of(AppId.FINANCE))

        val item = store.document.value!!.managed(ref)!!
        assertEquals("two", item.secret)
        assertEquals("the value is the app's; the name is the household's", "The joint account", item.title)
    }

    @Test
    fun `forgetting a credential tombstones it rather than dropping the row`() = runTest {
        store.create(passphrase)
        val broker = VaultBroker(store)
        val ref = SecretRef("citation", "calibre", "password")
        broker.write(ref, "abc", "Citation — Calibre", SecretOwner.of(AppId.CITATION))

        assertTrue(broker.forget(ref))

        assertNull(broker.read(ref))
        assertTrue(store.document.value!!.items.single().isDeleted)
    }

    /**
     * The whole point of the app, in one test.
     *
     * A phone with Finance connected and a vault. Take the backup. Get a new phone: the app's own
     * encrypted store is empty (it never travels), the vault is restored. Read the token.
     */
    @Test
    fun `a credential read through the vault survives a phone that no longer exists`() = runTest {
        store.create(passphrase)
        SecretsAccess.register(VaultBroker(store))
        val ref = SecretRef("finance", "usaa", "access-token")

        // The old phone: Finance writes its token to its own store *and* mirrors it here.
        var financeLocalStore: String? = "access-abc"
        ManagedSecrets.remember(ref, financeLocalStore!!, "Finance — USAA token", AppId.FINANCE)

        // The new phone: the vault file came back in the archive; Finance's keystore store did not.
        val vaultBytes = VaultFileStore(ApplicationProvider.getApplicationContext()).read()!!
        val newPhone = VaultStore(ApplicationProvider.getApplicationContext())
        VaultFileStore(ApplicationProvider.getApplicationContext()).write(vaultBytes)
        newPhone.refreshState()
        SecretsAccess.register(VaultBroker(newPhone))
        financeLocalStore = null

        // Before the vault is opened, Finance is exactly where it would have been without this app.
        assertNull(ManagedSecrets.readThrough(ref, { financeLocalStore }, { financeLocalStore = it }))

        newPhone.unlock(passphrase)

        assertEquals(
            "access-abc",
            ManagedSecrets.readThrough(ref, { financeLocalStore }, { financeLocalStore = it })
        )
        assertEquals("and it is back in Finance's own store", "access-abc", financeLocalStore)
    }

    @Test
    fun `unlocking hands every app back the credentials its own store lost`() = runTest {
        // The push, rather than the pull above. Same new phone: the rows came back in the archive
        // and every app's credential store is empty, because it was behind a key the old phone
        // owned. Nobody opens Finance, nobody opens Citation — somebody types their passphrase.
        val token = SecretRef("finance", "usaa", "access-token")
        val card = SecretRef("citation", "oreilly", "library-card")
        store.create(passphrase)
        SecretsAccess.register(VaultBroker(store))
        SecretsAccess.remember(token, "access-abc", "Finance — USAA", SecretOwner.of(AppId.FINANCE))
        SecretsAccess.remember(card, "31234-5678", "Citation — library card", SecretOwner.of(AppId.CITATION))
        store.lock()

        val finance = FakeApp(SecretOwner.of(AppId.FINANCE), mapOf(token to ""), store = mutableMapOf())
        val citation = FakeApp(SecretOwner.of(AppId.CITATION), mapOf(card to ""), store = mutableMapOf())
        SecretSources.register(finance)
        SecretSources.register(citation)

        assertEquals(VaultStore.UnlockResult.UNLOCKED, store.unlock(passphrase))

        assertEquals("access-abc", finance.store[token])
        assertEquals("31234-5678", citation.store[card])
    }

    @Test
    fun `an unlock never writes over a credential an app still has`() = runTest {
        // The working copy is always at least as new as the vault's: a token refreshed this morning
        // and mirrored while the vault was shut must not be replaced by yesterday's on unlock.
        val token = SecretRef("finance", "usaa", "access-token")
        store.create(passphrase)
        SecretsAccess.register(VaultBroker(store))
        SecretsAccess.remember(token, "yesterdays", "Finance — USAA", SecretOwner.of(AppId.FINANCE))
        store.lock()

        val finance = FakeApp(
            SecretOwner.of(AppId.FINANCE),
            mapOf(token to ""),
            store = mutableMapOf(token to "refreshed-this-morning")
        )
        SecretSources.register(finance)

        store.unlock(passphrase)

        assertEquals("refreshed-this-morning", finance.store[token])
    }

    @Test
    fun `making a vault also offers the apps whatever it already holds`() = runTest {
        // A household that makes a vault for the first time on a phone that has one restored from
        // somewhere else is rare; a household that makes one and has nothing waiting is the norm.
        // Either way `create` opens the vault, so it takes the same round as an unlock.
        val finance = FakeApp(SecretOwner.of(AppId.FINANCE), emptyMap(), store = mutableMapOf())
        SecretSources.register(finance)

        assertTrue(store.create(passphrase))

        assertTrue(finance.store.isEmpty())
    }

    // --- The forgotten passphrase --------------------------------------------------------------

    @Test
    fun `a reset replaces the vault and refills it from the apps that still hold their own`() = runTest {
        val token = SecretRef("finance", "usaa", "access-token")
        val card = SecretRef("citation", "oreilly", "library-card")
        store.create(passphrase)
        SecretsAccess.register(VaultBroker(store))
        SecretsAccess.remember(token, "access-abc", "Finance — USAA", SecretOwner.of(AppId.FINANCE))
        // And something only a person could have put there.
        store.mutate { it.upsert(item("Allotment gate", "elderflower"), 10) }
        SecretSources.register(FakeApp(SecretOwner.of(AppId.FINANCE), mapOf(token to "access-abc")))
        SecretSources.register(FakeApp(SecretOwner.of(AppId.CITATION), mapOf(card to "31234-5678")))

        val refill = store.resetForgottenPassphrase("a completely new passphrase".toCharArray())

        assertNotNull(refill)
        assertEquals(2, refill!!.filed)
        assertEquals(VaultState.UNLOCKED, store.state.value)
        // The managed credentials are back...
        assertEquals("access-abc", SecretsAccess.read(token))
        assertEquals("31234-5678", SecretsAccess.read(card))
        // ...and what somebody typed in is not, because the vault was the only place it was.
        assertTrue(
            "a reset cannot recover what only the old vault held",
            store.document.value!!.live.none { it.title == "Allotment gate" }
        )
    }

    @Test
    fun `after a reset only the new passphrase opens the vault`() = runTest {
        store.create(passphrase)

        store.resetForgottenPassphrase("a completely new passphrase".toCharArray())
        store.lock()

        assertEquals(VaultStore.UnlockResult.WRONG_PASSPHRASE, store.unlock(passphrase))
        assertEquals(
            VaultStore.UnlockResult.UNLOCKED,
            store.unlock("a completely new passphrase".toCharArray())
        )
    }

    @Test
    fun `a reset on a phone whose apps are also empty reports nothing rather than a success`() = runTest {
        store.create(passphrase)

        val refill = store.resetForgottenPassphrase("a completely new passphrase".toCharArray())

        assertNotNull(refill)
        assertTrue(refill!!.empty)
        assertEquals(VaultState.UNLOCKED, store.state.value)
        assertTrue(store.document.value!!.live.isEmpty())
    }

    @Test
    fun `a reset works with no vault at all - the corrupt file case`() = runTest {
        // Nothing was ever created, or what was there could not be parsed and was thrown away. The
        // reset is the same operation either way: delete whatever is there, build, refill.
        SecretSources.register(FakeApp(SecretOwner.of(AppId.FINANCE), mapOf(SecretRef("finance", "usaa", "access-token") to "abc")))

        val refill = store.resetForgottenPassphrase("a completely new passphrase".toCharArray())

        assertNotNull(refill)
        assertEquals(1, refill!!.filed)
        assertEquals(VaultState.UNLOCKED, store.state.value)
    }

    @Test
    fun `a reset drops the device shortcut with the vault it belonged to`() = runTest {
        store.create(passphrase)

        store.resetForgottenPassphrase("a completely new passphrase".toCharArray())

        // Robolectric has no secure lock screen, so the shortcut can never have been on here — what
        // this asserts is the invariant that matters either way: nothing is left claiming to open a
        // vault that no longer exists.
        assertFalse(store.device.isEnabled)
    }

    private fun item(title: String, secret: String) = VaultItem(
        id = UUID.randomUUID().toString(),
        title = title,
        secret = secret,
        createdAt = 1,
        updatedAt = 1
    )

    // --- Second factors and previous passwords, through the real file ----------------------------

    @Test
    fun `a second factor and a replaced password survive the seal and the reopen`() = runTest {
        store.create(passphrase)
        val seed = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
        val id = UUID.randomUUID().toString()
        store.mutate { document ->
            document.upsert(
                VaultItem(
                    id = id,
                    title = "Bank",
                    secret = "the-old-one",
                    totp = TotpConfig(secret = seed, issuer = "Bank", account = "me"),
                    createdAt = 1,
                    updatedAt = 1
                ),
                now = 10
            )
        }
        store.mutate { document -> document.upsert(document.item(id)!!.copy(secret = "the-new-one"), now = 20) }

        store.lock()
        val reopened = VaultStore(ApplicationProvider.getApplicationContext())
        assertEquals(VaultStore.UnlockResult.UNLOCKED, reopened.unlock(passphrase))

        val item = reopened.document.value!!.item(id)!!
        assertEquals("the-new-one", item.secret)
        assertEquals("the-old-one", item.history.single().secret)
        assertEquals("Bank", item.totp!!.issuer)
        // The seed is the one that was filed, which is the only thing that makes the codes right.
        assertEquals(
            Totp.code(TotpConfig(secret = seed), 59_000L)!!.digits,
            Totp.code(item.totp!!, 59_000L)!!.digits
        )
    }

    @Test
    fun `a rotated credential leaves no trail of dead tokens in the vault`() = runTest {
        store.create(passphrase)
        val broker = VaultBroker(store)
        val ref = SecretRef("finance", "usaa", "access-token")

        broker.write(ref, "token-one", "Finance — USAA token", SecretOwner.of(AppId.FINANCE))
        broker.write(ref, "token-two", "Finance — USAA token", SecretOwner.of(AppId.FINANCE))
        broker.write(ref, "token-three", "Finance — USAA token", SecretOwner.of(AppId.FINANCE))

        val item = store.document.value!!.managed(ref)!!
        assertEquals("token-three", item.secret)
        // These rotate on somebody else's schedule and none of the old ones opens anything, so a
        // history of them would be an unbounded pile of plaintext with no use for it.
        assertTrue(item.history.isEmpty())
    }

    @Test
    fun `the document version follows what the vault actually holds`() = runTest {
        store.create(passphrase)
        val id = UUID.randomUUID().toString()

        store.mutate { it.upsert(VaultItem(id = id, title = "Wifi", secret = "elderflower"), now = 10) }
        assertEquals(
            "nothing here a version-1 reader would drop",
            VaultDocument.BASELINE_VERSION,
            store.document.value!!.version
        )

        store.mutate { document ->
            document.upsert(
                document.item(id)!!.copy(totp = TotpConfig(secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ")),
                now = 20
            )
        }
        // Version 2, not the newest: a vault with a second factor and no passkey is still writable
        // by the build that introduced second factors.
        assertEquals(VaultDocument.VERSION_WITH_SECOND_FACTORS, store.document.value!!.version)

        // And the file itself carries it, rather than only the copy in memory.
        store.lock()
        val reopened = VaultStore(ApplicationProvider.getApplicationContext())
        reopened.unlock(passphrase)
        assertEquals(VaultDocument.VERSION_WITH_SECOND_FACTORS, reopened.document.value!!.version)
    }

    @Test
    fun `a passkey survives the seal and still signs on the phone that replaced this one`() = runTest {
        store.create(passphrase)
        val options = PasskeyRequest.parseCreation(
            """{"rp":{"id":"bank.com","name":"The Bank"},
                 "user":{"id":"dXNlcg","name":"me@example.com","displayName":"Me"},
                 "challenge":"Y2hhbGxlbmdl",
                 "pubKeyCredParams":[{"type":"public-key","alg":-7}]}"""
        )!!
        val madeOn = Passkeys.register(
            options = options,
            clientDataJson = "{}",
            clientDataHash = ByteArray(32),
            now = 1_000L
        )!!
        val id = UUID.randomUUID().toString()
        store.mutate {
            it.upsert(
                VaultItem(
                    id = id,
                    kind = VaultItemKind.PASSKEY,
                    title = "The Bank",
                    url = "bank.com",
                    passkey = madeOn.passkey,
                    createdAt = 1,
                    updatedAt = 1
                ),
                now = 10
            )
        }
        assertEquals(VaultDocument.DOCUMENT_VERSION, store.document.value!!.version)

        // The phone is gone; the sealed file is what came back.
        store.lock()
        val newPhone = VaultStore(ApplicationProvider.getApplicationContext())
        assertEquals(VaultStore.UnlockResult.UNLOCKED, newPhone.unlock(passphrase))
        val restored = newPhone.document.value!!.item(id)!!.passkey!!

        // The credential still signs, and the signature still verifies against the public key the
        // site was handed on the phone that no longer exists. That is the whole claim.
        val clientDataHash = WebAuthn.sha256("client-data".toByteArray())
        val assertionJson = Passkeys.assertion(restored, null, clientDataHash)!!
        // org.json rather than Gson: Gson is :vaultkit's own implementation dependency and does
        // not leak onto this module's test classpath, which is the arrangement working as intended.
        val inner = org.json.JSONObject(assertionJson).getJSONObject("response")
        val authData = WebAuthn.fromBase64Url(inner.getString("authenticatorData"))!!
        val signature = WebAuthn.fromBase64Url(inner.getString("signature"))!!

        val verifier = java.security.Signature.getInstance("SHA256withECDSA").apply {
            initVerify(Passkeys.publicKeyOf(madeOn.passkey))
            update(authData)
            update(clientDataHash)
        }
        assertTrue("a passkey made on the old phone still signs on the new one", verifier.verify(signature))
    }
}
