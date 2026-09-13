package com.operations.sandbox.shortcuts

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.operations.backupkit.AppId
import com.operations.suitekit.SuiteApps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the phone's own launcher is offered when the suite's icon is long-pressed.
 *
 * The ranking is the part worth pinning. It has to be right on a phone where nothing has been
 * opened yet (a fresh install should have four shortcuts, not none), right on one where the same
 * app has been opened forty times (four shortcuts, not one repeated), and right on one where the
 * household has hidden an app they used to use — which is the case a "most recently opened" list
 * gets wrong by default, by cheerfully offering the app they just took off their home screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class SuiteShortcutsTest {

    private val context: Application get() = ApplicationProvider.getApplicationContext()

    private val everyApp = SuiteApps.all.map { it.appId }

    @Test
    fun `a phone where nothing has been opened still gets a full set`() {
        val ranked = SuiteShortcuts.ranked(recent = emptyList(), showing = everyApp)

        assertEquals(everyApp.take(4), ranked)
    }

    @Test
    fun `recently opened apps come first, and the rest fill the list out`() {
        val ranked = SuiteShortcuts.ranked(
            recent = listOf(AppId.MAINTENANCE.key, AppId.FINANCE.key),
            showing = everyApp
        )

        assertEquals(listOf(AppId.MAINTENANCE, AppId.FINANCE), ranked.take(2))
        assertEquals(4, ranked.size)
        assertEquals("no app should be offered twice", ranked.size, ranked.distinct().size)
    }

    @Test
    fun `an app taken off the home screen is not offered by the launcher either`() {
        val showing = everyApp.filterNot { it == AppId.MAINTENANCE }

        val ranked = SuiteShortcuts.ranked(
            // Opened often, and since hidden. The hiding is the more recent instruction.
            recent = listOf(AppId.MAINTENANCE.key, AppId.FINANCE.key),
            showing = showing
        )

        assertFalse(AppId.MAINTENANCE in ranked)
        assertEquals(AppId.FINANCE, ranked.first())
    }

    @Test
    fun `a key from a build that knew an app this one does not is skipped`() {
        val ranked = SuiteShortcuts.ranked(recent = listOf("telegraphy"), showing = everyApp)

        assertEquals(everyApp.take(4), ranked)
    }

    @Test
    fun `opening an app puts it at the front and does not duplicate it`() {
        SuiteShortcuts.rememberOpened(context, AppId.HEALTH)
        SuiteShortcuts.rememberOpened(context, AppId.LOGISTICS)
        SuiteShortcuts.rememberOpened(context, AppId.HEALTH)

        assertEquals(listOf(AppId.HEALTH.key, AppId.LOGISTICS.key), SuiteShortcuts.recent(context))
    }

    @Test
    fun `the remembered list does not grow without end`() {
        // More opens than the list can hold; what survives is the newest, in order.
        everyApp.forEach { SuiteShortcuts.rememberOpened(context, it) }

        val recent = SuiteShortcuts.recent(context)
        assertTrue("the history should be capped", recent.size <= 8)
        assertEquals(everyApp.last().key, recent.first())
    }

    @Test
    fun `a shortcut opens the container, told which app it wants`() {
        // Not the hosted activity: those are not exported, so a launcher may not start one. The
        // container opens it, which is also why back from a shortcut lands on the home screen.
        val intent = SuiteShortcuts.intentFor(context, AppId.LOGISTICS)

        assertEquals("com.operations.sandbox.SandboxActivity", intent.component?.className)
        assertEquals(AppId.LOGISTICS.key, intent.getStringExtra(SuiteShortcuts.EXTRA_OPEN_APP))
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TASK != 0)
    }
}
