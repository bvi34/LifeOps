package com.people.app.data.prefs

import android.content.Context
import android.content.SharedPreferences

/**
 * People's sync bookkeeping: one cursor per peer, plus the last version we published.
 *
 * These are cursors, not content — they describe how far this device has got with each peer, so they
 * live in preferences rather than the database. The file is named `people_prefs` so the sandbox's
 * per-app prefs isolation (each contributor touches only files matching its own prefix) keeps
 * working; see [com.people.app.backup.PeopleBackupContributor].
 */
class PeoplePrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** The highest version of [peer]'s packets we have durably taken. */
    fun cursorFor(peer: String): Long = prefs.getLong(cursorKey(peer), 0L)

    fun setCursorFor(peer: String, version: Long) {
        prefs.edit().putLong(cursorKey(peer), version).apply()
    }

    /** The highest local version we have published, for the roster screen's status line. */
    var lastPublishedVersion: Long
        get() = prefs.getLong(KEY_LAST_PUBLISHED, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_PUBLISHED, value).apply()

    private fun cursorKey(peer: String) = "cursor_$peer"

    companion object {
        const val FILE_NAME = "people_prefs"
        private const val KEY_LAST_PUBLISHED = "last_published_version"
    }
}
