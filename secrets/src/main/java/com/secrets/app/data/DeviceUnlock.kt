package com.secrets.app.data

import android.app.KeyguardManager
import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.operations.vaultkit.VaultCrypto
import java.security.KeyStore

/**
 * The fingerprint shortcut: the vault key, wrapped by the Android Keystore, so that opening the app
 * for the fourth time today does not mean typing thirty characters again.
 *
 * ## Why this is not a contradiction
 *
 * The argument for this whole module is that a key bound to one phone's hardware dies with that
 * phone, and that is exactly what this file uses. The distinction that makes it coherent — and it is
 * the single most important idea in the app — is between a **root of trust** and a **shortcut**:
 *
 *  - The passphrase is the root. It is the only thing that opens the archived vault, it is not on
 *    the phone, and losing the phone does not lose it.
 *  - This is a shortcut to a vault the household already has on *this* phone. It stores the vault
 *    key behind a Keystore-wrapped file, it never leaves the device, it is excluded from the backup,
 *    and it can be revoked, expire or die with the hardware without costing anybody anything: they
 *    type the passphrase and carry on.
 *
 * Which is to say the Keystore is used here for precisely what it is good at — protecting something
 * on a device that still exists — and nowhere near the thing that has to survive the device.
 *
 * ## The gate
 *
 * Storing a key that unlocks everything behind nothing at all would be a downgrade dressed as a
 * feature, so:
 *
 *  - it is **opt-in**, from the settings screen, while the vault is already open;
 *  - it is refused outright on a phone with no lock screen ([canOffer]), because on such a phone
 *    "the device unlocked it" means "somebody picked it up";
 *  - and the caller must satisfy the device credential — fingerprint, PIN, pattern — immediately
 *    before [vaultKey] is read. That prompt is the platform's, raised by the unlock screen; this
 *    class holds the key and states the requirement, and the one place that calls it is the one
 *    place that has just come back from the lock screen with a success.
 *
 * ## What is stored
 *
 * The 32 raw bytes of the vault key, Base64'd into an `EncryptedSharedPreferences` file whose name
 * — `secure_secrets_device` — deliberately does not begin with `secrets`. That prefix is what the
 * backup contributor collects, so this file cannot be swept into an archive even by a later change
 * that relaxes a filter: the exclusion is a property of the name. It is the same trick
 * `FinanceSecrets` uses, for the same reason, and here it protects the one file whose escape would
 * turn the archive from safe into fatal.
 */
class DeviceUnlock(context: Context) {

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences by lazy { openPrefs(appContext) }

    private val keyguard: KeyguardManager? =
        appContext.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager

    /**
     * Can the shortcut be offered at all?
     *
     * Only on a phone with a lock screen. On one without, the device credential prompt cannot be
     * raised, so the key would be readable by anyone holding the handset — which is a worse position
     * than the app was in before the setting existed.
     */
    val canOffer: Boolean get() = keyguard?.isDeviceSecure == true

    val isEnabled: Boolean get() = canOffer && prefs.contains(KEY_VAULT_KEY)

    /** Store [vaultKey] for the shortcut. Called only from an unlocked vault. */
    fun enable(vaultKey: ByteArray): Boolean {
        if (!canOffer) return false
        return try {
            prefs.edit()
                .putString(KEY_VAULT_KEY, Base64.encodeToString(vaultKey, Base64.NO_WRAP))
                .commit()
        } catch (e: Exception) {
            Log.w(TAG, "Could not store the device key", e)
            false
        }
    }

    /**
     * The stored vault key, or null.
     *
     * **Call this only immediately after the device credential has been satisfied.** Nothing in this
     * class can enforce that — the Keystore key here is not itself auth-bound, because tying it to
     * user authentication on API 26 means a time-window scheme that behaves differently on every
     * vendor's implementation — so the requirement is a rule about the one call site rather than a
     * property of the store. That call site is `ui/unlock/UnlockScreen`, and it is the launcher
     * result of `createConfirmDeviceCredentialIntent`.
     */
    fun vaultKey(): ByteArray? {
        if (!canOffer) return null
        val encoded = prefs.getString(KEY_VAULT_KEY, null) ?: return null
        return try {
            Base64.decode(encoded, Base64.NO_WRAP).takeIf { it.size == VaultCrypto.KEY_BYTES }
        } catch (e: Exception) {
            Log.w(TAG, "Stored device key is unreadable; dropping it", e)
            disable()
            null
        }
    }

    /** Forget the shortcut. Called when it is turned off, when it goes stale, and on destroy. */
    fun disable() {
        runCatching { prefs.edit().clear().commit() }
    }

    private companion object {
        const val TAG = "DeviceUnlock"

        /**
         * Deliberately *not* prefixed `secrets_`.
         *
         * `SecretsBackupContributor` collects this app's preference files by matching that prefix,
         * and this is the one file that must never be collected — it holds the vault key in a form
         * the phone can unwrap by itself, which is the exact opposite of the property that makes the
         * vault safe to back up.
         */
        const val PREFS_NAME = "secure_secrets_device"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"

        const val KEY_VAULT_KEY = "vault_key"

        fun openPrefs(context: Context): SharedPreferences =
            runCatching { buildPrefs(context) }.getOrElse { failure ->
                // The same recovery Finance and Citation take: the encrypted file can outlive the
                // hardware-bound master key that wrapped it, and here the consequence is only that
                // the shortcut is gone. The vault itself is untouched — it was never protected by
                // this key — so the household types their passphrase and turns the shortcut back on.
                Log.w(TAG, "Device key store unreadable (likely reinstall/restore); rebuilding it", failure)
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

        /**
         * Drop the unreadable keyset and the master key that failed to open it.
         *
         * The master key alias is androidx.security's default, which every app in the suite that
         * keeps an encrypted store shares. Deleting it therefore affects Finance's and Citation's
         * stores too — and that reads worse than it is: this path only runs when the key can no
         * longer open a file it wrote, which means those stores are already unreadable for the same
         * reason. They rebuild themselves empty exactly as they do today, and the credentials they
         * lose are now the ones the vault gives back on the next read (see
         * `com.operations.vaultkit.ManagedSecrets`).
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
