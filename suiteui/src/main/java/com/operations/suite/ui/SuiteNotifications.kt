package com.operations.suite.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * The suite's one notification prompt, and the record of whether it has been shown.
 *
 * `POST_NOTIFICATIONS` is granted to a *package*, and since the eleven apps became library modules
 * there is exactly one package. That is easy to forget and expensive to forget: the permission was
 * asked for by LifeOps, on the first launch of LifeOps, and nowhere else — so a household that used
 * Health and never opened LifeOps was never asked, and the medication reminders they had set up
 * were posted by `MedicationReminderWorker` into a void. Nothing failed, nothing was logged, and
 * the only symptom was a reminder that did not arrive.
 *
 * So the ask belongs to the container, which is the one screen everybody passes through. The record
 * lives here, in :suiteui, rather than in the container, for the reason every shared thing lives
 * here: every hosted app depends on this module and none of them may depend on `:app`. LifeOps
 * still asks if it somehow gets there first, and reads the same record, so the household sees one
 * prompt whichever door they came in by.
 *
 * ## What this deliberately does not do
 *
 * It does not ask again. Android stops showing the dialog after two refusals anyway, but the
 * stronger reason is that a prompt somebody dismissed is an answer; an app that keeps asking is an
 * app teaching people to dismiss it faster. An app that needs the permission *now* — Citation,
 * when a book is being narrated — asks in context on its own screen, which is a different and
 * better-earned question than this one.
 *
 * Upgrading from the old arrangement re-asks once, and only of somebody who refused: the record
 * LifeOps kept was its own (`lifeops_prefs`), and reading another app's preferences to spare one
 * dialog would cost more than the dialog does. A household that granted the permission is never
 * asked, because [shouldAsk] checks the grant before it checks the record.
 */
object SuiteNotifications {

    /**
     * The container's record, not any app's — the shell is what asks — which is also why the name
     * carries the `sandbox_` prefix that keeps the container's own preferences out of the suite's
     * archive (see `BackupCoverageTest`). A restored phone asking once is correct: the permission
     * did not travel either.
     */
    private const val PREFS_NAME = "sandbox_notifications"
    private const val KEY_ASKED = "permission_asked"

    /**
     * Whether the suite may post notifications at all.
     *
     * This used to answer `true` outright below Android 13, where the permission did not exist. The
     * suite's floor is 34 now — passkeys took it there — so every phone that can install this has
     * the permission to grant, and the branch that used to say "no question to put to anybody" was
     * describing a device that can no longer run the app.
     */
    fun granted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Is there a prompt to show? True at most once per install: when the permission is not already
     * held and nobody has been asked for it yet.
     *
     * Whoever acts on this must call [markAsked] — before launching the request, not in its result
     * callback, because the result arrives after the activity has been through a configuration
     * change or two and a record written then is a record that can be missed.
     */
    fun shouldAsk(context: Context): Boolean =
        !granted(context) && !prefs(context).getBoolean(KEY_ASKED, false)

    /** Remember that the household has been asked, whatever they answered. */
    fun markAsked(context: Context) {
        prefs(context).edit().putBoolean(KEY_ASKED, true).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
