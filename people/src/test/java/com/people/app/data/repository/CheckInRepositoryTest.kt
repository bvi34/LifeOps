package com.people.app.data.repository

import com.people.app.logic.CheckInKind
import com.people.app.logic.CheckIns
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The check-in store's two rules, and the form editing around them: a day exists only if it says
 * something, and taking a question off the form never takes its answers with it.
 */
class CheckInRepositoryTest {

    private val dao = FakeCheckInDao()
    private val repo = CheckInRepository(dao)

    private val person = "person-1"
    private val monday = LocalDate.parse("2026-08-31")

    // --- recording a day ---

    @Test
    fun `a day records only the questions that were answered`() = runTest {
        val lunch = repo.addField(person, "Lunch", CheckInKind.TEXT)
        val mood = repo.addField(person, "How the day went", CheckInKind.SCALE)
        repo.addField(person, "Anything else", CheckInKind.NOTE)

        val recorded = repo.save(person, monday, mapOf(lunch to "Pasta", mood to "4"))

        assertTrue(recorded)
        val day = repo.observeCheckIn(person, monday).first()
        assertNotNull(day)
        assertEquals(
            listOf("Lunch" to "Pasta", "How the day went" to "4"),
            day!!.answers.map { it.label to it.value }
        )
    }

    @Test
    fun `answers come back in form order, not in the order they were typed`() = runTest {
        val lunch = repo.addField(person, "Lunch", CheckInKind.TEXT)
        val mood = repo.addField(person, "Mood", CheckInKind.SCALE)

        repo.save(person, monday, mapOf(mood to "5", lunch to "Sandwiches"))

        val day = repo.observeCheckIn(person, monday).first()
        assertEquals(listOf("Lunch", "Mood"), day!!.answers.map { it.label })
    }

    @Test
    fun `an answer that does not fit its question is left out`() = runTest {
        val mood = repo.addField(person, "Mood", CheckInKind.SCALE)
        val lunch = repo.addField(person, "Lunch", CheckInKind.TEXT)

        repo.save(person, monday, mapOf(mood to "brilliant", lunch to "Pasta"))

        val day = repo.observeCheckIn(person, monday).first()
        assertEquals(listOf("Lunch"), day!!.answers.map { it.label })
    }

    @Test
    fun `a form nobody filled in records no day at all`() = runTest {
        val lunch = repo.addField(person, "Lunch", CheckInKind.TEXT)

        val recorded = repo.save(person, monday, mapOf(lunch to "   "))

        assertFalse(recorded)
        assertNull(repo.observeCheckIn(person, monday).first())
        // And nothing to count: a blank day must not prop up a streak.
        assertEquals(emptyList<LocalDate>(), repo.observeDays(person).first())
    }

    @Test
    fun `clearing every answer removes the day rather than leaving an empty one`() = runTest {
        val lunch = repo.addField(person, "Lunch", CheckInKind.TEXT)
        repo.save(person, monday, mapOf(lunch to "Pasta"))

        val recorded = repo.save(person, monday, mapOf(lunch to ""))

        assertFalse(recorded)
        assertNull(repo.observeCheckIn(person, monday).first())
    }

    @Test
    fun `saving the same day again replaces its answers rather than adding a second set`() = runTest {
        val lunch = repo.addField(person, "Lunch", CheckInKind.TEXT)
        val mood = repo.addField(person, "Mood", CheckInKind.SCALE)
        repo.save(person, monday, mapOf(lunch to "Pasta", mood to "3"))

        repo.save(person, monday, mapOf(lunch to "Pasta and peas", mood to ""))

        val day = repo.observeCheckIn(person, monday).first()
        assertEquals(listOf("Lunch" to "Pasta and peas"), day!!.answers.map { it.label to it.value })
        assertEquals(listOf(monday), repo.observeDays(person).first())
    }

    @Test
    fun `each day is its own row and the recent list is newest first`() = runTest {
        val lunch = repo.addField(person, "Lunch", CheckInKind.TEXT)
        repo.save(person, monday.minusDays(1), mapOf(lunch to "Soup"))
        repo.save(person, monday, mapOf(lunch to "Pasta"))

        val recent = repo.observeRecent(person).first()

        assertEquals(listOf(monday, monday.minusDays(1)), recent.map { it.day })
        assertEquals(2, CheckIns.streak(repo.observeDays(person).first(), monday))
    }

    // --- editing the form ---

    @Test
    fun `a question nobody answered is deleted outright`() = runTest {
        val typo = repo.addField(person, "Lnuch", CheckInKind.TEXT)

        val deleted = repo.removeField(typo)

        assertTrue(deleted)
        assertEquals(emptyList<String>(), repo.observeFields(person).first().map { it.label })
    }

    @Test
    fun `a question that has answers is retired, and keeps naming them`() = runTest {
        val lunch = repo.addField(person, "Lunch", CheckInKind.TEXT)
        repo.save(person, monday, mapOf(lunch to "Pasta"))

        val deleted = repo.removeField(lunch)

        assertFalse("an answered question must not be deleted", deleted)
        // Off the form...
        assertEquals(emptyList<String>(), repo.observeForm(person).first().map { it.label })
        // ...and still there, naming the day it recorded.
        assertEquals(listOf("Lunch"), repo.observeFields(person).first().map { it.label })
        val day = repo.observeCheckIn(person, monday).first()
        assertEquals(listOf("Lunch" to "Pasta"), day!!.answers.map { it.label to it.value })
    }

    @Test
    fun `a retired question can be put back, at the end of the form`() = runTest {
        val lunch = repo.addField(person, "Lunch", CheckInKind.TEXT)
        val mood = repo.addField(person, "Mood", CheckInKind.SCALE)
        repo.save(person, monday, mapOf(lunch to "Pasta"))
        repo.removeField(lunch)

        repo.restoreField(lunch)

        assertEquals(listOf("Mood", "Lunch"), repo.observeForm(person).first().map { it.label })
        assertEquals(mood, repo.observeForm(person).first().first().id)
    }

    @Test
    fun `renaming a question renames it on every day it has already recorded`() = runTest {
        val lunch = repo.addField(person, "Lnuch", CheckInKind.TEXT)
        repo.save(person, monday, mapOf(lunch to "Pasta"))

        repo.updateField(lunch, "Lunch", CheckInKind.TEXT)

        val day = repo.observeCheckIn(person, monday).first()
        assertEquals(listOf("Lunch"), day!!.answers.map { it.label })
    }

    @Test
    fun `a question keeps the kind it was asked with once it has answers`() = runTest {
        val lunch = repo.addField(person, "Lunch", CheckInKind.TEXT)
        repo.save(person, monday, mapOf(lunch to "Pasta"))

        assertFalse(repo.canRetype(lunch))
        repo.updateField(lunch, "Lunch", CheckInKind.SCALE)

        // Still text — reading "Pasta" back as a rating would misrepresent what was recorded.
        assertEquals(CheckInKind.TEXT, repo.observeForm(person).first().first().kind)
    }

    @Test
    fun `an unanswered question can still be retyped`() = runTest {
        val mood = repo.addField(person, "Mood", CheckInKind.TEXT)

        assertTrue(repo.canRetype(mood))
        repo.updateField(mood, "Mood", CheckInKind.SCALE)

        assertEquals(CheckInKind.SCALE, repo.observeForm(person).first().first().kind)
    }

    @Test
    fun `moving a question resequences the whole form`() = runTest {
        repo.addField(person, "Lunch", CheckInKind.TEXT)
        val mood = repo.addField(person, "Mood", CheckInKind.SCALE)
        repo.addField(person, "Notes", CheckInKind.NOTE)

        repo.moveField(mood, up = true)

        val form = repo.observeForm(person).first()
        assertEquals(listOf("Mood", "Lunch", "Notes"), form.map { it.label })
        assertEquals(listOf(0, 1, 2), form.map { it.position })
    }

    @Test
    fun `moving past either end of the form does nothing`() = runTest {
        val lunch = repo.addField(person, "Lunch", CheckInKind.TEXT)
        repo.addField(person, "Mood", CheckInKind.SCALE)

        repo.moveField(lunch, up = true)

        assertEquals(listOf("Lunch", "Mood"), repo.observeForm(person).first().map { it.label })
    }

    @Test
    fun `a choice question only accepts its own options`() = runTest {
        val lunch = repo.addField(person, "Lunch", CheckInKind.CHOICE, listOf("School", "Packed"))

        repo.save(person, monday, mapOf(lunch to "packed"))
        assertEquals(
            listOf("Packed"),
            repo.observeCheckIn(person, monday).first()!!.answers.map { it.value }
        )

        repo.save(person, monday, mapOf(lunch to "Chips"))
        assertNull(repo.observeCheckIn(person, monday).first())
    }

    @Test
    fun `the starter form is there for somebody who does not want a blank page`() = runTest {
        repo.addStarterForm(person)

        val form = repo.observeForm(person).first()
        assertEquals(CheckIns.STARTER_FORM.map { it.first }, form.map { it.label })
        assertEquals(listOf(0, 1, 2, 3), form.map { it.position })
    }

    @Test
    fun `only people with a form and no day recorded are outstanding`() = runTest {
        val lunch = repo.addField(person, "Lunch", CheckInKind.TEXT)
        val other = "person-2"
        repo.addField(other, "Lunch", CheckInKind.TEXT)

        repo.save(person, monday, mapOf(lunch to "Pasta"))

        assertEquals(setOf(other), repo.observeOutstanding(monday).first())
    }
}
