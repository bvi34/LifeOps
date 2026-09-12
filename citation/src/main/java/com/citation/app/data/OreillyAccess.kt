package com.citation.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.citation.core.oreilly.OreillyLibraryProxy
import com.operations.backupkit.AppId
import com.operations.vaultkit.ManagedSecrets
import com.operations.vaultkit.SecretRef
import java.security.KeyStore

/**
 * Encrypted, on-device store for your **library O'Reilly access** — the EZproxy host plus your
 * library card and PIN. Backed by the Android Keystore through EncryptedSharedPreferences, so the
 * card/PIN are encrypted at rest and never leave the device. This matches the rest of Citation:
 * single-user, offline-first, and **never synced** (the sync seam carries notes and intents, never
 * secrets).
 *
 * The proxy host is *not* a secret and defaults to Mid-Continent Public Library; the card and PIN are
 * yours alone and are only ever read back to fill the library's own sign-in form via
 * [com.citation.core.oreilly.EzproxyLogin].
 *
 * The card and PIN are also **mirrored into the Secrets vault** and read back through it when this
 * store comes up empty — which on a new phone is always, because this store is bound to the old
 * phone's Keystore and cannot travel. The vault can: it is sealed with a passphrase rather than with
 * the hardware, so it rides in the backup and a restored install asks for the master passphrase
 * instead of for a library card that is in a drawer somewhere. Where there is no vault, nothing here
 * behaves differently from before.
 */
class OreillyAccess(context: Context) {

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences by lazy { openPrefs(appContext) }

    /** The configured library proxy host, defaulting to Mid-Continent Public Library. */
    fun proxyHost(): String =
        prefs.getString(KEY_PROXY, null)?.takeIf { it.isNotBlank() }
            ?: OreillyLibraryProxy.MID_CONTINENT_HOST

    /** The proxy to route deep links through, or null if the host was cleared (direct O'Reilly). */
    fun proxy(): OreillyLibraryProxy? =
        proxyHost().takeIf { it.isNotBlank() }?.let { OreillyLibraryProxy(it) }

    private fun card(): String? = read(KEY_CARD, cardRef)
    private fun pin(): String? = read(KEY_PIN, pinRef)

    /** Card + PIN if both are stored, else null — the pair the auto-reauth script needs. */
    fun credentials(): Credentials? {
        val c = card() ?: return null
        val p = pin() ?: return null
        return Credentials(c, p)
    }

    /** True if a card + PIN are stored (for Settings to show state without exposing the PIN). */
    fun hasCredentials(): Boolean = credentials() != null

    fun setProxyHost(host: String) {
        prefs.edit().putString(KEY_PROXY, host.trim()).apply()
    }

    fun setCredentials(card: String, pin: String) {
        prefs.edit().putString(KEY_CARD, card.trim()).putString(KEY_PIN, pin).apply()
        mirror(cardRef, card.trim(), "library card")
        mirror(pinRef, pin, "library card PIN")
    }

    /** Forget the card + PIN (keeps the proxy host — that isn't a secret). */
    fun clearCredentials() {
        prefs.edit().remove(KEY_CARD).remove(KEY_PIN).apply()
        ManagedSecrets.forget(cardRef)
        ManagedSecrets.forget(pinRef)
    }

    // --- The vault ------------------------------------------------------------------------------
    //
    // One library, so the connection segment is `oreilly` rather than a row id. The proxy host stays
    // out of it: it is a preference with a sensible default, it is not a credential, and a restored
    // install that reaches the wrong library says so immediately.

    private val cardRef = SecretRef(APP, "oreilly", "library-card")
    private val pinRef = SecretRef(APP, "oreilly", "library-pin")

    /**
     * File the card and PIN into the vault, and say how many refs were written (0 or 2).
     *
     * For the rebuild after a lost passphrase. The card is in a wallet and the PIN is in somebody's
     * head — but they are also right here, and asking for them again when this store has them would
     * be a poor use of an afternoon.
     */
    fun refileIntoVault(): Int {
        val stored = credentials() ?: return 0
        mirror(cardRef, stored.card, "library card")
        mirror(pinRef, stored.pin, "library card PIN")
        return 2
    }

    /**
     * Take the card and PIN back out of the vault when this store has neither, and say how many refs
     * were restored (0 or 2).
     *
     * The mirror of [refileIntoVault] and the one a restore walks: on a new phone this store is
     * empty because its key belonged to the old one, and the vault came across in the archive. Run
     * on every unlock, so the ordinary answer is 0 and costs two preference reads.
     */
    fun restockFromVault(): Int {
        var restored = 0
        if (restock(KEY_CARD, cardRef)) restored++
        if (restock(KEY_PIN, pinRef)) restored++
        return restored
    }

    /** One slot: filled from the vault only when this store has nothing in it. */
    private fun restock(key: String, ref: SecretRef): Boolean = ManagedSecrets.restock(
        ref = ref,
        local = { prefs.getString(key, null)?.takeIf { it.isNotBlank() } },
        save = { value -> prefs.edit().putString(key, value).apply() }
    )

    private fun read(key: String, ref: SecretRef): String? = ManagedSecrets.readThrough(
        ref = ref,
        local = { prefs.getString(key, null)?.takeIf { it.isNotBlank() } },
        rehydrate = { value -> prefs.edit().putString(key, value).apply() }
    )

    private fun mirror(ref: SecretRef, value: String, what: String) {
        ManagedSecrets.remember(ref, value, ManagedSecrets.label(AppId.CITATION, what), AppId.CITATION)
    }

    /** A snapshot for the Settings screen — proxy host and whether a card/PIN are on file. */
    data class Config(val proxyHost: String, val hasCredentials: Boolean)

    fun config(): Config = Config(proxyHost(), hasCredentials())

    /** The secret pair, only ever read to fill the library's own sign-in form. */
    data class Credentials(val card: String, val pin: String)

    private companion object {
        const val TAG = "OreillyAccess"

        /** This app's segment in a [SecretRef]; matches [AppId.CITATION]'s key. */
        const val APP = "citation"

        const val PREFS_NAME = "oreilly_access"
        const val KEY_PROXY = "proxy_host"
        const val KEY_CARD = "card"
        const val KEY_PIN = "pin"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"

        /**
         * Open the encrypted store, recovering from the classic reinstall/restore failure instead
         * of taking the whole app down on launch.
         *
         * The `oreilly_access` prefs file holds a Tink keyset wrapped by a hardware-bound Android
         * Keystore master key. That master key is **never** part of a backup and is regenerated on
         * reinstall, but the encrypted file itself can be restored by system auto-backup or left
         * behind on disk. When the surviving keyset is then decrypted with a fresh, non-matching
         * key, Tink throws [javax.crypto.AEADBadTagException] straight out of
         * [EncryptedSharedPreferences.create] — the fatal crash seen after a reinstall.
         *
         * The ciphertext is unrecoverable without the original key, so on that failure we drop the
         * unreadable keyset (and the possibly-mismatched master key) and rebuild the store empty.
         * The card/PIN have to be re-entered, but they never left the device anyway and the app
         * opens. The proxy host isn't a secret and falls back to its default.
         */
        fun openPrefs(context: Context): SharedPreferences =
            runCatching { buildPrefs(context) }.getOrElse { failure ->
                Log.w(TAG, "Encrypted store unreadable (likely reinstall/restore); rebuilding it", failure)
                wipeCorruptStore(context)
                // A second failure here is a genuine, unexpected problem — let it surface.
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

        /**
         * Discard the undecryptable store so [buildPrefs] can mint a fresh keyset. Clearing through
         * [Context.getSharedPreferences] also evicts the in-process cache entry the failed
         * `create` left behind (a raw file delete alone would be shadowed by that cache), and we
         * drop the Keystore master key in case the key entry itself is the mismatched party.
         */
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
