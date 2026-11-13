package com.operations.sandbox.transfer

import android.content.Context

/**
 * Whether to offer "Restore from old phone" on the home screen.
 *
 * Decided once, on the very first launch, before any hosted app has opened a database: if this
 * install has never been updated and the app's database directory is empty, the phone is new to
 * the suite, and the offer stands until it is used or dismissed. Any later launch keeps whatever
 * was decided, so somebody who has been using the suite for a month never sees it appear.
 *
 * [decide] must run from `SandboxActivity.onCreate` before `setContent`. That's the only point
 * where "empty" still means what it says.
 */
object FirstRun {

    private const val PREFS = "sandbox_first_run"
    private const val KEY_DECIDED = "decided"
    private const val KEY_OFFER_RESTORE = "offer_restore"

    fun decide(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_DECIDED, false)) return
        val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        val neverUpdated = info == null || info.firstInstallTime == info.lastUpdateTime
        // WorkManager's own database can appear at start-up (the scheduled backup is synced from
        // Application.onCreate), and says nothing about whether the household has data here.
        val noDatabases = context.databaseList().none { name ->
            !name.startsWith("androidx.work") && !name.endsWith("-journal") && !name.endsWith("-wal") && !name.endsWith("-shm")
        }
        prefs.edit()
            .putBoolean(KEY_DECIDED, true)
            .putBoolean(KEY_OFFER_RESTORE, neverUpdated && noDatabases)
            .apply()
    }

    fun shouldOfferRestore(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_OFFER_RESTORE, false)

    /** After a restore, or when somebody taps "Start fresh". */
    fun resolve(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_OFFER_RESTORE, false).apply()
    }
}
