package com.operations.sandbox.ui

import com.operations.vaultkit.VaultState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the home screen says about the vault, and — more importantly — when it says nothing.
 *
 * The line under the clock is the only interruption Secrets gets anywhere outside its own app, so
 * the bar for showing it is the whole design: it appears when a credential is actually stranded,
 * and never merely because the vault is shut.
 */
class VaultStatusTest {

    @Test
    fun `a locked vault with nothing waiting is not news`() {
        // The vault comes up shut on every process start, by design. "Secrets is locked" would
        // therefore be true nearly always and would say nothing — a permanent badge is a badge
        // people stop seeing, including on the day it means something.
        assertFalse(VaultStatus(VaultState.LOCKED, pending = 0).needsAttention)
        assertFalse(VaultStatus(VaultState.UNLOCKED, pending = 0).needsAttention)
        assertFalse(VaultStatus(VaultState.ABSENT, pending = 0).needsAttention)
    }

    @Test
    fun `a stranded credential is`() {
        assertTrue(VaultStatus(VaultState.LOCKED, pending = 1).needsAttention)
    }

    @Test
    fun `a shut vault is asked to be unlocked`() {
        assertEquals(
            "3 credentials are waiting for your vault — tap to unlock",
            VaultStatus(VaultState.LOCKED, pending = 3).message()
        )
    }

    @Test
    fun `one credential reads as one`() {
        assertEquals(
            "1 credential is waiting for your vault — tap to unlock",
            VaultStatus(VaultState.LOCKED, pending = 1).message()
        )
    }

    @Test
    fun `a household with no vault is asked to make one, not to unlock one`() {
        // Queued writes land the moment a vault is created, so this is a real offer rather than a
        // consolation — and telling somebody to unlock a vault they have never made is an
        // instruction they cannot follow.
        assertEquals(
            "2 credentials are waiting for a vault — tap to make one",
            VaultStatus(VaultState.ABSENT, pending = 2).message()
        )
    }
}
