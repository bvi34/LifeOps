package com.operations.sandbox.update

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.operations.sandbox.update.logic.UpdateSchedule
import com.operations.vaultkit.ManagedSecrets
import com.operations.vaultkit.SecretOwner
import com.operations.vaultkit.SecretRef

/**
 * The two things the updater has to remember between launches.
 *
 * Plain SharedPreferences rather than the suite's Room databases on purpose: this belongs to the
 * container, not to any hosted app, so it is deliberately outside everything the backup archive
 * collects. Restoring a year-old backup onto a new phone should not also restore a stale "we last
 * checked on Tuesday".
 */
class UpdatePrefs internal constructor(context: Context, private val override: SharedPreferences?) {

    constructor(context: Context) : this(context, null)

    private val app = context.applicationContext

    private val prefs = app.getSharedPreferences("sandbox_updates", Context.MODE_PRIVATE)

    /**
     * The access token lives apart from the settings, and encrypted.
     *
     * Separate file because it is the one thing here worth protecting, and encrypted because a
     * GitHub token is a credential: app-private storage already keeps it away from other apps, but
     * not from anyone holding an unlocked, rooted, or backed-up phone. Keystore-backed encryption
     * closes that, and costs nothing — the token is read once per check.
     *
     * The fallback matters more than it looks. EncryptedSharedPreferences fails outright on devices
     * whose keystore is in a bad state (a known problem after some restores and OS upgrades), and a
     * suite that will not launch because it could not open an *optional* token store would be a far
     * worse bug than the one this guards against. So a failure degrades to the ordinary
     * app-private file rather than taking the process down.
     *
     * [override] is a test seam and nothing else, and the same one `FinanceSecrets` carries for the
     * same reason: `EncryptedSharedPreferences` needs `AndroidKeyStore`, which does not exist on the
     * JVM. What the tests need to exercise is the *mirroring* — which ref is used, that a write goes
     * to both stores, that a read falls through to the vault after a restore — none of which is
     * about where the local copy is kept. Production has one constructor and it passes null.
     */
    private val secrets: SharedPreferences = override ?: runCatching {
        val key = MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            app,
            "sandbox_update_secrets",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }.getOrElse {
        app.getSharedPreferences("sandbox_update_secrets_plain", Context.MODE_PRIVATE)
    }

    /**
     * A GitHub personal access token with read access to this repository's contents, or null.
     *
     * Needed only because the repository is private: `/releases/latest` and the release assets are
     * both 404s to an anonymous request, which the updater cannot tell apart from "no release yet".
     * It is typed in on the Updates tab and never leaves the phone except as an Authorization
     * header to api.github.com. Nothing bakes a token into the APK — an APK is a file that gets
     * copied around, and a credential inside one is a credential published.
     *
     * If the repository is ever made public this becomes unnecessary and can be cleared.
     *
     * ## Why this one is mirrored into the vault
     *
     * Everything above describes a credential in `EncryptedSharedPreferences`, behind a Keystore
     * key, deliberately left out of the archive — which is word for word the arrangement Finance
     * and Citation had, and word for word the reason Secrets exists. A key bound to this phone's
     * hardware cannot be carried to the next phone, so a restore brought back every setting on this
     * screen and not the token, and the updater went quiet with a 404 it cannot tell from "no
     * release yet". The household's own suite stopped being able to update itself, silently, on the
     * one day they were least likely to investigate it.
     *
     * So it mirrors like any other credential. The local store stays exactly what it was — the
     * working copy, read once per check, device-bound, still out of the archive — and the vault
     * holds the surviving copy. [ManagedSecrets.readThrough] means normal running never consults
     * the vault at all: the local store answers, and only an empty one (which after a restore is
     * this one) falls through.
     *
     * The owner is [SecretOwner.SHELL] rather than an [com.operations.backupkit.AppId], because the
     * container is not one of the apps it hosts — see [SecretOwner] for why that distinction is
     * kept rather than papered over with a twelfth enum entry.
     */
    var token: String?
        get() = ManagedSecrets.readThrough(
            ref = TOKEN_REF,
            local = { secrets.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() } },
            rehydrate = { fromVault -> writeLocally(fromVault) }
        )
        set(value) {
            val clean = value?.trim().orEmpty()
            writeLocally(clean)
            // After the local write, never instead of it: the updater must keep working on a phone
            // with no vault, and on one whose vault is shut the write is queued rather than lost.
            if (clean.isEmpty()) {
                ManagedSecrets.forget(TOKEN_REF)
            } else {
                ManagedSecrets.remember(TOKEN_REF, clean, TOKEN_LABEL, SecretOwner.SHELL)
            }
        }

    /** The working copy, on its own. Blank clears it. */
    private fun writeLocally(value: String) {
        secrets.edit().apply {
            if (value.isEmpty()) remove(KEY_TOKEN) else putString(KEY_TOKEN, value)
        }.apply()
    }

    /**
     * File everything the shell holds into a vault that has just been rebuilt.
     *
     * The other half of [com.operations.vaultkit.SecretSource], called after a forgotten passphrase
     * has cost somebody their vault: the token is still in the local store twelve inches away, so
     * there is no reason for the rebuild to leave the updater broken. Returns how many refs it
     * wrote, which here is one or none.
     */
    fun refileIntoVault(): Int {
        val local = secrets.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() } ?: return 0
        ManagedSecrets.remember(TOKEN_REF, local, TOKEN_LABEL, SecretOwner.SHELL)
        return 1
    }

    /**
     * Whether the shell may ask GitHub on its own at launch. On by default — an update nobody is
     * told about may as well not exist when there is no store to nag on the app's behalf — and off
     * is a single switch away on the Updates tab, after which nothing here touches the network
     * unless a button is pressed.
     */
    var autoCheck: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CHECK, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_CHECK, value).apply()

    /** Epoch millis of the last completed automatic check, 0 if there has never been one. */
    var lastCheckedAt: Long
        get() = prefs.getLong(KEY_LAST_CHECKED, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_CHECKED, value).apply()

    /** Whether an automatic check is due — the arithmetic lives in [UpdateSchedule], with its tests. */
    fun isAutoCheckDue(now: Long = System.currentTimeMillis()): Boolean =
        UpdateSchedule.isCheckDue(enabled = autoCheck, lastCheckedAt = lastCheckedAt, now = now)

    companion object {
        private const val KEY_AUTO_CHECK = "auto_check"
        private const val KEY_LAST_CHECKED = "last_checked_at"
        private const val KEY_TOKEN = "github_token"

        /**
         * Where the token is filed in the vault: `sandbox/self/github-token`.
         *
         * `self` because the shell has no rows to hang this off — there is one updater and one
         * token, not one per connection — which is the same reason Finance's Plaid client id uses
         * it. A ref is a filing name and nothing dispatches on it (see
         * [com.operations.vaultkit.SecretRef]); this one exists so the item can be found again and
         * so the household can see, in their own vault, that the shell keeps a credential too.
         */
        val TOKEN_REF = SecretRef(SecretOwner.SHELL_KEY, SecretRef.SELF, "github-token")

        /**
         * What the row is called in the household's vault list, beside their own logins.
         *
         * Written once because both places that file it must agree — though only the first would
         * ever be used: the broker titles a mirrored item when it creates it and never re-titles it
         * afterwards, so somebody who renames this row keeps their name.
         */
        private val TOKEN_LABEL = ManagedSecrets.label(SecretOwner.SHELL, "GitHub update token")
    }
}
