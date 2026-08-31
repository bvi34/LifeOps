package com.people.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * The check-in's rules: what a question accepts, what it stores, and when a run of days is unbroken.
 *
 * Every case here is one somebody's evening actually produces — a field left alone, a number typed
 * as words, a yes that arrived as "true", a form retyped after it had answers, and the morning after
 * a day nobody has filled in yet.
 */
class CheckInsTest {

    private val monday = LocalDate.parse("2026-08-31")

    // --- what gets stored ---

    @Test
    fun `an untouched question stores nothing`() {
        assertNull(CheckIns.clean(CheckInKind.TEXT, null))
        assertNull(CheckIns.clean(CheckInKind.TEXT, ""))
        assertNull(CheckIns.clean(CheckInKind.NOTE, "   "))
        // The distinction the whole answers table turns on: nothing said is not the same as "no".
        assertNull(CheckIns.clean(CheckInKind.YES_NO, ""))
    }

    @Test
    fun `text is kept as written, trimmed`() {
        assertEquals("Pasta and peas", CheckIns.clean(CheckInKind.TEXT, "  Pasta and peas "))
    }

    @Test
    fun `a number has to be one`() {
        assertEquals("2.5", CheckIns.clean(CheckInKind.NUMBER, " 2.5 "))
        assertEquals("11", CheckIns.clean(CheckInKind.NUMBER, "11"))
        assertNull(CheckIns.clean(CheckInKind.NUMBER, "about three"))
    }

    @Test
    fun `yes and no arrive in several spellings and store as one`() {
        assertEquals(CheckIns.YES, CheckIns.clean(CheckInKind.YES_NO, "Yes"))
        assertEquals(CheckIns.YES, CheckIns.clean(CheckInKind.YES_NO, "true"))
        assertEquals(CheckIns.NO, CheckIns.clean(CheckInKind.YES_NO, "N"))
        assertNull(CheckIns.clean(CheckInKind.YES_NO, "sort of"))
    }

    @Test
    fun `a scale is one of its five steps`() {
        assertEquals("4", CheckIns.clean(CheckInKind.SCALE, "4"))
        assertNull(CheckIns.clean(CheckInKind.SCALE, "0"))
        assertNull(CheckIns.clean(CheckInKind.SCALE, "6"))
        assertNull(CheckIns.clean(CheckInKind.SCALE, "good"))
    }

    @Test
    fun `a choice has to be one of the options, and is stored as the option is written`() {
        val options = listOf("School", "Packed", "Home")
        assertEquals("Packed", CheckIns.clean(CheckInKind.CHOICE, "packed", options))
        assertNull(CheckIns.clean(CheckInKind.CHOICE, "Skipped", options))
    }

    @Test
    fun `an answer that no longer fits its question is dropped rather than thrown`() {
        // The sequence this guards: a question was answered as text, then retyped to a scale. The
        // stale value must not reach the database dressed as a rating.
        assertNull(CheckIns.clean(CheckInKind.SCALE, "sandwiches"))
        // And an option removed from the list after somebody picked it.
        assertNull(CheckIns.clean(CheckInKind.CHOICE, "Packed", listOf("School", "Home")))
    }

    // --- options ---

    @Test
    fun `options drop blanks and duplicates, and an empty list is no options at all`() {
        assertEquals("School\nHome", CheckIns.encodeOptions(listOf(" School ", "", "Home", "School")))
        assertNull(CheckIns.encodeOptions(listOf(" ", "")))
        assertEquals(listOf("School", "Home"), CheckIns.decodeOptions("School\nHome"))
        assertEquals(emptyList<String>(), CheckIns.decodeOptions(null))
    }

    // --- how it reads back ---

    @Test
    fun `values read as a person would say them`() {
        assertEquals("Yes", CheckIns.display(CheckInKind.YES_NO, CheckIns.YES))
        assertEquals("No", CheckIns.display(CheckInKind.YES_NO, CheckIns.NO))
        assertEquals("3 of 5", CheckIns.display(CheckInKind.SCALE, "3"))
        assertEquals("Pasta", CheckIns.display(CheckInKind.TEXT, "Pasta"))
    }

    @Test
    fun `a summary takes the first answers and counts the rest`() {
        val summary = CheckIns.summarise(
            listOf("Lunch" to "Pasta", "Mood" to "4 of 5", "Nap" to "Yes", "Notes" to "Long day")
        )
        assertEquals("Lunch: Pasta · Mood: 4 of 5 · +2 more", summary)
    }

    @Test
    fun `a summary of nothing says so rather than being blank`() {
        assertEquals("Nothing recorded", CheckIns.summarise(emptyList()))
    }

    @Test
    fun `a long answer is cut at a word`() {
        val summary = CheckIns.summarise(
            listOf("Notes" to "They told me about the whole assembly on the way home"),
            fields = 1,
            width = 20
        )
        assertEquals("Notes: They told me about…", summary)
    }

    // --- streaks ---

    @Test
    fun `a run counts back from today`() {
        val days = listOf(monday, monday.minusDays(1), monday.minusDays(2))
        assertEquals(3, CheckIns.streak(days, monday))
    }

    @Test
    fun `today not being done yet does not break the run`() {
        // The decision worth having a test for: a check-in is an evening habit, and a streak that
        // reset at midnight would report a broken run at breakfast.
        val days = listOf(monday.minusDays(1), monday.minusDays(2))
        assertEquals(2, CheckIns.streak(days, monday))
    }

    @Test
    fun `a missed day ends the run, however much came before it`() {
        val days = listOf(monday, monday.minusDays(2), monday.minusDays(3), monday.minusDays(4))
        assertEquals(1, CheckIns.streak(days, monday))
    }

    @Test
    fun `no days at all is no run`() {
        assertEquals(0, CheckIns.streak(emptyList(), monday))
    }

    @Test
    fun `days are counted once however often they appear`() {
        assertEquals(1, CheckIns.streak(listOf(monday, monday), monday))
    }

    // --- days ---

    @Test
    fun `the two days anybody fills in are named rather than dated`() {
        assertEquals("Today", CheckIns.describeDay(monday, monday))
        assertEquals("Yesterday", CheckIns.describeDay(monday.minusDays(1), monday))
        assertEquals("28 Aug", CheckIns.describeDay(monday.minusDays(3), monday))
    }

    @Test
    fun `a day that is not a day reads as null rather than throwing`() {
        assertEquals(monday, CheckIns.parseDay("2026-08-31"))
        assertNull(CheckIns.parseDay("last tuesday"))
        assertNull(CheckIns.parseDay(null))
    }
}
