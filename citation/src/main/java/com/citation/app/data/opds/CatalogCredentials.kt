package com.citation.app.data.opds

import android.content.Context
import android.content.SharedPreferences
import com.operations.backupkit.AppId
import com.operations.securestore.SecureStore
import com.operations.vaultkit.ManagedSecrets
import com.operations.vaultkit.SecretRef

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
 * The reinstall/restore recovery is the same too: the file can outlive the Keystore key that sealed
 * it, and then it opens empty rather than crashing the app (see `:securestore`) — sign-ins have to
 * be re-entered, but they never left the device and the app opens.
 *
 * ## What the vault changed
 *
 * "Sign-ins have to be re-entered" was the whole cost of keeping them here, and it is now paid by
 * the Secrets vault: every sign-in written here is mirrored there under a [SecretRef], and a read
 * that finds nothing locally falls through to it (see [ManagedSecrets]). The vault is a sealed file
 * whose key is a passphrase rather than a device-bound one, so it travels in the backup and a
 * restored phone gets its catalogue logins back on the first unlock.
 *
 * Nothing about *this* store changed. It is still the working copy, still device-bound, still
 * outside the archive, and on a phone with no vault it behaves exactly as it did before.
 */
class CatalogCredentials(context: Context) {

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences by lazy { openPrefs(appContext) }

    /** A catalog sign-in. Empty username means the server is open. */
    data class Credentials(val username: String, val password: String)

    fun credentials(catalogId: String): Credentials? {
        val user = read(userKey(catalogId), userRef(catalogId)) ?: return null
        val password = read(passwordKey(catalogId), passwordRef(catalogId)).orEmpty()
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
        mirror(userRef(catalogId), user, "catalogue username")
        mirror(passwordRef(catalogId), password, "catalogue password")
    }

    /** Forget one catalog's sign-in — called when the catalog itself is removed. */
    fun clear(catalogId: String) {
        prefs.edit().remove(userKey(catalogId)).remove(passwordKey(catalogId)).apply()
        ManagedSecrets.forget(userRef(catalogId))
        ManagedSecrets.forget(passwordRef(catalogId))
    }

    // --- The vault ------------------------------------------------------------------------------
    //
    // A catalogue's own row id is the connection segment. The username is filed beside the password
    // rather than being treated as public: a private Calibre server's user name is not a secret in
    // the cryptographic sense and is entirely useless to have lost, which is the test that matters.

    private fun userRef(catalogId: String) =
        SecretRef(APP, SecretRef.segment(catalogId), "username")

    private fun passwordRef(catalogId: String) =
        SecretRef(APP, SecretRef.segment(catalogId), "password")

    /**
     * File every sign-in this store holds into the vault, and say how many refs were written.
     *
     * For the rebuild after a lost passphrase — see [com.operations.vaultkit.SecretSource]. These
     * sign-ins are still here; only the vault's copy of them went.
     *
     * [nameFor] turns a catalogue id into its name, so a re-filed row is called "Calibre — home
     * server" rather than a row key. The caller resolves those names first ([catalogIds] says which
     * to look up) because looking a name up is a database read and this is not a suspending call.
     */
    fun refileIntoVault(nameFor: (String) -> String? = { null }): Int {
        var filed = 0
        catalogIds().forEach { catalogId ->
            val stored = credentials(catalogId) ?: return@forEach
            val label = nameFor(catalogId) ?: "Catalogue"
            mirror(userRef(catalogId), stored.username, "$label username")
            filed++
            if (stored.password.isNotEmpty()) {
                mirror(passwordRef(catalogId), stored.password, "$label password")
                filed++
            }
        }
        return filed
    }

    /**
     * Take back from the vault the sign-ins for [catalogIds] that this store is missing, and say how
     * many refs were restored.
     *
     * The ids come from the caller — from the *catalogue rows in the database* — rather than from
     * [catalogIds], and the difference is the entire point on a restored phone: this store is what
     * came up empty, so its own keys would name nothing to ask for. The catalogues themselves
     * travelled in the archive.
     *
     * A password that was genuinely empty (a catalogue that wants only a username) restores as one
     * ref rather than two, which is the same arithmetic [refileIntoVault] uses.
     */
    fun restockFromVault(catalogIds: Collection<String>): Int {
        var restored = 0
        catalogIds.distinct().forEach { catalogId ->
            if (restock(userKey(catalogId), userRef(catalogId))) restored++
            if (restock(passwordKey(catalogId), passwordRef(catalogId))) restored++
        }
        return restored
    }

    /** One slot: filled from the vault only when this store has nothing in it. */
    private fun restock(key: String, ref: SecretRef): Boolean = ManagedSecrets.restock(
        ref = ref,
        local = { prefs.getString(key, null)?.takeIf { it.isNotBlank() } },
        save = { value -> prefs.edit().putString(key, value).apply() }
    )

    /** Every catalogue this store has a sign-in for — what a refill needs names for. */
    fun catalogIds(): List<String> =
        prefs.all.keys.filter { it.startsWith(USER_PREFIX) }.map { it.removePrefix(USER_PREFIX) }

    private fun read(key: String, ref: SecretRef): String? = ManagedSecrets.readThrough(
        ref = ref,
        local = { prefs.getString(key, null)?.takeIf { it.isNotBlank() } },
        rehydrate = { value -> prefs.edit().putString(key, value).apply() }
    )

    private fun mirror(ref: SecretRef, value: String, what: String) {
        ManagedSecrets.remember(ref, value, ManagedSecrets.label(AppId.CITATION, what), AppId.CITATION)
    }

    private fun userKey(id: String) = "$USER_PREFIX$id"
    private fun passwordKey(id: String) = "pass:$id"

    private companion object {
        const val TAG = "CatalogCredentials"

        /** This app's segment in a [SecretRef]; matches [AppId.CITATION]'s key. */
        const val APP = "citation"

        /** The key prefix a stored username wears, and the one a refill enumerates by. */
        const val USER_PREFIX = "user:"

        const val PREFS_NAME = "opds_catalog_access"

        /**
         * Open the store: its own file behind its own Keystore key (see `:securestore`). A file that
         * outlived its key after a reinstall or restore opens empty instead of crashing the app, and a
         * file still in the old EncryptedSharedPreferences format is moved across on the first open.
         */
        fun openPrefs(context: Context): SharedPreferences = SecureStore.open(context, PREFS_NAME)
    }
}
