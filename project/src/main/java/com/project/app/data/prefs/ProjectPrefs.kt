package com.project.app.data.prefs

import android.content.Context
import android.content.SharedPreferences

/**
 * Project's small amount of "where was I" state.
 *
 * None of this is content — it is the app's memory of what you had open, which is why it lives in
 * preferences rather than in the database. The file is named `project_prefs` so the sandbox's
 * per-app prefs isolation (each contributor touches only files matching its own prefix) keeps
 * working; see [com.project.app.backup.ProjectBackupContributor].
 */
class ProjectPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /**
     * The project that was open when you last left.
     *
     * Reopening on it is the right default for a repository you dip in and out of all week. It is
     * only a hint: a project that has since been deleted simply lands you back on the shelf.
     */
    var lastProjectId: String?
        get() = prefs.getString(KEY_LAST_PROJECT, null)
        set(value) = prefs.edit().putString(KEY_LAST_PROJECT, value).apply()

    /** The section that project was left on — Outline, Docs, Lore, Timeline or Board. */
    var lastSection: String?
        get() = prefs.getString(KEY_LAST_SECTION, null)
        set(value) = prefs.edit().putString(KEY_LAST_SECTION, value).apply()

    /** Whether the doc editor was last in reading mode rather than block-editing mode. */
    var docReadingMode: Boolean
        get() = prefs.getBoolean(KEY_READING_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_READING_MODE, value).apply()

    /**
     * Whether tables are read as cards rather than as a grid.
     *
     * A preference about *this screen*, not about any one table: how wide a table can be before it
     * stops fitting is a fact about the phone in your hand.
     */
    var docTableCards: Boolean
        get() = prefs.getBoolean(KEY_TABLE_CARDS, false)
        set(value) = prefs.edit().putBoolean(KEY_TABLE_CARDS, value).apply()

    /**
     * Whether this install has ever put a card on the LifeOps week.
     *
     * The cheap gate in front of the completion listener. LifeOps announces every tick in the suite,
     * and a household that has never dated a card should not open Project's database to find that
     * out — so the question is answered from a preference, and only a `true` costs a read.
     */
    var hasPublishedTasks: Boolean
        get() = prefs.getBoolean(KEY_HAS_PUBLISHED, false)
        set(value) = prefs.edit().putBoolean(KEY_HAS_PUBLISHED, value).apply()

    companion object {
        const val FILE_NAME = "project_prefs"
        private const val KEY_LAST_PROJECT = "last_project_id"
        private const val KEY_LAST_SECTION = "last_section"
        private const val KEY_READING_MODE = "doc_reading_mode"
        private const val KEY_TABLE_CARDS = "doc_table_cards"
        private const val KEY_HAS_PUBLISHED = "has_published_tasks"
    }
}
