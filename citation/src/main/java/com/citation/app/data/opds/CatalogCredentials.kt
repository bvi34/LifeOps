package com.citation.app.data.opds

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyStore

/**
 * Encrypted, on-device store for catalog sign-ins — the username and password a private OPDS server
 * (a Calibre content server, a Calibre-Web instance) wants.
 *
 * Kept out of the database on purpose. A `citation.db` travels: it is copied by the sandbox backup,
 * and it is the file a restore swaps in wholesale. A database that carried server passwords would
 * make every backup a credential leak, so the row in `opds_catalogs` holds only the address and the
 * secret lives here, behind the Android Keystore — the same bargain the O'Reilly library card
 * already makes, and like it, **never synced**.
 *
 * The reinstall/restore recovery is the same too: the encrypted file can outlive the hardware-bound
 * master key that wrapped it, and decrypting the survivor with a fresh key throws out of
 * `EncryptedSharedPreferences.create`. Rather than a permanent boot crash, the unreadable keyset is
 * dropped and the store rebuilt empty — sign-ins have to be re-entered, but they never left the
 * device and the app opens.
 */
class CatalogCredentials(context: Context) {

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences by lazy { openPrefs(appContext) }

    /** A catalog sign-in. Empty username means the server is open. */
    data class Credentials(val username: String, val password: String)

    fun credentials(catalogId: String): Credentials? {
        val user = prefs.getString(userKey(catalogId), null)?.takeIf { it.isNotBlank() } ?: return null
        val password = prefs.getString(passwordKey(catalogId), null).orEmpty()
        return Credentials(user, password)
    }

    fun hasCredentials(catalogId: String): Boolean = credentials(catalogId) != null

    fun setCredentials(catalogId: String, username: String, password: String) {
        val user = username.trim()
        if (user.isBlank()) {
            clear(catalogId)
            return
        }
        prefs.edit()
            .putString(userKey(catalogId), user)
            .putString(passwordKey(catalogId), password)
            .apply()
    }

    /** Forget one catalog's sign-in — called when the catalog itself is removed. */
    fun clear(catalogId: String) {
        prefs.edit().remove(userKey(catalogId)).remove(passwordKey(catalogId)).apply()
    }

    private fun userKey(id: String) = "user:$id"
    private fun passwordKey(id: String) = "pass:$id"

    private companion object {
        const val TAG = "CatalogCredentials"
        const val PREFS_NAME = "opds_catalog_access"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"

        fun openPrefs(context: Context): SharedPreferences =
            runCatching { buildPrefs(context) }.getOrElse { failure ->
                Log.w(TAG, "Encrypted store unreadable (likely reinstall/restore); rebuilding it", failure)
                wipeCorruptStore(context)
                buildPrefs(context)
            }

        fun buildPrefs(context: Context): SharedPreferences {
            val key = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }

        fun wipeCorruptStore(context: Context) {
            runCatching {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
            }
            runCatching {
                val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                if (ks.containsAlias(MasterKey.DEFAULT_MASTER_KEY_ALIAS)) {
                    ks.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
                }
            }
        }
    }
}
