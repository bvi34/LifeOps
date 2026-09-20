package com.utilities.app.messages

import com.utilities.app.messages.logic.Routing
import com.utilities.app.messages.logic.SendRefusal
import com.utilities.app.messages.logic.SendRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which protocol a message becomes.
 *
 * Four inputs and three answers, and every wrong combination is visible to somebody: a group
 * message sent as separate texts means everybody gets a private note with no idea the others were
 * written to; a one-line reply sent as a picture message counts against an allowance nobody meant
 * to spend; a photograph sent without the default-app role vanishes the moment it leaves.
 */
class RoutingTest {

    @Test
    fun `an ordinary reply is a text`() {
        val plan = Routing.plan(recipients = 1, attachments = 0, hasBody = true, isDefaultApp = true)
        assertEquals(SendRoute.TEXT, plan.route)
        assertNull(plan.refusal)
    }

    @Test
    fun `a picture is a picture message, and needs the role`() {
        assertEquals(
            SendRoute.PICTURE,
            Routing.plan(recipients = 1, attachments = 1, hasBody = false, isDefaultApp = true).route
        )

        // Only the default app may record a sent MMS. Sending one anyway would mean a photograph
        // that disappears the instant it leaves, which is worse than being told to switch.
        val without = Routing.plan(recipients = 1, attachments = 1, hasBody = true, isDefaultApp = false)
        assertEquals(SendRoute.REFUSE, without.route)
        assertEquals(SendRefusal.NEEDS_DEFAULT, without.refusal)
    }

    @Test
    fun `a group is a picture message when the household asked for group threads`() {
        assertEquals(
            SendRoute.PICTURE,
            Routing.plan(
                recipients = 3,
                attachments = 0,
                hasBody = true,
                groupAsPicture = true,
                isDefaultApp = true
            ).route
        )
    }

    @Test
    fun `a group is separate texts when they did not`() {
        assertEquals(
            SendRoute.TEXT,
            Routing.plan(
                recipients = 3,
                attachments = 0,
                hasBody = true,
                groupAsPicture = false,
                isDefaultApp = true
            ).route
        )
    }

    @Test
    fun `a group without the role still goes, as texts`() {
        // What every phone did before group messaging existed, and better than not sending.
        val plan = Routing.plan(
            recipients = 3,
            attachments = 0,
            hasBody = true,
            groupAsPicture = true,
            isDefaultApp = false
        )
        assertEquals(SendRoute.TEXT, plan.route)
        assertNull(plan.refusal)
    }

    @Test
    fun `a subject needs a picture message, because a text has nowhere to put one`() {
        assertEquals(
            SendRoute.PICTURE,
            Routing.plan(recipients = 1, attachments = 0, hasBody = true, hasSubject = true, isDefaultApp = true).route
        )
        // …and degrades to a text rather than refusing when that is not available. Losing a subject
        // line is a smaller loss than losing the message.
        assertEquals(
            SendRoute.TEXT,
            Routing.plan(recipients = 1, attachments = 0, hasBody = true, hasSubject = true, isDefaultApp = false).route
        )
    }

    @Test
    fun `nothing to send and nobody to send it to are different refusals`() {
        assertEquals(
            SendRefusal.NOBODY,
            Routing.plan(recipients = 0, attachments = 1, hasBody = true, isDefaultApp = true).refusal
        )
        assertEquals(
            SendRefusal.EMPTY,
            Routing.plan(recipients = 1, attachments = 0, hasBody = false, isDefaultApp = true).refusal
        )
    }

    @Test
    fun `no permission refuses before anything else is considered`() {
        val plan = Routing.plan(
            recipients = 2,
            attachments = 2,
            hasBody = true,
            canSend = false,
            isDefaultApp = true
        )
        assertEquals(SendRoute.REFUSE, plan.route)
        assertEquals(SendRefusal.NO_PERMISSION, plan.refusal)
    }

    @Test
    fun `a picture with no words is a message`() {
        assertEquals(
            SendRoute.PICTURE,
            Routing.plan(recipients = 1, attachments = 1, hasBody = false, isDefaultApp = true).route
        )
    }

    @Test
    fun `every refusal carries a sentence somebody can act on`() {
        SendRefusal.entries.forEach { refusal ->
            org.junit.Assert.assertTrue(refusal.name, refusal.reason.length > 15)
            org.junit.Assert.assertTrue(refusal.name, refusal.reason.trim().endsWith("."))
        }
    }
}
