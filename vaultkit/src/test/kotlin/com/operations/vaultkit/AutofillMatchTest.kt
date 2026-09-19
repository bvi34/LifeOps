package com.operations.vaultkit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mostly a test of what autofill *refuses*, because that is where the damage is.
 *
 * A service that fills the wrong form has typed a bank password into whatever asked for one, and
 * unlike every other bug in this suite the household would have no way of noticing.
 */
class AutofillMatchTest {

    private fun login(
        id: String,
        title: String = id,
        url: String = "",
        username: String = "me",
        secret: String = "p",
        favourite: Boolean = false
    ) = VaultItem(
        id = id,
        title = title,
        url = url,
        username = username,
        secret = secret,
        favourite = favourite,
        createdAt = 1,
        updatedAt = 1
    )

    private fun titles(
        items: List<VaultItem>,
        packageName: String? = null,
        webDomain: String? = null
    ) = AutofillMatch.candidates(items, packageName, webDomain).map { it.item.title }

    // --- What it will not do ---------------------------------------------------------------------

    @Test
    fun `a lookalike domain gets nothing`() {
        val bank = login("a", title = "Bank", url = "https://bank.com/login")

        // The suffix test is on a label boundary, which is the whole difference between these two.
        assertEquals(emptyList<String>(), titles(listOf(bank), webDomain = "bank.com.evil.example"))
        assertEquals(emptyList<String>(), titles(listOf(bank), webDomain = "notbank.com"))
        assertEquals(emptyList<String>(), titles(listOf(bank), webDomain = "bank.com.co"))
        assertEquals(emptyList<String>(), titles(listOf(bank), webDomain = "evil.example"))
    }

    @Test
    fun `a mirrored credential is never offered to anything`() {
        val token = VaultItem(
            id = "m",
            title = "Finance — USAA access token",
            secret = "token",
            url = "https://usaa.com",
            ref = "finance/usaa/access-token",
            managedBy = "finance",
            createdAt = 1,
            updatedAt = 1
        )

        // Even though its address matches perfectly. No sign-in page wants a Plaid token, and the
        // only thing filling one in could achieve is handing a bank token to a form.
        assertEquals(emptyList<String>(), titles(listOf(token), webDomain = "usaa.com"))
        assertEquals(emptyList<String>(), titles(listOf(token), packageName = "com.usaa.mobile"))
    }

    @Test
    fun `an item with no address is never offered, however well its title reads`() {
        val guessable = login("a", title = "Bank", url = "")

        assertEquals(emptyList<String>(), titles(listOf(guessable), webDomain = "bank.com"))
        assertEquals(emptyList<String>(), titles(listOf(guessable), packageName = "com.bank.app"))
    }

    @Test
    fun `an asker that names nothing gets nothing`() {
        val bank = login("a", url = "https://bank.com")

        assertEquals(emptyList<String>(), titles(listOf(bank)))
        assertEquals(emptyList<String>(), titles(listOf(bank), packageName = "", webDomain = ""))
    }

    @Test
    fun `a tombstone and an empty item are not candidates`() {
        val deleted = login("a", url = "https://bank.com").copy(deletedAt = 5)
        val hollow = login("b", title = "Stub", url = "https://bank.com", username = "", secret = "")

        assertEquals(emptyList<String>(), titles(listOf(deleted, hollow), webDomain = "bank.com"))
    }

    @Test
    fun `a browser is not filled from an item filed under the browser`() {
        // Chrome names itself as the package and the page as the domain. Matching on the package
        // here would fill every site in the world from one row.
        val chrome = login("a", title = "Chrome", url = "https://chrome.google.com")
        val bank = login("b", title = "Bank", url = "https://bank.com")

        assertEquals(
            listOf("Bank"),
            titles(listOf(chrome, bank), packageName = "com.android.chrome", webDomain = "bank.com")
        )
    }

    // --- What it will do -------------------------------------------------------------------------

    @Test
    fun `an exact domain is offered, and www is not a different site`() {
        val bank = login("a", title = "Bank", url = "https://www.bank.com/login?next=1")

        assertEquals(listOf("Bank"), titles(listOf(bank), webDomain = "bank.com"))
        assertEquals(listOf("Bank"), titles(listOf(bank), webDomain = "https://www.bank.com/"))
    }

    @Test
    fun `a subdomain and its parent are one site, in both directions`() {
        val bare = login("a", title = "Bare", url = "bank.com")
        val sub = login("b", title = "Sub", url = "login.bank.com")

        assertTrue("Sub" in titles(listOf(bare, sub), webDomain = "login.bank.com"))
        assertTrue("Bare" in titles(listOf(bare, sub), webDomain = "login.bank.com"))
        assertTrue("Sub" in titles(listOf(bare, sub), webDomain = "bank.com"))
    }

    @Test
    fun `an exact domain outranks a parent, and a favourite outranks a name`() {
        val exact = login("a", title = "Zebra", url = "login.bank.com")
        val parent = login("b", title = "Aardvark", url = "bank.com")
        val alsoExact = login("c", title = "Zzz", url = "login.bank.com", favourite = true)

        assertEquals(
            listOf("Zzz", "Zebra", "Aardvark"),
            titles(listOf(exact, parent, alsoExact), webDomain = "login.bank.com")
        )
    }

    @Test
    fun `an app is matched by its package read backwards`() {
        val monzo = login("a", title = "Monzo", url = "https://monzo.com")

        assertEquals(listOf("Monzo"), titles(listOf(monzo), packageName = "com.monzo.app"))
        assertEquals(listOf("Monzo"), titles(listOf(monzo), packageName = "com.monzo"))
    }

    @Test
    fun `the package match is by label and not by string prefix`() {
        // `com.monzonian.app` starts with the characters of `com.monzo` and is a different company.
        assertTrue(AutofillMatch.matchesPackage("monzo.com", "com.monzo.app"))
        assertFalse(AutofillMatch.matchesPackage("monzo.com", "com.monzonian.app"))
        assertFalse(AutofillMatch.matchesPackage("monzo.com", "com.evil"))
        assertFalse(AutofillMatch.matchesPackage("monzo.com", "com"))
    }

    @Test
    fun `a package match ranks below every domain match`() {
        val byPackage = login("a", title = "ByPackage", url = "bank.com")

        val ranked = AutofillMatch.candidates(listOf(byPackage), packageName = "com.bank.app")

        assertEquals(AutofillMatch.Strength.PACKAGE, ranked.single().strength)
    }

    // --- Reading an address ----------------------------------------------------------------------

    @Test
    fun `a host is pulled out of whatever shape the address was saved in`() {
        assertEquals("bank.com", AutofillMatch.hostOf("https://www.bank.com/login#form"))
        assertEquals("bank.com", AutofillMatch.hostOf("BANK.COM"))
        assertEquals("bank.com", AutofillMatch.hostOf("http://me@bank.com:8443/x"))
        assertEquals("login.bank.com", AutofillMatch.hostOf("login.bank.com"))
    }

    @Test
    fun `a note about where you use a password is not an address`() {
        assertNull(AutofillMatch.hostOf("the one at work"))
        assertNull(AutofillMatch.hostOf("localhost"))
        assertNull(AutofillMatch.hostOf(""))
        assertNull(AutofillMatch.hostOf(null))
        assertNull(AutofillMatch.hostOf("   "))
    }

    @Test
    fun `the subdomain test is on label boundaries`() {
        assertTrue(AutofillMatch.isSubdomainOf("login.bank.com", "bank.com"))
        assertFalse(AutofillMatch.isSubdomainOf("bank.com", "bank.com"))
        assertFalse(AutofillMatch.isSubdomainOf("evilbank.com", "bank.com"))
        assertFalse(AutofillMatch.isSubdomainOf("bank.com.evil.example", "bank.com"))
    }
}
