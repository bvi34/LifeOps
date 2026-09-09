package com.finance.app.data.prefs

import android.content.Context
import android.content.SharedPreferences

/**
 * Finance's small amount of "where was I" state, and two settings that shape the front screen.
 *
 * None of this is content and none of it is a secret — it is the app's memory of what you were
 * looking at, plus two numbers you chose. The file is named `finance_prefs` so the sandbox's per-app
 * prefs isolation (each contributor touches only files matching its own prefix) keeps working; see
 * [com.finance.app.backup.FinanceBackupContributor]. The credentials live somewhere else entirely
 * and under a name that deliberately does not match that prefix — see
 * [com.finance.app.data.secure.FinanceSecrets].
 */
class FinancePrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** Which of the four front doors you were last at. */
    var lastTab: String?
        get() = prefs.getString(KEY_LAST_TAB, null)
        set(value) = prefs.edit().putString(KEY_LAST_TAB, value).apply()

    /**
     * The balance the household treats as a floor — the number they don't want to go under.
     *
     * Zero by default, which makes the forecast's alarm the overdraft. Set to anything above that
     * and it becomes a far more useful warning: "you'll dip under your $500 buffer on the 28th" is
     * something somebody can act on a week early, where "you'll be overdrawn on the 28th" is news
     * that arrives too late to be useful.
     */
    var floorCents: Long
        get() = prefs.getLong(KEY_FLOOR, 0L)
        set(value) = prefs.edit().putLong(KEY_FLOOR, value.coerceAtLeast(0L)).apply()

    /** How far the forecast and the due list look ahead. Six weeks by default. */
    var horizonDays: Int
        get() = prefs.getInt(KEY_HORIZON, DEFAULT_HORIZON_DAYS)
        set(value) = prefs.edit().putInt(KEY_HORIZON, value.coerceIn(7, 180)).apply()

    /**
     * Whether the forecast mixes in an estimate of ordinary daily spending, or shows bills alone.
     *
     * On by default, because a line that only counts bills is optimistic in a way that defeats the
     * point. Off is for somebody who wants the schedule without the estimate mixed into it, and
     * both are honest — the screen says which one it is showing.
     */
    var forecastIncludesSpending: Boolean
        get() = prefs.getBoolean(KEY_FORECAST_SPEND, true)
        set(value) = prefs.edit().putBoolean(KEY_FORECAST_SPEND, value).apply()

    /**
     * Whether this install has ever put a bill on the LifeOps week.
     *
     * The cheap gate that keeps an unused Finance from opening its database every time a task is
     * ticked anywhere in the suite: an install that has never published cannot own the task that was
     * just completed, and answering that from the database would open it for nothing.
     */
    var hasPublishedTasks: Boolean
        get() = prefs.getBoolean(KEY_PUBLISHED, false)
        set(value) = prefs.edit().putBoolean(KEY_PUBLISHED, value).apply()

    /**
     * When the app last refreshed from a provider, so opening it twice in a minute doesn't ask the
     * bank twice.
     */
    var lastAutoRefreshAt: Long
        get() = prefs.getLong(KEY_LAST_AUTO_REFRESH, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_AUTO_REFRESH, value).apply()

    /** Whether enough time has passed to refresh on open without being a nuisance about it. */
    fun shouldAutoRefresh(now: Long = System.currentTimeMillis()): Boolean =
        now - lastAutoRefreshAt >= AUTO_REFRESH_INTERVAL_MS

    private companion object {
        const val FILE_NAME = "finance_prefs"
        const val KEY_LAST_TAB = "last_tab"
        const val KEY_FLOOR = "floor_cents"
        const val KEY_HORIZON = "horizon_days"
        const val KEY_FORECAST_SPEND = "forecast_includes_spending"
        const val KEY_PUBLISHED = "has_published_tasks"
        const val KEY_LAST_AUTO_REFRESH = "last_auto_refresh_at"

        const val DEFAULT_HORIZON_DAYS = 45

        /**
         * Four hours.
         *
         * Balances do not move fast enough to justify more, and each refresh is a round trip to
         * somebody's bank on a mobile connection. Pressing Refresh always works regardless — this
         * only governs the automatic one on opening the app.
         */
        const val AUTO_REFRESH_INTERVAL_MS = 4L * 60L * 60L * 1000L
    }
}
