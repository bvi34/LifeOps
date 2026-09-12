package com.operations.sandbox.update

import com.operations.sandbox.update.logic.InstallCompatibility
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The question asked before the installer is handed an APK: will Android take this, or refuse it?
 *
 * The case that matters is the one a developer hits exactly once — a hand-built debug install being
 * offered its first real release — and the cost of getting it wrong runs both ways. A missed
 * conflict is the cryptic system dialog this exists to pre-empt; a *claimed* conflict that isn't
 * one would talk somebody through uninstalling their app for no reason at all.
 */
class InstallCompatibilityTest {

    private val debugKey = "aa11"
    private val releaseKey = "bb22"
    private val rotatedFrom = "cc33"

    @Test
    fun `the same signer installs over the top`() {
        assertEquals(
            InstallCompatibility.REPLACEABLE,
            InstallCompatibility.of(setOf(releaseKey), setOf(releaseKey))
        )
    }

    @Test
    fun `a debug build and a release build conflict`() {
        assertEquals(
            InstallCompatibility.SIGNATURE_CONFLICT,
            InstallCompatibility.of(setOf(debugKey), setOf(releaseKey))
        )
    }

    @Test
    fun `a certificate anywhere in the lineage counts as the same signer`() {
        // A rotated key leaves the old certificate in the package's history, and Android still
        // accepts an APK signed with it. Requiring the sets to be equal would call that a conflict.
        assertEquals(
            InstallCompatibility.REPLACEABLE,
            InstallCompatibility.of(setOf(releaseKey, rotatedFrom), setOf(rotatedFrom))
        )
    }

    @Test
    fun `a fingerprint that differs only in case is the same fingerprint`() {
        assertEquals(
            InstallCompatibility.REPLACEABLE,
            InstallCompatibility.of(setOf("AA11BB22"), setOf(" aa11bb22 "))
        )
    }

    @Test
    fun `an unreadable side claims nothing`() {
        // Neither "install away" nor "you have a conflict": the installer is still the authority,
        // and an updater that blocked on its own failure to read a file would be the worse bug.
        assertEquals(
            InstallCompatibility.UNKNOWN,
            InstallCompatibility.of(emptySet(), setOf(releaseKey))
        )
        assertEquals(
            InstallCompatibility.UNKNOWN,
            InstallCompatibility.of(setOf(debugKey), emptySet())
        )
        assertEquals(
            InstallCompatibility.UNKNOWN,
            InstallCompatibility.of(setOf("   "), setOf(releaseKey))
        )
    }
}
