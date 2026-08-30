package com.people.app.partner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDate

/**
 * Two whole instances, an exchange folder between them, and the pairing done the way two people do
 * it: each scans the other's code.
 *
 * The unit tests either side of this one check one rule at a time. This is here for the properties
 * none of them can see alone — that a task typed on one device reaches the other's planner, that
 * each side sees the *other's* week and not a merger of both, and that the round settles instead of
 * ping-ponging the same edit for ever.
 */
class TwoInstanceRoundTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val week = SharedWeeks.windowFor(LocalDate.parse("2026-08-31"))

    /**
     * One paired instance.
     *
     * [ownWeek] stands in for its LifeOps week — the one it publishes — and [mirror] for the copy of
     * its partner's week that People keeps. That they are separate fields here is the point being
     * tested: nothing a round does ever moves a task from one into the other.
     */
    private class Instance(val id: String, val name: String, val secret: String) {
        val engine = PartnerSyncEngine(id, name)
        var partnerSecret: String = ""
        var ownWeek: SharedWeek = SharedWeek("", "")
        var mirror: SharedWeek = SharedWeek("", "")
        var outbox = mutableListOf<Contribution>()
        val taken = mutableSetOf<String>()

        fun link(partner: Instance) = PartnerSyncEngine.Link(
            partnerInstanceId = partner.id,
            partnerName = partner.name,
            mySecret = secret,
            partnerSecret = partnerSecret
        )
    }

    /**
     * A round as the app runs it on open: read theirs, mirror it, create anything they added to our
     * week, then publish ours.
     */
    private fun round(store: PartnerEnvelopeStore, self: Instance, partner: Instance): PartnerSyncEngine.Applied {
        val applied = self.engine.applyInbound(
            link = self.link(partner),
            envelope = store.read(partner.id),
            previousMirror = self.mirror,
            ourWeek = self.ownWeek,
            takenContributionIds = self.taken
        )
        self.mirror = applied.mirror

        // What a contribution becomes: an ordinary task on *our* week, stamped so its author can
        // recognise it coming back.
        applied.accepted.forEach { add ->
            self.taken += add.id
            self.ownWeek = self.ownWeek.copy(
                tasks = self.ownWeek.tasks + SharedTask(
                    taskId = "local-${add.id}",
                    title = add.title,
                    dueDate = add.dueDate,
                    fromContribution = add.id
                )
            )
        }

        // A contribution stops being sent once it has landed on their week — recognised by the stamp.
        val landed = self.mirror.tasks.mapNotNull { it.fromContribution }.toSet()
        self.outbox.removeAll { it.id in landed }

        self.engine.buildShare(self.link(partner), self.ownWeek, self.outbox.toList())?.let { share ->
            store.write(self.engine.buildOutbound(listOf(share), at = 1L))
        }
        return applied
    }

    @Test
    fun `two instances pair by scanning each other, then each sees the other's week`() {
        val store = PartnerEnvelopeStore(folder.newFolder("exchange"))

        val sam = Instance("inst-sam", "Sam", PairSecret.mint())
        val marta = Instance("inst-marta", "Marta", PairSecret.mint())
        sam.ownWeek = week.copy(tasks = listOf(SharedTask("s1", "Book the van", "2026-09-01")))
        marta.ownWeek = week.copy(tasks = listOf(SharedTask("m1", "Pick up the keys", "2026-09-02")))

        // --- Sam scans Marta's code. One-way so far: Marta knows nothing about Sam. ---
        val martaInvite = PartnerInviteCodec.decode(
            PartnerInviteCodec.encode(PartnerInvite(marta.id, marta.name, "key-marta", marta.secret, 1L))
        )!!
        sam.partnerSecret = martaInvite.secret

        assertFalse("Marta has not scanned back yet", round(store, sam, marta).confirmed)

        // Marta's app cannot even read Sam's week: with no half-secret of his, she has no token.
        assertEquals(PartnerSyncEngine.Rejection.NOTHING_PUBLISHED, round(store, marta, sam).rejection)
        assertTrue(marta.mirror.tasks.isEmpty())

        // --- Marta scans Sam's code. Both halves now exist on both devices. ---
        val samInvite = PartnerInviteCodec.decode(
            PartnerInviteCodec.encode(PartnerInvite(sam.id, sam.name, "key-sam", sam.secret, 2L))
        )!!
        marta.partnerSecret = samInvite.secret

        val martaFirst = round(store, marta, sam)
        assertTrue(martaFirst.confirmed)
        assertEquals(listOf("Book the van"), martaFirst.mirror.tasks.map { it.title })
        assertTrue("first sight of a week is not a list of changes", martaFirst.changes.isEmpty())

        val samFirst = round(store, sam, marta)
        assertTrue(samFirst.confirmed)
        assertEquals(listOf("Pick up the keys"), samFirst.mirror.tasks.map { it.title })

        // Each sees the other's week, and neither week has grown the other's tasks.
        assertEquals(listOf("Book the van"), sam.ownWeek.tasks.map { it.title })
        assertEquals(listOf("Pick up the keys"), marta.ownWeek.tasks.map { it.title })
    }

    @Test
    fun `a task added while viewing a partner's week lands on their planner and comes back mirrored`() {
        val store = PartnerEnvelopeStore(folder.newFolder("exchange"))
        val (sam, marta) = paired(store)

        // Sam, looking at Marta's week, adds something to it.
        sam.outbox += Contribution("c1", "Call the vet", "2026-09-03", createdAt = 10L)
        round(store, sam, marta)

        // It becomes an ordinary task on Marta's own week — the one place this seam ever writes.
        val martaRound = round(store, marta, sam)
        assertEquals(listOf("c1"), martaRound.accepted.map { it.id })
        assertEquals(
            listOf("Pick up the keys", "Call the vet"),
            marta.ownWeek.tasks.map { it.title }
        )

        // And Sam sees it in his mirror of her week, no longer pending.
        round(store, sam, marta)
        assertTrue(sam.mirror.tasks.any { it.title == "Call the vet" })
        assertTrue("a landed contribution stops being resent", sam.outbox.isEmpty())

        // It never reached Sam's own week.
        assertEquals(listOf("Book the van"), sam.ownWeek.tasks.map { it.title })

        // Marta ticks it; Sam is told, once.
        marta.ownWeek = marta.ownWeek.copy(
            tasks = marta.ownWeek.tasks.map { if (it.title == "Call the vet") it.copy(done = true) else it }
        )
        round(store, marta, sam)

        val told = round(store, sam, marta)
        assertEquals(PartnerWeekDiff.ChangeKind.COMPLETED, told.changes.single().kind)
        assertEquals("Call the vet", told.changes.single().title)

        // Settled: further rounds are quiet on both sides.
        assertTrue(round(store, sam, marta).changes.isEmpty())
        assertTrue(round(store, marta, sam).changes.isEmpty())
    }

    @Test
    fun `a contribution is never taken twice, even after the task is deleted`() {
        val store = PartnerEnvelopeStore(folder.newFolder("exchange"))
        val (sam, marta) = paired(store)

        sam.outbox += Contribution("c1", "Call the vet", "2026-09-03", createdAt = 10L)
        round(store, sam, marta)
        round(store, marta, sam)
        assertEquals(2, marta.ownWeek.tasks.size)

        // Marta decides against it and deletes the task from her week. Sam's app has not yet seen it
        // land, so it is still in his outbox and goes out again.
        marta.ownWeek = marta.ownWeek.copy(tasks = marta.ownWeek.tasks.filterNot { it.title == "Call the vet" })
        round(store, sam, marta)

        val second = round(store, marta, sam)

        assertTrue("a decision already made is not re-offered", second.accepted.isEmpty())
        assertEquals(listOf("Pick up the keys"), marta.ownWeek.tasks.map { it.title })
    }

    @Test
    fun `a stranger who guessed the instance id cannot write a week or a task`() {
        val store = PartnerEnvelopeStore(folder.newFolder("exchange"))
        val (sam, marta) = paired(store)

        // Somebody who knows both instance ids — they are not secret — but neither half-secret.
        store.write(
            PartnerEnvelope(
                instanceId = marta.id,
                displayName = "Marta",
                issuedAt = 99L,
                shares = listOf(
                    PartnerShare(
                        partnerInstanceId = sam.id,
                        token = PairSecret.token("guessed", "wrong"),
                        week = week.copy(tasks = listOf(SharedTask("spam", "Click here"))),
                        contributions = listOf(Contribution("spam-add", "Click here too"))
                    )
                )
            )
        )

        val applied = round(store, sam, marta)

        assertEquals(PartnerSyncEngine.Rejection.TOKEN_MISMATCH, applied.rejection)
        assertTrue(applied.accepted.isEmpty())
        assertFalse(sam.mirror.tasks.any { it.title == "Click here" })
        assertEquals(listOf("Book the van"), sam.ownWeek.tasks.map { it.title })
    }

    @Test
    fun `an envelope survives the trip through the folder unchanged`() {
        val store = PartnerEnvelopeStore(folder.newFolder("exchange"))
        val envelope = PartnerEnvelope(
            instanceId = "inst-sam",
            displayName = "Sam",
            issuedAt = 42L,
            shares = listOf(
                PartnerShare(
                    partnerInstanceId = "inst-marta",
                    token = PairSecret.token("a", "b"),
                    week = week.copy(
                        tasks = listOf(
                            SharedTask("t1", "Book the van", "2026-09-01", done = true),
                            SharedTask("t2", "Undated", null, false, fromContribution = "c1")
                        )
                    ),
                    contributions = listOf(Contribution("c2", "Call the vet", "2026-09-03", createdAt = 7L))
                )
            )
        )

        store.write(envelope)

        assertEquals(envelope, store.read("inst-sam"))
    }

    @Test
    fun `unlinking forgets the partner's envelope`() {
        val dir = folder.newFolder("exchange")
        val store = PartnerEnvelopeStore(dir)
        store.write(PartnerEnvelope(instanceId = "inst-marta", displayName = "Marta"))

        store.forget("inst-marta")

        assertNull(store.read("inst-marta"))
    }

    @Test
    fun `an instance id from a scanned code cannot name a file outside the folder`() {
        val dir = folder.newFolder("exchange")
        val store = PartnerEnvelopeStore(dir)

        store.write(PartnerEnvelope(instanceId = "../../escape", displayName = "?"))

        assertEquals(listOf("______escape-partner.json"), dir.list()!!.toList())
    }

    /** Two instances that have already scanned each other and exchanged one round. */
    private fun paired(store: PartnerEnvelopeStore): Pair<Instance, Instance> {
        val sam = Instance("inst-sam", "Sam", PairSecret.mint())
        val marta = Instance("inst-marta", "Marta", PairSecret.mint())
        sam.partnerSecret = marta.secret
        marta.partnerSecret = sam.secret
        sam.ownWeek = week.copy(tasks = listOf(SharedTask("s1", "Book the van", "2026-09-01")))
        marta.ownWeek = week.copy(tasks = listOf(SharedTask("m1", "Pick up the keys", "2026-09-02")))

        round(store, sam, marta)
        round(store, marta, sam)
        round(store, sam, marta)
        return sam to marta
    }
}
