package com.operations.sandbox.update

import android.content.pm.PackageInstaller
import com.operations.sandbox.update.logic.InstallOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What an install session's status turns into on the Updates tab.
 *
 * Getting the non-failures right matters most: a confirmation screen on its way must not put up an
 * error card, and a user backing out of the confirmation must get the Install button back rather
 * than an error.
 */
class InstallOutcomeTest {

    @Test
    fun `the status codes are the platform's`() {
        // Compile-time constants, so this reads the SDK's values even on the JVM.
        assertEquals(PackageInstaller.STATUS_PENDING_USER_ACTION, InstallOutcome.STATUS_PENDING_USER_ACTION)
        assertEquals(PackageInstaller.STATUS_SUCCESS, InstallOutcome.STATUS_SUCCESS)
        assertEquals(PackageInstaller.STATUS_FAILURE, InstallOutcome.STATUS_FAILURE)
        assertEquals(PackageInstaller.STATUS_FAILURE_BLOCKED, InstallOutcome.STATUS_FAILURE_BLOCKED)
        assertEquals(PackageInstaller.STATUS_FAILURE_ABORTED, InstallOutcome.STATUS_FAILURE_ABORTED)
        assertEquals(PackageInstaller.STATUS_FAILURE_INVALID, InstallOutcome.STATUS_FAILURE_INVALID)
        assertEquals(PackageInstaller.STATUS_FAILURE_CONFLICT, InstallOutcome.STATUS_FAILURE_CONFLICT)
        assertEquals(PackageInstaller.STATUS_FAILURE_STORAGE, InstallOutcome.STATUS_FAILURE_STORAGE)
        assertEquals(PackageInstaller.STATUS_FAILURE_INCOMPATIBLE, InstallOutcome.STATUS_FAILURE_INCOMPATIBLE)
        assertEquals(PackageInstaller.STATUS_FAILURE_TIMEOUT, InstallOutcome.STATUS_FAILURE_TIMEOUT)
    }

    @Test
    fun `success and a pending confirmation are not failures`() {
        assertEquals(InstallOutcome.Installed, InstallOutcome.of(InstallOutcome.STATUS_SUCCESS, null))
        assertEquals(
            InstallOutcome.AwaitingConfirmation,
            InstallOutcome.of(InstallOutcome.STATUS_PENDING_USER_ACTION, null)
        )
    }

    @Test
    fun `backing out of the confirmation is a cancel, not an error`() {
        assertEquals(
            InstallOutcome.Cancelled,
            InstallOutcome.of(InstallOutcome.STATUS_FAILURE_ABORTED, "User rejected permissions")
        )
    }

    @Test
    fun `a conflict points at the signing explanation`() {
        val outcome = InstallOutcome.of(InstallOutcome.STATUS_FAILURE_CONFLICT, null)
        assertTrue(outcome is InstallOutcome.Failed)
        assertTrue((outcome as InstallOutcome.Failed).message.contains("signed with different keys"))
    }

    @Test
    fun `the installer's own message is kept at the end`() {
        val outcome = InstallOutcome.of(InstallOutcome.STATUS_FAILURE_STORAGE, " INSTALL_FAILED_INSUFFICIENT_STORAGE ")
        assertTrue((outcome as InstallOutcome.Failed).message.endsWith("(INSTALL_FAILED_INSUFFICIENT_STORAGE)"))
    }

    @Test
    fun `a blank message adds nothing`() {
        val outcome = InstallOutcome.of(InstallOutcome.STATUS_FAILURE, "  ") as InstallOutcome.Failed
        assertEquals("Android didn't install the update.", outcome.message)
    }

    @Test
    fun `an unknown status is a failure`() {
        assertTrue(InstallOutcome.of(99, null) is InstallOutcome.Failed)
    }
}
