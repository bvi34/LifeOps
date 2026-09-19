package com.secrets.app.ui.importer

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.operations.vaultkit.SecretSources
import com.operations.vaultkit.SecretsAccess
import com.operations.vaultkit.VaultImport
import com.secrets.app.data.VaultFileStore
import com.secrets.app.data.VaultStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The import, from a picked file to a saved vault.
 *
 * The parsing and the plan are decided in `:vaultkit` and tested there without a device. What is
 * left for this side is the seam nobody can reason about: a `content://`-shaped read, a vault that
 * may be shut by the time the file has been read, and the promise that nothing is written until
 * somebody has ticked something.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportViewModelTest {

    private lateinit var context: Context
    private lateinit var store: VaultStore

    private val passphrase get() = "a passphrase nobody guesses".toCharArray()

    private val chromeCsv = """
        name,url,username,password,note
        Bank,https://bank.example/login,me@example.com,hunter2,
        Shop,https://shop.example/,shopper,s3cret,
    """.trimIndent()

    @Before
    fun setUp() {
        // The view model launches into `viewModelScope`, whose work then hops to the IO dispatcher
        // for the file read and the save. Virtual time cannot follow it across that hop, so the
        // main dispatcher is unconfined here and the tests wait for the state to settle instead.
        Dispatchers.setMain(Dispatchers.Unconfined)
        context = ApplicationProvider.getApplicationContext()
        VaultFileStore(context).deleteAll()
        store = VaultStore(context)
        SecretsAccess.reset()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        SecretsAccess.reset()
        SecretSources.reset()
    }

    /**
     * Wait for the state the test is about.
     *
     * A real wait rather than a virtual one: what is being waited for is an IO thread doing a file
     * read and a save, and the point of these tests is that both are real.
     */
    private inline fun <reified T : ImportViewModel.State> ImportViewModel.await(
        timeoutMillis: Long = 10_000
    ): T {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            val current = state.value
            if (current is T) return current
            Thread.sleep(10)
        }
        throw AssertionError("Stuck at ${state.value}, waiting for ${T::class.simpleName}")
    }

    private fun file(name: String, contents: String): Uri {
        val file = File(context.cacheDir, name)
        file.writeText(contents)
        return Uri.fromFile(file)
    }

    @Test
    fun `a browser export is read, reviewed, and only then written`() = runBlocking {
        store.create(passphrase)
        val vm = ImportViewModel(store)

        vm.open(context.contentResolver, file("passwords.csv", chromeCsv))

        val reviewing = vm.await<ImportViewModel.State.Reviewing>()
        assertEquals(VaultImport.Format.CHROMIUM_CSV, reviewing.plan.format)
        assertEquals(2, reviewing.plan.newCount)
        // Read, planned, and nothing written: the vault is still as empty as it was.
        assertTrue(store.document.value!!.live.isEmpty())

        vm.confirm()

        val done = vm.await<ImportViewModel.State.Done>()
        assertEquals(2, done.added)
        assertEquals(0, done.updated)
        assertEquals(
            setOf("Bank", "Shop"),
            store.document.value!!.live.map { it.title }.toSet()
        )
    }

    @Test
    fun `what is imported is in the file, not only in memory`() = runBlocking {
        store.create(passphrase)
        val vm = ImportViewModel(store)
        vm.open(context.contentResolver, file("passwords.csv", chromeCsv))
        vm.await<ImportViewModel.State.Reviewing>()
        vm.confirm()
        vm.await<ImportViewModel.State.Done>()

        store.lock()
        assertEquals(VaultStore.UnlockResult.UNLOCKED, store.unlock(passphrase))
        assertEquals(2, store.document.value!!.live.size)
    }

    @Test
    fun `unticking everything leaves the vault alone`() = runBlocking {
        store.create(passphrase)
        val vm = ImportViewModel(store)
        vm.open(context.contentResolver, file("passwords.csv", chromeCsv))
        vm.await<ImportViewModel.State.Reviewing>()

        vm.setAll(VaultImport.Verdict.NEW, false)
        vm.confirm()

        assertEquals(0, vm.await<ImportViewModel.State.Done>().added)
        assertTrue(store.document.value!!.live.isEmpty())
    }

    @Test
    fun `the wrong file is refused with a sentence rather than an empty list`() = runBlocking {
        store.create(passphrase)
        val vm = ImportViewModel(store)

        val statement = file("statement.csv", "date,payee,amount\n2026-01-01,Shop,12.40\n")
        vm.open(context.contentResolver, statement)

        val failed = vm.await<ImportViewModel.State.Failed>()
        assertTrue(failed.reason, failed.reason.contains("password"))
    }

    @Test
    fun `a vault that shut while the file was being read does not lose the passwords quietly`() =
        runBlocking {
            store.create(passphrase)
            val vm = ImportViewModel(store)
            store.lock()

            vm.open(context.contentResolver, file("passwords.csv", chromeCsv))

            val failed = vm.await<ImportViewModel.State.Failed>()
            assertTrue(failed.reason, failed.reason.contains("vault"))
        }
}
