package com.operations.vaultkit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One row per place.
 *
 * The tests that matter are the ones about *not* grouping: a vault of one-login sites must look
 * exactly as it did, and a starred login must not vanish into a folder halfway down the alphabet
 * because something else shares its address.
 */
class VaultGroupsTest {

    private fun item(
        id: String,
        title: String,
        url: String = "",
        username: String = "",
        favourite: Boolean = false
    ) = VaultItem(
        id = id,
        title = title,
        url = url,
        username = username,
        favourite = favourite,
        createdAt = 1,
        updatedAt = 1
    )

    @Test
    fun `two logins at one site become one row`() {
        val rows = VaultGroups.group(
            listOf(
                item("a", "Bank", "https://bank.example", "me"),
                item("b", "Bank", "https://www.bank.example/login", "someone-else")
            )
        )

        val row = rows.single()
        assertTrue(row.isGroup)
        assertEquals("bank.example", row.site)
        assertEquals(2, row.items.size)
    }

    @Test
    fun `one login at a site is not a folder`() {
        val rows = VaultGroups.group(listOf(item("a", "Bank", "https://bank.example", "me")))

        assertFalse(rows.single().isGroup)
        assertNull(rows.single().site)
    }

    @Test
    fun `an item with no address stays exactly where it was`() {
        val rows = VaultGroups.group(
            listOf(
                item("a", "Safe combination"),
                item("b", "Wifi"),
                item("c", "Bank", "https://bank.example")
            )
        )

        assertEquals(3, rows.size)
        assertTrue(rows.none { it.isGroup })
    }

    @Test
    fun `a group is named after the title its items share`() {
        val rows = VaultGroups.group(
            listOf(
                item("a", "Bank", "https://bank.example", "me"),
                item("b", "Bank", "https://bank.example", "joint")
            )
        )

        assertEquals("Bank", rows.single().label)
        // The address is still shown, because it is what makes them one row.
        assertEquals("bank.example", rows.single().subtitle)
    }

    @Test
    fun `a group whose titles disagree is named after the address`() {
        val rows = VaultGroups.group(
            listOf(
                item("a", "Bank", "https://bank.example", "me"),
                item("b", "Bank savings", "https://bank.example", "me2")
            )
        )

        assertEquals("bank.example", rows.single().label)
        assertNull(rows.single().subtitle)
    }

    @Test
    fun `a group sorts where its best member would have`() {
        val rows = VaultGroups.group(
            listOf(
                item("a", "Aardvark", "https://aardvark.example"),
                item("b", "Zoo", "https://zoo.example", "me", favourite = true),
                item("c", "Zoo", "https://zoo.example", "another")
            )
        )

        // The starred login is inside the Zoo group, so the Zoo group leads — a favourite does not
        // lose its place by having a neighbour.
        assertEquals(listOf("Zoo", "Aardvark"), rows.map { it.label })
        assertTrue(rows.first().favourite)
    }

    @Test
    fun `a deleted item is in no row at all`() {
        val rows = VaultGroups.group(
            listOf(
                item("a", "Bank", "https://bank.example", "me"),
                item("b", "Bank", "https://bank.example", "old").tombstone(2)
            )
        )

        assertFalse(rows.single().isGroup)
        assertEquals(1, VaultGroups.flatten(rows).size)
    }

    @Test
    fun `a subdomain is its own place`() {
        // Deliberately not merged with `bank.example`: autofill treats them as one site for
        // *filling*, but they are two addresses, and a group that swallowed every subdomain would
        // put the whole of google.com in one row.
        val rows = VaultGroups.group(
            listOf(
                item("a", "Bank", "https://bank.example", "me"),
                item("b", "Bank", "https://bank.example", "joint"),
                item("c", "Bank cards", "https://cards.bank.example", "me")
            )
        )

        assertEquals(2, rows.size)
        assertEquals(setOf("bank.example", null), rows.map { it.site }.toSet())
    }

    @Test
    fun `everything flattens back in the order it is drawn`() {
        val rows = VaultGroups.group(
            listOf(
                item("a", "Bank", "https://bank.example", "me"),
                item("b", "Bank", "https://bank.example", "joint"),
                item("c", "Almanac", "https://almanac.example")
            )
        )

        assertEquals(listOf("Almanac", "Bank", "Bank"), VaultGroups.flatten(rows).map { it.title })
    }
}
