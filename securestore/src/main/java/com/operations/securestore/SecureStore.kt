package com.operations.securestore

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Opens a credential store: the app-private preferences file [name], sealed by a Keystore key of its
 * own (alias `securestore.<name>`). See [KeystorePreferences] for what that does and doesn't hide.
 *
 * The file keeps its name, so every backup rule that excludes it by name still does.
 */
object SecureStore {

    private const val TAG = "SecureStore"
    private const val KEY_ALIAS_PREFIX = "securestore."

    /**
     * Opening is serialised: two threads opening a store for the first time would otherwise each
     * make a key, and whatever the first sealed would be unreadable under the second. Opening is a
     * key lookup and a file read, so the lock costs nothing that matters.
     */
    fun open(context: Context, name: String): SharedPreferences = synchronized(this) {
        open(
            backing = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE),
            name = name,
            keys = AndroidKeystoreKey(KEY_ALIAS_PREFIX + name),
            readLegacy = { LegacyStore.read(context.applicationContext, name) }
        )
    }

    /**
     * The same, with its three dependencies handed in — which is what lets the tests run it on the
     * JVM with a software key and a pretend legacy file.
     *
     * A file still in the EncryptedSharedPreferences format is moved across first: its strings are
     * read with the old library, the file is emptied, and they are written back sealed with this
     * store's own key. Nobody re-enters anything. If the old file can't be read — the reason the old
     * stores had a recovery path at all — it is emptied instead, exactly as they would have done,
     * and the stores' vault fall-through puts back what it can.
     */
    internal fun open(
        backing: SharedPreferences,
        name: String,
        keys: KeySource,
        readLegacy: () -> Map<String, String>
    ): SharedPreferences {
        val legacy = if (LegacyStore.isLegacy(backing)) {
            runCatching(readLegacy).getOrElse { failure ->
                Log.w(TAG, "$name: the old encrypted file could not be read; starting it empty", failure)
                emptyMap()
            }.also { backing.edit().clear().commit() }
        } else {
            null
        }
        val store = KeystorePreferences(backing, name, keys)
        if (!legacy.isNullOrEmpty()) {
            store.edit().apply { legacy.forEach { (key, value) -> putString(key, value) } }.commit()
            Log.i(TAG, "$name: moved ${legacy.size} entries to its own key")
        }
        return store
    }
}

/**
 * Reading a file written by EncryptedSharedPreferences, once, on its way to [KeystorePreferences].
 *
 * The master key it uses is androidx.security's default, shared by every old store, so it is left
 * where it is: another store may not have been moved yet. It opens nothing once they all have.
 */
internal object LegacyStore {

    /** The entries EncryptedSharedPreferences keeps its own keysets under. */
    private const val KEYSET_PREFIX = "__androidx_security_crypto_encrypted_prefs_"

    fun isLegacy(file: SharedPreferences): Boolean = file.all.keys.any { it.startsWith(KEYSET_PREFIX) }

    fun read(context: Context, name: String): Map<String, String> {
        val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        val old = EncryptedSharedPreferences.create(
            context,
            name,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        return old.all.mapNotNull { (key, value) -> (value as? String)?.let { key to it } }.toMap()
    }
}
