package com.operations.sandbox.ui

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.operations.suite.ui.SuiteNotifications
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * One prompt, once, for the whole suite.
 *
 * The class under test lives in :suiteui because every hosted app has to be able to read the same
 * record and none of them may depend on the container. The test lives here because this is the
 * module that already runs Robolectric, and because the behaviour being pinned is the container's:
 * `SandboxActivity` is what asks now.
 *
 * What is worth pinning is the *shape* of the decision rather than androidx's permission plumbing:
 * a household that already granted the permission is never asked (which is what keeps this change
 * from re-prompting everybody who upgrades), and a household that has been asked is never asked
 * twice, whatever they answered.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SuiteNotificationsTest {

    private val context: Application get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `a fresh install on Android 13 is asked`() {
        assertFalse(SuiteNotifications.granted(context))
        assertTrue(SuiteNotifications.shouldAsk(context))
    }

    @Test
    fun `nobody is asked twice`() {
        SuiteNotifications.markAsked(context)
        assertFalse(SuiteNotifications.shouldAsk(context))
    }

    @Test
    fun `a household that already granted it is never asked`() {
        // The upgrade case, and the reason the grant is checked before the record: everybody who
        // answered LifeOps' old prompt with "allow" has the permission and no record of being
        // asked, and must not meet this dialog again.
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        assertTrue(SuiteNotifications.granted(context))
        assertFalse(SuiteNotifications.shouldAsk(context))
    }

    @Test
    fun `a phone that refused is not asked again`() {
        // This replaces a test that pinned the pre-Android-13 case, where the permission did not
        // exist and there was no question to put to anybody. The suite's floor is 34 now, so that
        // device cannot run this app and the branch it covered is gone. What is still worth pinning
        // is the case it shared a shape with: no grant, but an answer already given.
        SuiteNotifications.markAsked(context)

        assertFalse(SuiteNotifications.granted(context))
        assertFalse(SuiteNotifications.shouldAsk(context))
    }
}
