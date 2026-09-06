package com.operations.sandbox.update

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.operations.sandbox.update.logic.UpdateSchedule

/**
 * The two things the updater has to remember between launches.
 *
 * Plain SharedPreferences rather than the suite's Room databases on purpose: this belongs to the
 * container, not to any hosted app, so it is deliberately outside everything the backup archive
 * collects. Restoring a year-old backup onto a new phone should not also restore a stale "we last
 * checked on Tuesday".
 */
class UpdatePrefs(context: Context) {

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
     */
    private val secrets: SharedPreferences = runCatching {
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
     */
    var token: String?
        get() = secrets.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() }
        set(value) {
            val clean = value?.trim().orEmpty()
            secrets.edit().apply {
                if (clean.isEmpty()) remove(KEY_TOKEN) else putString(KEY_TOKEN, clean)
            }.apply()
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

    private companion object {
        const val KEY_AUTO_CHECK = "auto_check"
        const val KEY_LAST_CHECKED = "last_checked_at"
        const val KEY_TOKEN = "github_token"
    }
}
