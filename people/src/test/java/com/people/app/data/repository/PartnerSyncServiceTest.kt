package com.people.app.data.repository

import com.people.app.partner.Contribution
import com.people.app.partner.HouseholdWeek
import com.people.app.partner.PartnerEnvelopeStore
import com.people.app.partner.PartnerInvite
import com.people.app.partner.PartnerSyncEngine
import com.people.app.partner.SharedTask
import com.people.app.partner.SharedWeek
import com.people.app.partner.SharedWeeks
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate

/**
 * The service around the engine: what a round leaves behind, including the round somebody runs
 * before they have paired with anybody.
 *
 * That last case is the one this suite was added for. Setting the seam up and pairing with a person
 * are different acts — the first mints this install's identity and creates the folder envelopes are
 * exchanged in, the second needs two people and two scans — and the app has a button for the first
 * only because a round with no links does something rather than returning early.
 */
class PartnerSyncServiceTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val dao = FakePartnerDao()
    private val repository = PartnerRepository(dao)

    /** Deliberately not created up front: creating it is part of what is being tested. */
    private val syncDir: File get() = File(folder.root, "partner-sync")

    private var lastRoundAt = 0L

    private val week = SharedWeeks.windowFor(LocalDate.parse("2026-08-31"))

    private fun service(householdWeek: HouseholdWeek? = null) = PartnerSyncService(
        repository = repository,
        syncDir = syncDir,
        instanceId = { "us" },
        displayName = { "Our house" },
        householdWeek = { householdWeek },
        markRoundAt = { lastRoundAt = it }
    )

    @Test
    fun `a round with nobody paired still sets the seam up`() = runTest {
        assertFalse("the folder should not exist before the first round", syncDir.exists())

        val status = service().sync()

        assertEquals(0, status.linksSynced)
        assertTrue("the round should have created the exchange folder", syncDir.isDirectory)
        assertTrue("the round should have been stamped", lastRoundAt > 0L)

        val published = PartnerEnvelopeStore(syncDir).read("us")
        assertNotNull("a household with no partners still publishes an envelope", published)
        assertEquals("us", published!!.instanceId)
        assertEquals("Our house", published.displayName)
        assertTrue("setting up pairs nobody", published.shares.isEmpty())
    }

    @Test
    fun `setting up twice republishes rather than accumulating`() = runTest {
        val service = service()
        service.sync()
        service.sync()

        // One file, and no `.tmp` left over from the write-then-rename.
        assertEquals(listOf("us-partner.json"), syncDir.list()!!.sorted())
    }

    @Test
    fun `a paired round publishes our week to them and mirrors theirs`() = runTest {
        val ourWeek = week.copy(tasks = listOf(SharedTask("t-1", "Fix the gate")))
        val link = pair(partnerSecret = "their-half")
        val service = service(householdWeek = FixedWeek(ourWeek))

        service.sync()

        val addressed = PartnerEnvelopeStore(syncDir).read("us")?.shareFor("partner")
        assertNotNull("their share should be in our envelope once they are scanned", addressed)
        assertEquals(listOf("Fix the gate"), addressed!!.week.tasks.map { it.title })

        // Now their side answers, addressing the same pairing with the token only both halves make.
        publishFromPartner(mySecret = "their-half", theirSecret = link.mySecret)
        val second = service.sync()

        assertEquals(1, second.linksSynced)
        assertTrue("their week arriving is news", second.changes > 0)
        assertEquals(
            listOf("Book the van"),
            repository.mirror(link.id).tasks.map { it.title }
        )
    }

    /** Scan a partner's code, as the person screen does — the only way a link is ever completed. */
    private suspend fun pair(partnerSecret: String) = run {
        val result = repository.acceptScan(
            personId = "person-1",
            invite = PartnerInvite(
                instanceId = "partner",
                displayName = "Marta",
                personKey = "marta-key",
                secret = partnerSecret,
                issuedAt = 0L
            ),
            ourInstanceId = "us"
        )
        (result as PartnerRepository.ScanResult.Paired).link
    }

    /** Their instance publishing its own envelope into the same folder. */
    private fun publishFromPartner(mySecret: String, theirSecret: String) {
        val engine = PartnerSyncEngine("partner", "Marta")
        val share = engine.buildShare(
            link = PartnerSyncEngine.Link(
                partnerInstanceId = "us",
                partnerName = "Our house",
                mySecret = mySecret,
                partnerSecret = theirSecret
            ),
            myWeek = week.copy(tasks = listOf(SharedTask("t-9", "Book the van"))),
            contributions = emptyList()
        )
        PartnerEnvelopeStore(syncDir).write(engine.buildOutbound(listOfNotNull(share), at = 1L))
    }

    /** A planner that answers with one week and refuses nothing — the seam's own week is not the subject here. */
    private class FixedWeek(private val week: SharedWeek) : HouseholdWeek {
        override suspend fun current(): SharedWeek = week
        override suspend fun createTask(contribution: Contribution, partnerName: String): String? =
            contribution.id
    }
}
