package com.citation.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.citation.core.oreilly.OreillyLibraryProxy

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
 */
class OreillyAccess(context: Context) {

    private val prefs: SharedPreferences by lazy {
        val key = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context.applicationContext,
            "oreilly_access",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /** The configured library proxy host, defaulting to Mid-Continent Public Library. */
    fun proxyHost(): String =
        prefs.getString(KEY_PROXY, null)?.takeIf { it.isNotBlank() }
            ?: OreillyLibraryProxy.MID_CONTINENT_HOST

    /** The proxy to route deep links through, or null if the host was cleared (direct O'Reilly). */
    fun proxy(): OreillyLibraryProxy? =
        proxyHost().takeIf { it.isNotBlank() }?.let { OreillyLibraryProxy(it) }

    private fun card(): String? = prefs.getString(KEY_CARD, null)?.takeIf { it.isNotBlank() }
    private fun pin(): String? = prefs.getString(KEY_PIN, null)?.takeIf { it.isNotBlank() }

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
    }

    /** Forget the card + PIN (keeps the proxy host — that isn't a secret). */
    fun clearCredentials() {
        prefs.edit().remove(KEY_CARD).remove(KEY_PIN).apply()
    }

    /** A snapshot for the Settings screen — proxy host and whether a card/PIN are on file. */
    data class Config(val proxyHost: String, val hasCredentials: Boolean)

    fun config(): Config = Config(proxyHost(), hasCredentials())

    /** The secret pair, only ever read to fill the library's own sign-in form. */
    data class Credentials(val card: String, val pin: String)

    private companion object {
        const val KEY_PROXY = "proxy_host"
        const val KEY_CARD = "card"
        const val KEY_PIN = "pin"
    }
}
