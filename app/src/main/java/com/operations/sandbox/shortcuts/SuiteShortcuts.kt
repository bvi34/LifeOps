package com.operations.sandbox.shortcuts

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.operations.backupkit.AppId
import com.operations.sandbox.SandboxActivity
import com.operations.suite.ui.SuiteAppearanceStore
import com.operations.suite.ui.SuiteMarkRaster
import com.operations.suitekit.SuiteApps
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The suite's apps, as the *phone's* launcher sees them.
 *
 * Eleven apps ship behind one icon. That is the point of the container and it is also its one
 * concession: on the phone's home screen there is a single "Operations Sandbox", and everything
 * inside it is two taps away at best. A launcher's own answer to that is shortcuts, and the suite
 * had none at all — long-pressing its icon offered nothing, and there was no way to put "Logistics"
 * on the phone's home screen next to the apps it competes with for attention.
 *
 * Two kinds, and they answer different questions:
 *
 * - **Dynamic** shortcuts are what a long-press on the suite's icon offers. They are the apps most
 *   recently opened, which is the only ranking that needs no setup and is right more often than any
 *   fixed list: a household in the middle of a renovation gets Maintenance and Project without
 *   having asked for them. Android shows four or so, so four is what is published.
 * - **Pinned** shortcuts are the household putting an app on the phone's home screen themselves,
 *   from the tile's long-press menu. This is the one that undoes the container's concession
 *   outright — Logistics gets its own icon, in its own colour, beside everything else they use.
 *
 * Both route through [SandboxActivity] rather than launching a hosted activity directly. The hosted
 * activities are not exported (there is one launcher entry point, deliberately), so a shortcut that
 * named one would be a shortcut the launcher is not allowed to start. The container opens the app
 * instead, which also means back from a shortcut lands on the home screen rather than on nothing.
 *
 * Nothing here needs a permission or a manifest entry: publishing dynamic shortcuts is free, and
 * pinning asks the launcher, which asks the household.
 */
object SuiteShortcuts {

    /** How many dynamic shortcuts to publish. Launchers show four or five; asking for more is noise. */
    private const val PUBLISHED = 4

    /** How many opens to remember. A little longer than the list, so a one-off doesn't evict a habit. */
    private const val REMEMBERED = 8

    private const val PREFS = "sandbox_shortcuts"
    private const val KEY_RECENT = "recently_opened"

    /** The extra [SandboxActivity] reads to find out which app a shortcut wants opened. */
    const val EXTRA_OPEN_APP = "com.operations.sandbox.OPEN_APP"

    /**
     * Remember that [appId] was opened, and republish.
     *
     * Called from the one place an app is opened from, so "recent" means what it says. Cheap enough
     * to do inline: a preferences write and a list of four icons the platform caches.
     */
    fun recordOpened(context: Context, appId: AppId) {
        rememberOpened(context, appId)
        publish(context)
    }

    /** The remembering half, without the publishing half — the part worth testing on its own. */
    internal fun rememberOpened(context: Context, appId: AppId) {
        val recent = (listOf(appId.key) + recent(context)).distinct().take(REMEMBERED)
        prefs(context).edit().putString(KEY_RECENT, recent.joinToString(SEPARATOR)).apply()
    }

    /**
     * Publish the dynamic shortcuts: recently opened apps first, then the household's own home
     * screen order to fill the list out.
     *
     * The fallback is what makes this work on a phone where nothing has been opened yet — a fresh
     * install has a full set of shortcuts rather than none — and it is deliberately the *arranged*
     * order rather than the shipped one, so an app the household has hidden from their home screen
     * does not reappear in their launcher's long-press menu.
     */
    fun publish(context: Context) {
        // Off the main thread, and on the application context.
        //
        // Neither is fussiness. Publishing draws four icons and then makes a binder call into the
        // system server, and the two places this is called from are the moment an app is being
        // opened and the moment the home screen is being rearranged — both of them frames somebody
        // is watching. The application context because the work now outlives the activity that
        // asked for it.
        val app = context.applicationContext
        worker.execute { runCatching { publishNow(app) } }
    }

    private fun publishNow(context: Context) {
        val showing = SuiteAppearanceStore.get(context).appearance.homeApps.map { it.appId }
        val ranked = ranked(recent(context), showing)
        ShortcutManagerCompat.setDynamicShortcuts(context, ranked.map { shortcut(context, it) })
    }

    /**
     * One background thread for the lot. Serial rather than pooled on purpose: two publishes racing
     * would be two answers to "what are the four most recent apps", and the loser would win.
     */
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "suite-shortcuts").apply { isDaemon = true }
    }

    /**
     * Which apps get a shortcut: the recently opened ones first, then the household's own home
     * screen order to fill the list out.
     *
     * Both halves are filtered to what is actually *on* the home screen. An app the household has
     * hidden has been told to go away, and a launcher menu is not the place for it to turn up
     * again — including when they opened it last week, before hiding it.
     */
    internal fun ranked(recent: List<String>, showing: List<AppId>, limit: Int = PUBLISHED): List<AppId> =
        (recent.mapNotNull { AppId.fromKey(it) }.filter { it in showing } + showing)
            .distinct()
            .take(limit)

    /**
     * Does the phone's launcher do pinning at all?
     *
     * Asked before the row is offered rather than after it is tapped: a few launchers say no, and a
     * menu entry that silently does nothing is worse than one that was never there.
     */
    fun isPinSupported(context: Context): Boolean =
        runCatching { ShortcutManagerCompat.isRequestPinShortcutSupported(context) }.getOrDefault(false)

    /**
     * Ask the launcher to pin [appId] to the phone's home screen.
     *
     * Returns false when the launcher does not do pinning (some do not, and the platform says so
     * rather than failing), so the caller can tell the household nothing is going to happen instead
     * of leaving them waiting for a dialog that never comes.
     */
    fun pin(context: Context, appId: AppId): Boolean {
        if (!isPinSupported(context)) return false
        return runCatching {
            ShortcutManagerCompat.requestPinShortcut(context, shortcut(context, appId), null)
        }.getOrDefault(false)
    }

    /**
     * One app as a shortcut: its name, its own mark on its own colour, and an intent that opens it.
     *
     * The id is the app's key, so pinning the same app twice updates the pinned shortcut rather
     * than littering the home screen with copies of it.
     */
    private fun shortcut(context: Context, appId: AppId): ShortcutInfoCompat {
        val info = SuiteApps.of(appId)
        val appearance = SuiteAppearanceStore.get(context).appearance
        return ShortcutInfoCompat.Builder(context, appId.key)
            .setShortLabel(info.label)
            .setLongLabel(info.label)
            .setIcon(IconCompat.createWithAdaptiveBitmap(SuiteMarkRaster.launcherIcon(appearance, appId)))
            .setIntent(intentFor(context, appId))
            .build()
    }

    /**
     * What a shortcut launches: the container, told which app to open.
     *
     * `CLEAR_TASK` because a shortcut is a fresh intention. Without it, tapping "Logistics" while
     * the suite is already open on Citation would deliver the intent into that task and leave the
     * household somewhere between the two.
     */
    fun intentFor(context: Context, appId: AppId): Intent =
        Intent(context, SandboxActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra(EXTRA_OPEN_APP, appId.key)

    internal fun recent(context: Context): List<String> =
        prefs(context).getString(KEY_RECENT, null)
            ?.split(SEPARATOR)
            ?.filter { it.isNotBlank() }
            .orEmpty()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val SEPARATOR = ","
}
