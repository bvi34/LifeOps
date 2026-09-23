package com.operations.securestore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The store every credential in the suite sits in on this phone.
 *
 * The cases that matter are the failures: a file that outlived its key must open empty rather than
 * crash, and must not take any other store's key with it; a value moved between entries must not
 * open as the other entry's; and a file still in the old format must come across without anybody
 * typing a password again.
 */
class KeystorePreferencesTest {

    private fun open(backing: FakePreferences = FakePreferences(), keys: SoftwareKey = SoftwareKey(), legacy: Map<String, String> = emptyMap()) =
        SecureStore.open(backing, "test_store", keys) { legacy }

    @Test
    fun `a value comes back as it went in, and is not in the file as itself`() {
        val backing = FakePreferences()
        val store = open(backing)
        store.edit().putString("token", "ghp_secret").commit()

        assertEquals("ghp_secret", store.getString("token", null))
        val raw = backing.values["token"] as String
        assertTrue(raw.startsWith("v1:"))
        assertFalse(raw.contains("ghp_secret"))
    }

    @Test
    fun `the same value sealed twice looks different`() {
        val backing = FakePreferences()
        val store = open(backing)
        store.edit().putString("a", "same").putString("b", "same").commit()
        assertTrue(backing.values["a"] != backing.values["b"])
    }

    @Test
    fun `a value copied onto another entry does not open as that entry`() {
        val backing = FakePreferences()
        val store = open(backing)
        store.edit().putString("pin", "1234").putString("card", "9999").commit()
        backing.values["card"] = backing.values["pin"]

        assertNull(store.getString("card", null))
        assertFalse("the unreadable entry is dropped", "card" in backing.values)
        assertEquals("1234", store.getString("pin", null))
    }

    @Test
    fun `a value from another file does not open here`() {
        val keys = SoftwareKey()
        val other = FakePreferences()
        SecureStore.open(other, "other_store", keys) { emptyMap() }.edit().putString("token", "x").commit()

        val backing = FakePreferences().apply { values["token"] = other.values["token"] }
        assertNull(SecureStore.open(backing, "test_store", keys) { emptyMap() }.getString("token", null))
    }

    @Test
    fun `a file that outlived its key opens empty instead of failing`() {
        val backing = FakePreferences()
        open(backing).edit().putString("token", "t").commit()

        // A reinstall: the file survives, the Keystore does not.
        val store = open(backing, SoftwareKey())

        assertNull(store.getString("token", null))
        assertTrue(backing.values.isEmpty())
        store.edit().putString("token", "again").commit()
        assertEquals("again", store.getString("token", null))
    }

    @Test
    fun `an existing key is reused, not replaced`() {
        val keys = SoftwareKey()
        val backing = FakePreferences()
        open(backing, keys).edit().putString("token", "t").commit()
        val reopened = open(backing, keys)

        assertEquals("t", reopened.getString("token", null))
        assertEquals(1, keys.created)
    }

    @Test
    fun `all is every readable entry, opened`() {
        val store = open()
        store.edit().putString("user:a", "ada").putString("user:b", "bo").commit()
        assertEquals(mapOf("user:a" to "ada", "user:b" to "bo"), store.all)
    }

    @Test
    fun `null and remove and clear do what they say`() {
        val store = open()
        store.edit().putString("a", "1").putString("b", "2").putString("c", "3").commit()
        store.edit().putString("a", null).remove("b").commit()
        assertEquals(mapOf("c" to "3"), store.all)
        assertTrue(store.contains("c"))
        store.edit().clear().commit()
        assertTrue(store.all.isEmpty())
    }

    @Test(expected = UnsupportedOperationException::class)
    fun `a number is refused rather than stored in the clear`() {
        open().edit().putLong("count", 3)
    }

    @Test
    fun `listeners hear about the store`() {
        val store = open()
        val heard = mutableListOf<String?>()
        store.registerOnSharedPreferenceChangeListener { prefs, key ->
            assertTrue(prefs === store)
            heard += key
        }
        store.edit().putString("token", "t").commit()
        assertEquals(listOf<String?>("token"), heard)
    }

    @Test
    fun `an old encrypted file comes across with its values`() {
        val backing = FakePreferences().apply {
            values["__androidx_security_crypto_encrypted_prefs_key_keyset__"] = "keyset"
            values["__androidx_security_crypto_encrypted_prefs_value_keyset__"] = "keyset"
            values["AUhX…sealed-name"] = "sealed-value"
        }
        val store = open(backing, legacy = mapOf("card" to "4000", "pin" to "1234"))

        assertEquals(mapOf("card" to "4000", "pin" to "1234"), store.all)
        assertTrue("the old keysets are gone", backing.values.keys.none { it.startsWith("__androidx") })
        assertEquals(setOf("card", "pin"), backing.values.keys)
    }

    @Test
    fun `an old file that can't be read starts empty rather than failing`() {
        val backing = FakePreferences().apply {
            values["__androidx_security_crypto_encrypted_prefs_key_keyset__"] = "keyset"
        }
        val store = SecureStore.open(backing, "test_store", SoftwareKey()) { throw javax.crypto.AEADBadTagException() }

        assertTrue(store.all.isEmpty())
        assertTrue(backing.values.isEmpty())
    }

    @Test
    fun `a file already in the new format is not read as an old one`() {
        val keys = SoftwareKey()
        val backing = FakePreferences()
        open(backing, keys).edit().putString("token", "t").commit()
        val reopened = SecureStore.open(backing, "test_store", keys) { error("must not be called") }
        assertEquals("t", reopened.getString("token", null))
    }
}
