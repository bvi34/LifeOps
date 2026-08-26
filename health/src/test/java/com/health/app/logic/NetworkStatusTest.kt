package com.health.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading a doctor's network status out of the history of every check.
 *
 * The cases are the ones a single stored "in network" flag gets wrong: a doctor who has quietly left
 * the network, a directory that was down this morning, four people with the same surname, and
 * somebody's word on the phone being kept as somebody's word on the phone.
 */
class NetworkStatusTest {

    private val now = 1_776_000_000_000L
    private fun daysAgo(days: Long) = now - days * 24L * 60L * 60L * 1000L

    private fun check(
        days: Long,
        outcome: CheckOutcome,
        networks: List<String> = emptyList(),
        directory: String? = "Meridian Mutual",
        detail: String? = null
    ) = NetworkCheckRecord(
        checkedAt = daysAgo(days),
        outcome = outcome,
        directoryLabel = directory,
        networks = networks,
        detail = detail
    )

    @Test
    fun `nobody has asked, so Health has no opinion`() {
        val assessment = NetworkStatus.evaluate(emptyList(), now)
        assertEquals(NetworkVerdict.UNCHECKED, assessment.verdict)
        assertNull(assessment.lastCheckedAt)
        assertNull(assessment.lastListedAt)
        assertFalse(assessment.stale)
    }

    @Test
    fun `a current listing names the network it was found in`() {
        val assessment = NetworkStatus.evaluate(
            listOf(check(2, CheckOutcome.LISTED, networks = listOf("Choice Plus PPO"))),
            now
        )
        assertEquals(NetworkVerdict.IN_NETWORK, assessment.verdict)
        assertEquals(listOf("Choice Plus PPO"), assessment.networks)
        // "Listed in this payer's directory" and "in the network your plan buys" are not the same
        // sentence, so the network is part of the answer rather than a detail behind it.
        assertTrue(assessment.summary.contains("Choice Plus PPO"))
        assertFalse(assessment.needsAttention)
    }

    @Test
    fun `listed once and not listed now is a doctor who has left the network`() {
        val assessment = NetworkStatus.evaluate(
            listOf(
                check(120, CheckOutcome.LISTED, networks = listOf("Choice Plus PPO")),
                check(1, CheckOutcome.NOT_LISTED)
            ),
            now
        )
        assertEquals(NetworkVerdict.DROPPED, assessment.verdict)
        assertTrue(assessment.needsAttention)
        assertEquals(daysAgo(120), assessment.lastListedAt)
        assertTrue(assessment.summary.contains("ringing the office"))
    }

    @Test
    fun `never found in any directory is a different fact and says so`() {
        val assessment = NetworkStatus.evaluate(
            listOf(check(30, CheckOutcome.NOT_LISTED), check(1, CheckOutcome.NOT_LISTED)),
            now
        )
        assertEquals(NetworkVerdict.NEVER_LISTED, assessment.verdict)
        assertNull(assessment.lastListedAt)
        assertTrue(assessment.summary.contains("no earlier check"))
    }

    @Test
    fun `a directory that is down does not overwrite the answer it gave last month`() {
        val assessment = NetworkStatus.evaluate(
            listOf(
                check(20, CheckOutcome.LISTED, networks = listOf("Choice Plus PPO")),
                check(0, CheckOutcome.UNAVAILABLE, detail = "The directory couldn't be reached.")
            ),
            now
        )
        // An outage is not evidence about a doctor. Treating it as one would flip a whole care team
        // to "unknown" every time a payer's server hiccuped.
        assertEquals(NetworkVerdict.IN_NETWORK, assessment.verdict)
        assertEquals(daysAgo(0), assessment.lastCheckedAt)
    }

    @Test
    fun `an unreachable directory with nothing behind it reports itself`() {
        val assessment = NetworkStatus.evaluate(
            listOf(check(0, CheckOutcome.UNAVAILABLE, detail = "Nothing published at that address.")),
            now
        )
        assertEquals(NetworkVerdict.UNAVAILABLE, assessment.verdict)
        assertTrue(assessment.summary.contains("Nothing published"))
    }

    @Test
    fun `four doctors with one surname is not a yes`() {
        val assessment = NetworkStatus.evaluate(listOf(check(1, CheckOutcome.AMBIGUOUS)), now)
        assertEquals(NetworkVerdict.AMBIGUOUS, assessment.verdict)
        assertTrue(assessment.summary.contains("NPI"))
    }

    @Test
    fun `a name that has become ambiguous unsettles an older yes`() {
        val assessment = NetworkStatus.evaluate(
            listOf(check(60, CheckOutcome.LISTED), check(1, CheckOutcome.AMBIGUOUS)),
            now
        )
        // The directory has since grown a second person with that name; the old yes may have been
        // about either of them.
        assertEquals(NetworkVerdict.AMBIGUOUS, assessment.verdict)
    }

    @Test
    fun `ambiguity does not bury the fact that they were dropped`() {
        val assessment = NetworkStatus.evaluate(
            listOf(
                check(200, CheckOutcome.LISTED),
                check(30, CheckOutcome.NOT_LISTED),
                check(1, CheckOutcome.AMBIGUOUS)
            ),
            now
        )
        assertEquals(NetworkVerdict.DROPPED, assessment.verdict)
    }

    @Test
    fun `a phone confirmation stays a phone confirmation`() {
        val assessment = NetworkStatus.evaluate(listOf(check(3, CheckOutcome.CONFIRMED_BY_HAND)), now)
        // Somebody was told something once. That is worth recording and is not a published listing.
        assertEquals(NetworkVerdict.CONFIRMED_BY_PHONE, assessment.verdict)
        assertTrue(assessment.summary.contains("not a published listing"))
    }

    @Test
    fun `being told no on the phone is worth keeping`() {
        val assessment = NetworkStatus.evaluate(
            listOf(check(90, CheckOutcome.LISTED), check(2, CheckOutcome.DECLINED_BY_HAND)),
            now
        )
        assertEquals(NetworkVerdict.DECLINED, assessment.verdict)
        assertTrue(assessment.needsAttention)
    }

    @Test
    fun `an answer from before the last renewal is flagged as one`() {
        val fresh = NetworkStatus.evaluate(listOf(check(10, CheckOutcome.LISTED)), now)
        val old = NetworkStatus.evaluate(listOf(check(200, CheckOutcome.LISTED)), now)
        assertFalse(fresh.stale)
        assertTrue(old.stale)
        assertTrue(old.summary.contains("Worth checking again"))
    }

    @Test
    fun `trouble sorts to the top of a care team`() {
        val ranked = listOf(
            NetworkVerdict.IN_NETWORK,
            NetworkVerdict.UNCHECKED,
            NetworkVerdict.DROPPED,
            NetworkVerdict.NEVER_LISTED
        ).sortedBy { it.sortRank }
        assertEquals(
            listOf(
                NetworkVerdict.DROPPED,
                NetworkVerdict.NEVER_LISTED,
                NetworkVerdict.UNCHECKED,
                NetworkVerdict.IN_NETWORK
            ),
            ranked
        )
    }

    @Test
    fun `checks are read in time order however they arrive`() {
        val jumbled = listOf(
            check(1, CheckOutcome.NOT_LISTED),
            check(300, CheckOutcome.LISTED),
            check(150, CheckOutcome.LISTED)
        )
        assertEquals(NetworkVerdict.DROPPED, NetworkStatus.evaluate(jumbled, now).verdict)
        assertEquals(daysAgo(150), NetworkStatus.evaluate(jumbled, now).lastListedAt)
    }

    @Test
    fun `a check's age reads the way somebody would say it`() {
        assertEquals("today", NetworkStatus.describeAge(daysAgo(0), now))
        assertEquals("yesterday", NetworkStatus.describeAge(daysAgo(1), now))
        assertEquals("5 days ago", NetworkStatus.describeAge(daysAgo(5), now))
        assertEquals("3 weeks ago", NetworkStatus.describeAge(daysAgo(21), now))
        assertEquals("4 months ago", NetworkStatus.describeAge(daysAgo(120), now))
    }
}
