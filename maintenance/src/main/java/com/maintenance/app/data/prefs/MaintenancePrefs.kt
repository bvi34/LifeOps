package com.maintenance.app.data.prefs

import android.content.Context
import android.content.SharedPreferences

/**
 * Maintenance's small amount of "where was I" state.
 *
 * None of this is content — it is the app's memory of what you were looking at, which is why it
 * lives in preferences rather than in the database. The file is named `maintenance_prefs` so the
 * sandbox's per-app prefs isolation (each contributor touches only files matching its own prefix)
 * keeps working; see [com.maintenance.app.backup.MaintenanceBackupContributor].
 */
class MaintenancePrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /**
     * Which of the two front doors you were last at — the docket, or the assets.
     *
     * Most opens are "what's due"; somebody who uses this as a register of what they own should not
     * have to walk past the docket every time.
     */
    var lastTab: String?
        get() = prefs.getString(KEY_LAST_TAB, null)
        set(value) = prefs.edit().putString(KEY_LAST_TAB, value).apply()

    /** Whether the docket is showing everything, rather than only what is pressing. */
    var docketShowsAll: Boolean
        get() = prefs.getBoolean(KEY_DOCKET_ALL, false)
        set(value) = prefs.edit().putBoolean(KEY_DOCKET_ALL, value).apply()

    /** Whether the asset list is showing the ones you no longer own. */
    var showArchived: Boolean
        get() = prefs.getBoolean(KEY_SHOW_ARCHIVED, false)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_ARCHIVED, value).apply()

    /**
     * Whether this install has ever put a plan on the LifeOps week.
     *
     * It exists to keep a promise the app makes elsewhere: nothing of Maintenance's is loaded until
     * somebody opens it. LifeOps announces *every* task completion in the process, and answering
     * "is that one of mine?" from the database would open `maintenance.db` the first time anybody
     * ticked anything, in an install that has never used Maintenance at all. A boolean in
     * preferences answers it for free.
     */
    var hasPublishedTasks: Boolean
        get() = prefs.getBoolean(KEY_HAS_PUBLISHED, false)
        set(value) = prefs.edit().putBoolean(KEY_HAS_PUBLISHED, value).apply()

    companion object {
        const val FILE_NAME = "maintenance_prefs"
        private const val KEY_LAST_TAB = "last_tab"
        private const val KEY_DOCKET_ALL = "docket_shows_all"
        private const val KEY_SHOW_ARCHIVED = "show_archived"
        private const val KEY_HAS_PUBLISHED = "has_published_tasks"
    }
}
