package com.finance.app.data.secure

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.finance.app.logic.Endpoints
import java.security.KeyStore

/**
 * Every string in this app that could read a bank account, kept behind the Android Keystore.
 *
 * Four kinds of secret live here and nowhere else:
 *
 *  - the **Plaid client id and secret**, which are the household's own developer credentials;
 *  - one **Plaid access token per connection**, each of which can read that institution forever;
 *  - one **Mercury API token per connection**;
 *  - the **transactions cursor** per connection, which is not a secret but is meaningless without
 *    the token it belongs to and would be actively harmful to restore beside a token that is gone.
 *
 * ## Why not in the database
 *
 * `finance.db` travels. The sandbox backup copies it into a zip that ends up in Google Drive or on
 * somebody's laptop, and a restore swaps it in wholesale. A database carrying access tokens would
 * make every backup a credential leak of the worst kind — not a password that can be changed, but a
 * standing read grant on a bank account that the household would have no way of knowing had
 * escaped. So the row in `connections` holds the institution's *name* and nothing else, and the
 * token lives here. The backup contributor writes the database and skips this store entirely, which
 * means restoring onto a new phone requires reconnecting. That is the intended cost and it is
 * cheap: reconnecting is two minutes, and the alternative is a zip file that owns your money.
 *
 * ## Recovery when the key is gone
 *
 * The encrypted file can outlive the hardware-bound master key that wrapped it — a reinstall, a
 * device restore — and decrypting the survivor with a fresh key throws out of
 * [EncryptedSharedPreferences.create]. Rather than a permanent boot crash, the unreadable keyset is
 * dropped and the store rebuilt empty: the connections have to be re-authorised, but they never
 * left the device and the app opens. This is Citation's `CatalogCredentials` bargain, taken for
 * stronger reasons.
 */
class FinanceSecrets(context: Context) {

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences by lazy { openPrefs(appContext) }

    /** The household's own Plaid developer credentials, and which environment they belong to. */
    data class PlaidKeys(
        val clientId: String,
        val secret: String,
        val environment: Endpoints.PlaidEnvironment
    )

    var plaidKeys: PlaidKeys?
        get() {
            val clientId = prefs.getString(KEY_PLAID_CLIENT, null)?.takeIf { it.isNotBlank() } ?: return null
            val secret = prefs.getString(KEY_PLAID_SECRET, null)?.takeIf { it.isNotBlank() } ?: return null
            return PlaidKeys(
                clientId = clientId,
                secret = secret,
                environment = Endpoints.PlaidEnvironment.fromKey(prefs.getString(KEY_PLAID_ENV, null))
            )
        }
        set(value) {
            val editor = prefs.edit()
            if (value == null) {
                editor.remove(KEY_PLAID_CLIENT).remove(KEY_PLAID_SECRET).remove(KEY_PLAID_ENV)
            } else {
                editor.putString(KEY_PLAID_CLIENT, value.clientId.trim())
                    .putString(KEY_PLAID_SECRET, value.secret.trim())
                    .putString(KEY_PLAID_ENV, value.environment.key)
            }
            editor.apply()
        }

    val hasPlaidKeys: Boolean get() = plaidKeys != null

    /** The long-lived token for one connection. Plaid calls it an access token; Mercury, an API token. */
    fun token(connectionId: String): String? =
        prefs.getString(tokenKey(connectionId), null)?.takeIf { it.isNotBlank() }

    fun setToken(connectionId: String, token: String?) {
        val editor = prefs.edit()
        if (token.isNullOrBlank()) editor.remove(tokenKey(connectionId))
        else editor.putString(tokenKey(connectionId), token.trim())
        editor.apply()
    }

    /**
     * Where the last `/transactions/sync` left off.
     *
     * Kept beside the token rather than on the connection row precisely so the two are forgotten
     * together: a cursor restored next to a token that was not is a cursor that would silently skip
     * everything the new token should have fetched from the beginning.
     */
    fun cursor(connectionId: String): String? =
        prefs.getString(cursorKey(connectionId), null)?.takeIf { it.isNotBlank() }

    fun setCursor(connectionId: String, cursor: String?) {
        val editor = prefs.edit()
        if (cursor.isNullOrBlank()) editor.remove(cursorKey(connectionId))
        else editor.putString(cursorKey(connectionId), cursor)
        editor.apply()
    }

    /** Forget everything about one connection — called when the connection itself is removed. */
    fun forget(connectionId: String) {
        prefs.edit().remove(tokenKey(connectionId)).remove(cursorKey(connectionId)).apply()
    }

    /** Forget every secret this app holds. The Connections screen's "disconnect everything". */
    fun forgetAll() {
        prefs.edit().clear().apply()
    }

    private fun tokenKey(connectionId: String) = "token:$connectionId"
    private fun cursorKey(connectionId: String) = "cursor:$connectionId"

    private companion object {
        const val TAG = "FinanceSecrets"

        /**
         * Deliberately *not* prefixed `finance_`.
         *
         * The backup contributor collects this app's preference files by matching that prefix, and
         * this is the one file that must never be collected. Naming it out of the pattern means the
         * exclusion is a property of the name rather than of a filter somebody could relax later.
         */
        const val PREFS_NAME = "secure_finance_access"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"

        const val KEY_PLAID_CLIENT = "plaid_client_id"
        const val KEY_PLAID_SECRET = "plaid_secret"
        const val KEY_PLAID_ENV = "plaid_environment"

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
