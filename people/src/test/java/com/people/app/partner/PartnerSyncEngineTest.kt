package com.people.app.partner

import com.people.app.partner.PartnerSyncEngine.Rejection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PartnerSyncEngineTest {

    private val us = "inst-a"
    private val them = "inst-b"
    private val ourSecret = "secret-a"
    private val theirSecret = "secret-b"

    private val engine = PartnerSyncEngine(instanceId = us, displayName = "Sam")

    private fun link(partnerSecret: String = theirSecret) = PartnerSyncEngine.Link(
        partnerInstanceId = them,
        partnerName = "Marta",
        mySecret = ourSecret,
        partnerSecret = partnerSecret
    )

    private val thisWeek = SharedWeek("2026-08-31", "2026-09-06")
    private val lastWeek = SharedWeek("2026-08-24", "2026-08-30")
    private val emptyMirror = SharedWeek("", "")

    private val theirTask = SharedTask("t1", "Book the van", "2026-09-01")

    private fun theirEnvelope(
        instanceId: String = them,
        addressedTo: String = us,
        token: String = PairSecret.token(theirSecret, ourSecret),
        week: SharedWeek = thisWeek.copy(tasks = listOf(theirTask)),
        contributions: List<Contribution> = emptyList()
    ) = PartnerEnvelope(
        instanceId = instanceId,
        displayName = "Marta",
        issuedAt = 5_000L,
        shares = listOf(PartnerShare(addressedTo, token, week, contributions))
    )

    private fun apply(
        link: PartnerSyncEngine.Link = link(),
        envelope: PartnerEnvelope? = theirEnvelope(),
        previousMirror: SharedWeek = emptyMirror,
        ourWeek: SharedWeek = thisWeek,
        taken: Set<String> = emptySet()
    ) = engine.applyInbound(link, envelope, previousMirror, ourWeek, taken)

    @Test
    fun `a completed handshake confirms the link and mirrors their week`() {
        val applied = apply()

        assertTrue(applied.confirmed)
        assertNull(applied.rejection)
        assertEquals(listOf("t1"), applied.mirror.tasks.map { it.taskId })
    }

    @Test
    fun `the first sight of a week is mirrored without announcing every task as news`() {
        val applied = apply()

        assertTrue(applied.mirror.tasks.isNotEmpty())
        assertTrue("a week we have never held is not a week of changes", applied.changes.isEmpty())
    }

    @Test
    fun `once a week is held, what they do to it is reported`() {
        val first = apply()
        val second = apply(
            envelope = theirEnvelope(week = thisWeek.copy(tasks = listOf(theirTask.copy(done = true)))),
            previousMirror = first.mirror
        )

        assertEquals(PartnerWeekDiff.ChangeKind.COMPLETED, second.changes.single().kind)
    }

    @Test
    fun `a second round over the same envelope reports nothing new`() {
        val first = apply(previousMirror = thisWeek)
        val second = apply(previousMirror = first.mirror)

        assertTrue(second.changes.isEmpty())
        assertEquals(first.mirror, second.mirror)
    }

    @Test
    fun `a link we have not scanned yet publishes nothing and reads nothing`() {
        val half = link(partnerSecret = "")

        assertNull(engine.buildShare(half, thisWeek, emptyList()))

        val applied = apply(link = half)
        assertFalse(applied.confirmed)
        assertEquals(Rejection.NOTHING_PUBLISHED, applied.rejection)
        assertTrue(applied.mirror.tasks.isEmpty())
    }

    @Test
    fun `a partner who has not scanned us back is not yet connected`() {
        // They publish, but every share is addressed to somebody else: they never scanned our code.
        val applied = apply(envelope = theirEnvelope(addressedTo = "inst-c"))

        assertFalse(applied.confirmed)
        assertEquals(Rejection.NOT_ADDRESSED_TO_US, applied.rejection)
    }

    @Test
    fun `a share bearing the wrong token is refused, week and contributions alike`() {
        val applied = apply(
            envelope = theirEnvelope(
                token = "0".repeat(64),
                contributions = listOf(Contribution("c1", "Click here"))
            )
        )

        assertFalse(applied.confirmed)
        assertEquals(Rejection.TOKEN_MISMATCH, applied.rejection)
        assertTrue(applied.mirror.tasks.isEmpty())
        assertTrue("nothing may reach LifeOps from an unproven share", applied.accepted.isEmpty())
    }

    @Test
    fun `an envelope written by a different instance under their name is refused`() {
        assertEquals(Rejection.WRONG_INSTANCE, apply(envelope = theirEnvelope(instanceId = "inst-x")).rejection)
    }

    @Test
    fun `nothing published yet is an ordinary outcome, not an error`() {
        val applied = apply(envelope = null)

        assertFalse(applied.confirmed)
        assertEquals(Rejection.NOTHING_PUBLISHED, applied.rejection)
    }

    @Test
    fun `a stale week is not mirrored, and the mirror we hold is left alone`() {
        val held = thisWeek.copy(tasks = listOf(theirTask))

        val applied = apply(
            envelope = theirEnvelope(week = lastWeek.copy(tasks = listOf(SharedTask("old", "Last week")))),
            previousMirror = held
        )

        assertTrue(applied.confirmed)
        assertEquals(Rejection.DIFFERENT_WEEK, applied.rejection)
        assertEquals(held, applied.mirror)
    }

    @Test
    fun `a task they added to our week is accepted even while their own week is stale`() {
        val applied = apply(
            envelope = theirEnvelope(
                week = lastWeek,
                contributions = listOf(Contribution("c1", "Call the vet", "2026-09-02"))
            )
        )

        assertEquals(Rejection.DIFFERENT_WEEK, applied.rejection)
        assertEquals(listOf("c1"), applied.accepted.map { it.id })
    }

    @Test
    fun `a contribution already taken is never taken twice`() {
        val envelope = theirEnvelope(contributions = listOf(Contribution("c1", "Call the vet")))

        val first = apply(envelope = envelope)
        val second = apply(envelope = envelope, taken = setOf("c1"))

        assertEquals(listOf("c1"), first.accepted.map { it.id })
        assertTrue(second.accepted.isEmpty())
    }

    @Test
    fun `a mirrored week never becomes something to put on our own planner`() {
        // The only thing the caller is ever handed for LifeOps is `accepted`. Their week, however
        // many tasks it carries, contributes nothing to it — that is the whole boundary.
        val applied = apply(
            envelope = theirEnvelope(
                week = thisWeek.copy(
                    tasks = listOf(theirTask, SharedTask("t2", "Their other job", "2026-09-02"))
                )
            )
        )

        assertEquals(2, applied.mirror.tasks.size)
        assertTrue(applied.accepted.isEmpty())
    }

    @Test
    fun `our outbound share carries our week, our additions to theirs, and the pair token`() {
        val contribution = Contribution("c9", "Call the vet", "2026-09-02")
        val share = engine.buildShare(link(), thisWeek.copy(tasks = listOf(theirTask)), listOf(contribution))!!

        assertEquals(them, share.partnerInstanceId)
        assertEquals(PairSecret.token(ourSecret, theirSecret), share.token)
        assertEquals(listOf(contribution), share.contributions)

        val envelope = engine.buildOutbound(listOf(share), at = 9_000L)
        assertEquals(us, envelope.instanceId)
        assertEquals(share, envelope.shareFor(them))
    }
}
