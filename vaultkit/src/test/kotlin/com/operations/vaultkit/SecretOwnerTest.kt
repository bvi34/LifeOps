package com.operations.vaultkit

import com.operations.backupkit.AppId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who is allowed to own a secret, and what their name is written as.
 *
 * The tests that matter here are about the *string*. An owner's key is stored in every mirrored
 * item and is the first segment of every ref, so an archive written last year names its owners with
 * these exact characters — which makes a silent collision or a renamed key a data bug rather than a
 * compile error.
 */
class SecretOwnerTest {

    @Test
    fun `the shell's key collides with no hosted app`() {
        // The reason this is a test and not a comment: adding a hosted app is a one-line change to
        // AppId, and one called "Sandbox" would quietly start reading the container's credentials
        // back — the ref is a string, and nothing else would notice.
        assertTrue(AppId.entries.none { it.key == SecretOwner.SHELL_KEY })
    }

    @Test
    fun `the shell's key is a legal ref segment`() {
        // It is used as one, in `sandbox/self/github-token`. A key that failed here would throw at
        // the first attempt to file the updater's token, on a device, at start-up.
        assertTrue(SecretRef.valid(SecretOwner.SHELL_KEY))
    }

    @Test
    fun `every hosted app's owner carries that app's key and name`() {
        AppId.entries.forEach { appId ->
            val owner = SecretOwner.of(appId)
            assertEquals(appId.key, owner.key)
            assertEquals(appId.defaultDisplayName, owner.displayName)
            assertEquals(appId, owner.appId)
            assertFalse(owner.isShell)
        }
    }

    @Test
    fun `the shell is not one of the apps`() {
        assertTrue(SecretOwner.SHELL.isShell)
        // The distinction the whole type exists for: the container holds credentials and has no
        // AppId, because it has no tile, no backup payload and no place in SuiteApps.
        assertNull(SecretOwner.SHELL.appId)
        assertEquals("Operations Sandbox", SecretOwner.SHELL.displayName)
    }

    @Test
    fun `an owner is its key, however it was obtained`() {
        assertEquals(SecretOwner.of(AppId.FINANCE), SecretOwner.fromKey("finance"))
        assertEquals(SecretOwner.of(AppId.FINANCE).hashCode(), SecretOwner.fromKey("finance")!!.hashCode())
        assertEquals(SecretOwner.SHELL, SecretOwner.fromKey(SecretOwner.SHELL_KEY))
    }

    @Test
    fun `a key from a newer version of the suite is nobody rather than somebody invented`() {
        // An item whose managedBy this build has never heard of is shown under its raw key; making
        // up a display name for it would be a vault row confidently mislabelled.
        assertNull(SecretOwner.fromKey("telemetry"))
        assertNull(SecretOwner.fromKey(null))
        assertNull(SecretOwner.fromKey(""))
    }

    @Test
    fun `every owner round-trips through its key`() {
        SecretOwner.all.forEach { owner ->
            assertEquals(owner, SecretOwner.fromKey(owner.key))
        }
        assertEquals(AppId.entries.size + 1, SecretOwner.all.size)
        assertNotNull(SecretOwner.all.firstOrNull { it.isShell })
    }
}
