package com.finance.app.data.secure

import android.content.Context
import android.content.SharedPreferences
import com.finance.app.logic.Endpoints
import com.operations.backupkit.AppId
import com.operations.securestore.SecureStore
import com.operations.vaultkit.ManagedSecrets
import com.operations.vaultkit.SecretRef

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
 * token lives here, and the backup contributor skips this store entirely.
 *
 * ## The vault, and why reconnecting is no longer the cost
 *
 * That reasoning left one thing unpaid for, and this file used to say so: restore onto a new phone
 * and every connection had to be re-authorised, because this store is bound to the old phone's
 * Keystore and does not travel.
 *
 * It travels now, in the one place it can safely: the Secrets vault. Every write here is **mirrored**
 * to `com.operations.vaultkit.SecretsAccess` under a [SecretRef], and every read that finds nothing
 * locally **falls through** to it (see [ManagedSecrets]). The vault is a sealed file whose key is a
 * passphrase in somebody's head rather than anything the archive or the phone holds, so it is in the
 * backup and the token in it is not readable by anyone who has the backup.
 *
 * This store stays exactly what it was — the working copy, device-bound, read on every sync, still
 * excluded from the archive by the naming rule below. What changed is what happens when it comes up
 * empty on a new phone: the vault is asked, and if it is open, the token comes back and is written
 * here. If there is no vault, or it is locked, nothing here behaves differently from the day before
 * this seam existed — the connection needs re-authorising, exactly as it always did.
 *
 * ## Recovery when the key is gone
 *
 * The file can outlive the hardware-bound key that sealed it — a reinstall, a device restore — and
 * then it opens empty rather than crashing the app (see `:securestore`): the connections have to be
 * re-authorised unless the vault gives the tokens back, but they never left the device and the app
 * opens. This is Citation's `CatalogCredentials` bargain, taken for stronger reasons. The key is
 * this file's alone, so recovering here touches no other app's store.
 */
class FinanceSecrets internal constructor(context: Context, private val override: SharedPreferences?) {

    constructor(context: Context) : this(context, null)

    private val appContext = context.applicationContext

    /**
     * [override] is a test seam and nothing else.
     *
     * The sealed store needs `AndroidKeyStore`, which does not exist on the JVM, so
     * every unit test of this class would otherwise die in [openPrefs] before reaching a line worth
     * testing. What the tests need to exercise is the *mirroring* — which refs are used, that a
     * write goes to both stores, that a read falls through to the vault — none of which is about
     * where the local copy is kept. So they supply a plain preferences file and the rest of this
     * class does not know the difference. Production has exactly one constructor and it passes null.
     */
    private val prefs: SharedPreferences by lazy { override ?: openPrefs(appContext) }

    // --- Where each secret is filed in the vault -------------------------------------------------
    //
    // Three app-wide refs and one per connection. The connection's own id is the segment, which is
    // already unique and already in the address alphabet; see [SecretRef.segment] for what happens
    // to anything that is not.

    private val plaidClientRef = SecretRef(APP, SecretRef.SELF, "plaid-client-id")
    private val plaidSecretRef = SecretRef(APP, SecretRef.SELF, "plaid-secret")
    private val plaidEnvRef = SecretRef(APP, SecretRef.SELF, "plaid-environment")

    private fun tokenRef(connectionId: String) =
        SecretRef(APP, SecretRef.segment(connectionId), "access-token")

    /** The household's own Plaid developer credentials, and which environment they belong to. */
    data class PlaidKeys(
        val clientId: String,
        val secret: String,
        val environment: Endpoints.PlaidEnvironment
    )

    var plaidKeys: PlaidKeys?
        get() {
            // Read-through, key by key: on a restored phone all three of these are missing locally
            // and present in the vault, and the first read after an unlock puts them back.
            val clientId = read(KEY_PLAID_CLIENT, plaidClientRef) ?: return null
            val secret = read(KEY_PLAID_SECRET, plaidSecretRef) ?: return null
            val environment = read(KEY_PLAID_ENV, plaidEnvRef)
            return PlaidKeys(
                clientId = clientId,
                secret = secret,
                environment = Endpoints.PlaidEnvironment.fromKey(environment)
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
            if (value == null) {
                ManagedSecrets.forget(plaidClientRef)
                ManagedSecrets.forget(plaidSecretRef)
                ManagedSecrets.forget(plaidEnvRef)
            } else {
                mirror(plaidClientRef, value.clientId.trim(), "Plaid client id")
                mirror(plaidSecretRef, value.secret.trim(), "Plaid client secret")
                // Not a secret, and filed anyway: keys restored without knowing whether they are
                // sandbox or production keys are keys that fail against the wrong host with an
                // error nobody can read.
                mirror(plaidEnvRef, value.environment.key, "Plaid environment")
            }
        }

    val hasPlaidKeys: Boolean get() = plaidKeys != null

    /** The long-lived token for one connection. Plaid calls it an access token; Mercury, an API token. */
    fun token(connectionId: String): String? = read(tokenKey(connectionId), tokenRef(connectionId))

    /**
     * File the token for one connection.
     *
     * [label] is what the household sees if they look in Secrets — the institution's name, if the
     * caller has it by now. It is only used when the item is created: a name changed there is theirs
     * to keep, and a later refresh of the token does not rename it back.
     */
    fun setToken(connectionId: String, token: String?, label: String? = null) {
        val editor = prefs.edit()
        if (token.isNullOrBlank()) editor.remove(tokenKey(connectionId))
        else editor.putString(tokenKey(connectionId), token.trim())
        editor.apply()

        val ref = tokenRef(connectionId)
        if (token.isNullOrBlank()) ManagedSecrets.forget(ref)
        else mirror(ref, token.trim(), "${label ?: "Connection"} access token")
    }

    /**
     * Where the last `/transactions/sync` left off.
     *
     * Kept beside the token rather than on the connection row precisely so the two are forgotten
     * together: a cursor restored next to a token that was not is a cursor that would silently skip
     * everything the new token should have fetched from the beginning.
     *
     * It is the one thing here that is **not** mirrored into the vault, and the asymmetry is
     * deliberate. A token that survives a restore beside a cursor that does not means the first sync
     * on the new phone starts from the beginning — which is slower, and correct, and the repository
     * already de-duplicates what comes back. The other way round is the one that loses data.
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
        ManagedSecrets.forget(tokenRef(connectionId))
    }

    /**
     * Forget every secret this app holds. The Connections screen's "disconnect everything".
     *
     * The vault copies are read out of the local store *before* it is cleared, because a ref can
     * only be forgotten by name and after the clear there is nothing left to name. A vault that is
     * shut takes the forgettings into its pending queue, like any other write.
     */
    fun forgetAll() {
        val connectionIds = prefs.all.keys
            .filter { it.startsWith(TOKEN_PREFIX) }
            .map { it.removePrefix(TOKEN_PREFIX) }
        prefs.edit().clear().apply()
        connectionIds.forEach { ManagedSecrets.forget(tokenRef(it)) }
        ManagedSecrets.forget(plaidClientRef)
        ManagedSecrets.forget(plaidSecretRef)
        ManagedSecrets.forget(plaidEnvRef)
    }

    /**
     * File everything this store holds into the vault, and say how many refs were written.
     *
     * The reverse of the read-through, and it exists for one situation: the vault was lost — a
     * forgotten passphrase, a file that would not parse — and is being built again. These
     * credentials were never the vault's only copy; they are right here, and re-filing them costs
     * nothing and saves the household reconnecting every bank it has.
     *
     * [nameFor] turns a connection id into the institution's name, so a re-filed token is called
     * "USAA access token" rather than a UUID. It is a lambda rather than a repository because this
     * class has never known what a connection *is* and should not start now; the caller
     * ([com.finance.app.FinanceApp]) has the database and does the looking up.
     *
     * Idempotent: re-filing a credential the vault already holds writes the same value back, which
     * the broker treats as no change at all.
     */
    fun refileIntoVault(nameFor: (String) -> String? = { null }): Int {
        var filed = 0

        plaidKeys?.let { keys ->
            mirror(plaidClientRef, keys.clientId, "Plaid client id")
            mirror(plaidSecretRef, keys.secret, "Plaid client secret")
            mirror(plaidEnvRef, keys.environment.key, "Plaid environment")
            filed += 3
        }

        prefs.all.keys
            .filter { it.startsWith(TOKEN_PREFIX) }
            .map { it.removePrefix(TOKEN_PREFIX) }
            .forEach { connectionId ->
                val token = prefs.getString(tokenKey(connectionId), null)?.takeIf { it.isNotBlank() }
                    ?: return@forEach
                val label = nameFor(connectionId) ?: "Connection"
                mirror(tokenRef(connectionId), token, "$label access token")
                filed++
            }

        return filed
    }

    /**
     * Take back from the vault everything this store is missing, for the connections [connectionIds]
     * names, and say how many refs were restored.
     *
     * The mirror of [refileIntoVault], and the one that runs on an ordinary phone rather than after
     * a disaster: this is what a restore needs. The database came back in the archive, so the
     * connections are all here; the tokens did not, because they were behind a key that belongs to
     * a phone the household no longer has.
     *
     * The ids come from the caller — which is to say from the *database* — rather than from this
     * store's own keys, and that is the whole trick. After a restore this store is empty, so
     * enumerating it would find nothing to ask the vault for; the connections are the thing that
     * survived, and they are what says which refs to go looking for.
     *
     * Nothing is overwritten: [ManagedSecrets.restock] fills only an empty slot, so a token
     * refreshed this morning is never replaced by the copy the vault happens to hold.
     */
    fun restockFromVault(connectionIds: Collection<String>): Int {
        var restored = 0

        if (restock(KEY_PLAID_CLIENT, plaidClientRef)) restored++
        if (restock(KEY_PLAID_SECRET, plaidSecretRef)) restored++
        if (restock(KEY_PLAID_ENV, plaidEnvRef)) restored++

        connectionIds.distinct().forEach { connectionId ->
            if (restock(tokenKey(connectionId), tokenRef(connectionId))) restored++
        }
        return restored
    }

    /** One slot: filled from the vault if this store has nothing in it. */
    private fun restock(key: String, ref: SecretRef): Boolean = ManagedSecrets.restock(
        ref = ref,
        local = { prefs.getString(key, null)?.takeIf { it.isNotBlank() } },
        save = { value -> prefs.edit().putString(key, value).apply() }
    )

    /**
     * One value: this store if it has it, the vault if it does not, and back into this store if the
     * vault was the one that had it.
     *
     * The re-cache is what makes a restored phone cost one vault read rather than one per sync.
     */
    private fun read(key: String, ref: SecretRef): String? = ManagedSecrets.readThrough(
        ref = ref,
        local = { prefs.getString(key, null)?.takeIf { it.isNotBlank() } },
        rehydrate = { value -> prefs.edit().putString(key, value).apply() }
    )

    private fun mirror(ref: SecretRef, value: String, what: String) {
        ManagedSecrets.remember(ref, value, ManagedSecrets.label(AppId.FINANCE, what), AppId.FINANCE)
    }

    private fun tokenKey(connectionId: String) = "$TOKEN_PREFIX$connectionId"
    private fun cursorKey(connectionId: String) = "cursor:$connectionId"

    private companion object {
        const val TAG = "FinanceSecrets"

        /** This app's segment in a [SecretRef]. Matches [AppId.FINANCE]'s key, and is asserted to. */
        const val APP = "finance"

        const val TOKEN_PREFIX = "token:"

        /**
         * Deliberately *not* prefixed `finance_`.
         *
         * The backup contributor collects this app's preference files by matching that prefix, and
         * this is the one file that must never be collected. Naming it out of the pattern means the
         * exclusion is a property of the name rather than of a filter somebody could relax later.
         */
        const val PREFS_NAME = "secure_finance_access"

        const val KEY_PLAID_CLIENT = "plaid_client_id"
        const val KEY_PLAID_SECRET = "plaid_secret"
        const val KEY_PLAID_ENV = "plaid_environment"

        /**
         * Open the store: its own file behind its own Keystore key (see `:securestore`). A file that
         * outlived its key after a reinstall or restore opens empty instead of crashing the app, and a
         * file still in the old EncryptedSharedPreferences format is moved across on the first open.
         */
        fun openPrefs(context: Context): SharedPreferences = SecureStore.open(context, PREFS_NAME)
    }
}
