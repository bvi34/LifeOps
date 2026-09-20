package com.utilities.app.shelf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the shelf says, and — the part that is easy to get wrong — what it offers to do next.
 *
 * The whole app is a screen that reads a handful of platform facts and turns them into a sentence
 * and a button. Both halves are judgement, both are wrong in ways a compiler cannot see, and both
 * are here rather than in a Compose file for exactly that reason.
 */
class TakeoversTest {

    @Test
    fun `a keyboard that is installed but not selected is half-done, not off`() {
        // The state that matters most: from the outside it looks identical to nothing having
        // happened, because the household is still typing on the old keyboard.
        val half = Takeovers.keyboard(enabled = true, selected = false)
        assertEquals(TakeoverState.PARTIAL, half.state)
        assertNotNull("there is something to do about it", half.nextStep)
        assertTrue(half.detail.contains("another keyboard"))
    }

    @Test
    fun `a keyboard that is neither is off, and a selected one is done`() {
        assertEquals(TakeoverState.OFF, Takeovers.keyboard(enabled = false, selected = false).state)

        val done = Takeovers.keyboard(enabled = true, selected = true)
        assertEquals(TakeoverState.ON, done.state)
        assertNull("nothing left to offer", done.nextStep)
    }

    @Test
    fun `messages has two rungs and knows which one it is on`() {
        val off = Takeovers.messages(canRead = false, canSend = false, isDefault = false)
        assertEquals(TakeoverState.OFF, off.state)

        val reading = Takeovers.messages(canRead = true, canSend = true, isDefault = false)
        assertEquals(TakeoverState.PARTIAL, reading.state)
        assertEquals("Make it the default", reading.nextStep)

        val whole = Takeovers.messages(canRead = true, canSend = true, isDefault = true)
        assertEquals(TakeoverState.ON, whole.state)
        assertNull(whole.nextStep)
    }

    @Test
    fun `reading without sending is still the reading rung, and says what is missing`() {
        val readOnly = Takeovers.messages(canRead = true, canSend = false, isDefault = false)
        assertEquals(TakeoverState.PARTIAL, readOnly.state)
        assertEquals("Allow sending", readOnly.nextStep)
    }

    @Test
    fun `a finished takeover can still have something to offer`() {
        // The case a state comparison would get wrong: contacts are optional, so the takeover is ON
        // and there is still a button. The UI keys off nextStep rather than off the state.
        val noContacts = Takeovers.messages(
            canRead = true,
            canSend = true,
            isDefault = true,
            canReadContacts = false
        )
        assertEquals(TakeoverState.ON, noContacts.state)
        assertEquals("Allow contacts", noContacts.nextStep)
        assertTrue(noContacts.detail.contains("number"))
    }

    @Test
    fun `a device with no radio is told so rather than offered a button`() {
        val tablet = Takeovers.messages(
            canRead = false,
            canSend = false,
            isDefault = false,
            hasTelephony = false
        )
        assertEquals(TakeoverState.UNAVAILABLE, tablet.state)
        assertNull(tablet.nextStep)
    }

    @Test
    fun `every takeover says what it replaces and what it stops`() {
        Utility.entries.forEach { utility ->
            assertTrue(utility.title.isNotBlank())
            assertTrue(utility.blurb.isNotBlank())
            assertTrue("${utility.title} does not say what it is for", utility.leak.length > 40)
        }
        assertEquals(Utility.entries.size, Utility.entries.map { it.id }.toSet().size)
        assertEquals(Utility.KEYBOARD, Utility.byId("keyboard"))
        assertNull(Utility.byId("dialler"))
    }

    @Test
    fun `the warning before the role picker says what is lost and that it is reversible`() {
        // This constant is the app's one honest admission, and it must be impossible to change the
        // behaviour without walking past it.
        assertTrue(Takeovers.mmsWarning.contains("text messages only"))
        assertTrue(Takeovers.mmsWarning.contains("picture"))
        assertTrue(Takeovers.mmsWarning.contains("switch back", ignoreCase = true))
    }
}
