package com.secrets.app.ui.items

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.operations.vaultkit.SecretsAccess
import com.operations.vaultkit.VaultItem
import com.secrets.app.data.SecretsPrefs
import com.secrets.app.data.VaultFileStore
import com.secrets.app.data.VaultStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The list, once a vault has more than one login at a site.
 *
 * The grouping itself is decided in `:vaultkit` and tested there. What is left here is the part
 * that only exists on screen: that a search flattens the list rather than hiding a match inside a
 * shut folder, and that a folder nobody can see any more is not still recorded as open.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ItemsViewModelTest {

    private lateinit var store: VaultStore
    private lateinit var prefs: SecretsPrefs

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        VaultFileStore(context).deleteAll()
        store = VaultStore(context)
        prefs = SecretsPrefs(context)
        SecretsAccess.reset()
        runBlocking { store.create("a passphrase nobody guesses".toCharArray()) }
    }

    @After
    fun tearDown() {
        SecretsAccess.reset()
    }

    private fun fill(vararg items: VaultItem) {
        store.mutateBlocking { document ->
            items.fold(document) { acc, item -> acc.upsert(item, 1_000L) }
        }
    }

    private fun item(id: String, title: String, url: String, username: String) =
        VaultItem(id = id, title = title, url = url, username = username, createdAt = 1, updatedAt = 1)

    private fun viewModel() = ItemsViewModel(store, prefs).also { it.refresh() }

    @Test
    fun `a site with several logins is one row until it is opened`() {
        fill(
            item("a", "Bank", "https://bank.example", "me"),
            item("b", "Bank", "https://www.bank.example/login", "joint"),
            item("c", "Almanac", "https://almanac.example", "me")
        )

        val vm = viewModel()
        val state = vm.state.value

        assertEquals(2, state.rows.size)
        assertTrue(state.rows.any { it.isGroup && it.items.size == 2 })
        // Shut: nothing is expanded until somebody asks.
        assertTrue(state.expanded.isEmpty())

        vm.onToggleSite("bank.example")
        assertTrue("bank.example" in vm.state.value.expanded)
        vm.onToggleSite("bank.example")
        assertFalse("bank.example" in vm.state.value.expanded)
    }

    @Test
    fun `searching flattens the list, so a match is never inside a shut folder`() {
        fill(
            item("a", "Bank", "https://bank.example", "me"),
            item("b", "Bank", "https://bank.example", "joint")
        )

        val vm = viewModel()
        vm.onQuery("joint")

        val rows = vm.state.value.rows
        assertEquals(1, rows.size)
        assertFalse(rows.single().isGroup)
        assertEquals("joint", rows.single().lead.username)
    }

    @Test
    fun `a site that leaves the list stops being an open folder`() {
        fill(
            item("a", "Bank", "https://bank.example", "me"),
            item("b", "Bank", "https://bank.example", "joint")
        )

        val vm = viewModel()
        vm.onToggleSite("bank.example")
        assertTrue("bank.example" in vm.state.value.expanded)

        // Filtered away — *From apps* holds none of these.
        vm.onFilter(ItemsViewModel.Filter.MANAGED)

        assertTrue(vm.state.value.expanded.isEmpty())
    }

    @Test
    fun `one login at a site is a plain row, not a folder of one`() {
        fill(item("a", "Bank", "https://bank.example", "me"))

        assertFalse(viewModel().state.value.rows.single().isGroup)
    }
}
