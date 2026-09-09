package com.secrets.app.data

import android.content.Context

/**
 * The handful of settings this app has, in a plain preferences file.
 *
 * Plain, and that is the point worth stating: **nothing here is a secret.** How long before the
 * vault locks itself, whether the clipboard is cleared, which tab you were on. The file is named
 * `secrets_prefs` so the backup contributor collects it, and the one file that must never travel is
 * named so that it cannot be (see [DeviceUnlock]).
 */
class SecretsPrefs(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /**
     * Minutes of no interaction before the vault shuts itself, or 0 for never.
     *
     * Five minutes by default. Long enough to look something up, switch to the app you needed it
     * for, and come back; short enough that a phone left on a table is not an open vault. "Never"
     * is offered because somebody with a phone that never leaves their hand should be allowed to
     * choose it, and it is not the default because most people would set it and forget why the app
     * stopped protecting them.
     */
    var autoLockMinutes: Int
        get() = prefs.getInt(KEY_AUTO_LOCK, DEFAULT_AUTO_LOCK_MINUTES)
        set(value) = prefs.edit().putInt(KEY_AUTO_LOCK, value.coerceIn(0, 120)).apply()

    val autoLockMillis: Long get() = autoLockMinutes * 60_000L

    /**
     * Lock the moment the app goes to the background, regardless of the timer.
     *
     * Off by default, because the common use of this app is *copy a password, go and paste it
     * somewhere else, come back* — and an app that locks on every switch makes that a four-step
     * dance. On for anybody who wants it.
     */
    var lockOnLeave: Boolean
        get() = prefs.getBoolean(KEY_LOCK_ON_LEAVE, false)
        set(value) = prefs.edit().putBoolean(KEY_LOCK_ON_LEAVE, value).apply()

    /**
     * Seconds before a copied secret is taken back out of the clipboard, or 0 to leave it.
     *
     * Android's clipboard is readable by the foreground app and, on older versions, by anything at
     * all; a password sitting in it until the next copy is a password in a shared buffer. Ninety
     * seconds is enough to switch apps and paste.
     */
    var clipboardClearSeconds: Int
        get() = prefs.getInt(KEY_CLIPBOARD_CLEAR, DEFAULT_CLIPBOARD_SECONDS)
        set(value) = prefs.edit().putInt(KEY_CLIPBOARD_CLEAR, value.coerceIn(0, 600)).apply()

    /** Which list was last open, so the app comes back where it was left. */
    var lastTab: String?
        get() = prefs.getString(KEY_LAST_TAB, null)
        set(value) = prefs.edit().putString(KEY_LAST_TAB, value).apply()

    /** Whether the list shows the credentials other apps mirrored here, or only what you typed in. */
    var showManaged: Boolean
        get() = prefs.getBoolean(KEY_SHOW_MANAGED, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_MANAGED, value).apply()

    private companion object {
        /** Collected by the backup contributor, which matches on the `secrets` prefix. */
        const val NAME = "secrets_prefs"

        const val KEY_AUTO_LOCK = "auto_lock_minutes"
        const val KEY_LOCK_ON_LEAVE = "lock_on_leave"
        const val KEY_CLIPBOARD_CLEAR = "clipboard_clear_seconds"
        const val KEY_LAST_TAB = "last_tab"
        const val KEY_SHOW_MANAGED = "show_managed"

        const val DEFAULT_AUTO_LOCK_MINUTES = 5
        const val DEFAULT_CLIPBOARD_SECONDS = 90
    }
}
