package com.operations.securestore

import android.content.SharedPreferences
import android.util.Log
import javax.crypto.SecretKey

/**
 * A [SharedPreferences] whose string values are sealed with a key that belongs to this file alone.
 *
 * It stands in, unchanged, wherever an `EncryptedSharedPreferences` stood: the stores that use it
 * read and write strings through the ordinary interface and never see a cipher. Strings are all any
 * of them keep — a token, a PIN, a wrapped key — so strings are all this takes; the other `put…`s
 * throw rather than quietly store a number in the clear.
 *
 * ## What is and isn't hidden
 *
 * Values are sealed ([SealedStrings]). Entry **names** are not — `plaid_access_token_3f2a…`, `pin` —
 * where EncryptedSharedPreferences sealed those too. The trade is deliberate: the stores enumerate
 * their own entries (a refill from the vault walks `all`), the names say what kind of thing is kept
 * but never the thing, and the file is app-private and excluded from every backup. A name alone
 * opens nothing.
 *
 * ## When it can't be read
 *
 * The file can outlive its key — a reinstall regenerates the Keystore, a restore brings an old file
 * back. Where the old stores threw out of `create` and then deleted the suite's shared master key
 * on the way to recovering, this one notices on open (entries, and no key), clears **only this
 * file**, and carries on empty; the stores' vault fall-through puts back whatever it can. An entry
 * that won't open on its own reads as absent and is removed.
 */
class KeystorePreferences(
    private val backing: SharedPreferences,
    private val file: String,
    private val keys: KeySource
) : SharedPreferences {

    private val key: SecretKey

    init {
        val existing = keys.existing()
        key = if (existing != null) {
            existing
        } else {
            if (backing.all.isNotEmpty()) {
                Log.w(TAG, "$file outlived its key (reinstall or restore); starting it empty")
                backing.edit().clear().commit()
            }
            keys.create()
        }
    }

    override fun getString(name: String, defValue: String?): String? {
        val sealed = backing.getString(name, null) ?: return defValue
        return SealedStrings.open(key, file, name, sealed) ?: run {
            Log.w(TAG, "An entry in $file could not be opened; dropping it")
            backing.edit().remove(name).apply()
            defValue
        }
    }

    override fun getAll(): Map<String, *> =
        backing.all.keys.mapNotNull { name -> getString(name, null)?.let { name to it } }.toMap()

    override fun contains(name: String): Boolean = getString(name, null) != null

    override fun getStringSet(name: String, defValues: MutableSet<String>?): MutableSet<String>? =
        unsupported("getStringSet")

    override fun getInt(name: String, defValue: Int): Int = unsupported("getInt")
    override fun getLong(name: String, defValue: Long): Long = unsupported("getLong")
    override fun getFloat(name: String, defValue: Float): Float = unsupported("getFloat")
    override fun getBoolean(name: String, defValue: Boolean): Boolean = unsupported("getBoolean")

    override fun edit(): SharedPreferences.Editor = Editor(backing.edit())

    private val listeners =
        mutableMapOf<SharedPreferences.OnSharedPreferenceChangeListener, SharedPreferences.OnSharedPreferenceChangeListener>()

    /** Listeners hear about this store, not the file under it. */
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        val relay = SharedPreferences.OnSharedPreferenceChangeListener { _, name -> listener.onSharedPreferenceChanged(this, name) }
        synchronized(listeners) { listeners[listener] = relay }
        backing.registerOnSharedPreferenceChangeListener(relay)
    }

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        val relay = synchronized(listeners) { listeners.remove(listener) } ?: return
        backing.unregisterOnSharedPreferenceChangeListener(relay)
    }

    private inner class Editor(private val inner: SharedPreferences.Editor) : SharedPreferences.Editor {

        override fun putString(name: String, value: String?): SharedPreferences.Editor = apply {
            if (value == null) inner.remove(name) else inner.putString(name, SealedStrings.seal(key, file, name, value))
        }

        override fun remove(name: String): SharedPreferences.Editor = apply { inner.remove(name) }
        override fun clear(): SharedPreferences.Editor = apply { inner.clear() }
        override fun commit(): Boolean = inner.commit()
        override fun apply() = inner.apply()

        override fun putStringSet(name: String, values: MutableSet<String>?): SharedPreferences.Editor =
            unsupported("putStringSet")

        override fun putInt(name: String, value: Int): SharedPreferences.Editor = unsupported("putInt")
        override fun putLong(name: String, value: Long): SharedPreferences.Editor = unsupported("putLong")
        override fun putFloat(name: String, value: Float): SharedPreferences.Editor = unsupported("putFloat")
        override fun putBoolean(name: String, value: Boolean): SharedPreferences.Editor = unsupported("putBoolean")
    }

    private fun unsupported(what: String): Nothing =
        throw UnsupportedOperationException("$file keeps strings only; $what is not sealed")

    private companion object {
        const val TAG = "SecureStore"
    }
}
