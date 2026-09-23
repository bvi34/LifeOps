package com.lifeops.app.util

import com.lifeops.app.data.model.ObjectiveStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ObjectivesTest {

    private fun step(
        position: Int,
        opensOn: String? = null,
        afterPrevious: Boolean = false,
        dueDate: String? = null,
        completedAt: String? = null
    ) = ObjectiveStep("s$position", "o", position, "Step $position", opensOn, afterPrevious, dueDate, completedAt)

    // Obtain ITIL 4 Foundation, due Sep 30: enroll (open now, due Mar 31), then training (after
    // enrolling, due Jul 31), then the exam (after training, due Sep 30).
    private fun itil(enrolled: String? = null, trained: String? = null) = listOf(
        step(0, dueDate = "2026-03-31", completedAt = enrolled),
        step(1, afterPrevious = true, dueDate = "2026-07-31", completedAt = trained),
        step(2, afterPrevious = true, dueDate = "2026-09-30")
    )

    @Test
    fun `only the first step is open until it is done`() {
        assertEquals(
            listOf(StepState.OPEN, StepState.LOCKED, StepState.LOCKED),
            Objectives.states(itil(), "2026-02-01")
        )
    }

    @Test
    fun `completing a step unlocks the next`() {
        assertEquals(
            listOf(StepState.DONE, StepState.OPEN, StepState.LOCKED),
            Objectives.states(itil(enrolled = "2026-02-10T00:00:00Z"), "2026-02-11")
        )
    }

    @Test
    fun `an open step past its due date is overdue, a locked one is still locked`() {
        assertEquals(
            listOf(StepState.OVERDUE, StepState.LOCKED, StepState.LOCKED),
            Objectives.states(itil(), "2026-04-01")
        )
    }

    @Test
    fun `a step with an opening date waits for it`() {
        val steps = listOf(step(0, opensOn = "2026-05-01"))
        assertEquals(StepState.UPCOMING, Objectives.stateOf(steps, 0, "2026-04-30"))
        assertEquals(StepState.OPEN, Objectives.stateOf(steps, 0, "2026-05-01"))
    }

    @Test
    fun `both gates must be met`() {
        val steps = listOf(
            step(0, completedAt = "2026-01-01T00:00:00Z"),
            step(1, opensOn = "2026-06-01", afterPrevious = true)
        )
        assertEquals(StepState.UPCOMING, Objectives.stateOf(steps, 1, "2026-05-01"))
        assertTrue(Objectives.isOpen(steps, 1, "2026-06-01"))
    }

    @Test
    fun `a first step marked after-previous has nothing to wait on`() {
        assertEquals(StepState.OPEN, Objectives.stateOf(listOf(step(0, afterPrevious = true)), 0, "2026-01-01"))
    }

    @Test
    fun `success needs every step done`() {
        assertFalse(Objectives.canReportSuccess(itil(enrolled = "x", trained = "y")))
        assertTrue(Objectives.canReportSuccess(itil(enrolled = "x", trained = "y").map { it.copy(completedAt = "z") }))
        assertTrue(Objectives.canReportSuccess(emptyList()))
    }

    @Test
    fun `focus shows open steps, else the next one waiting`() {
        assertEquals(listOf(0), Objectives.focusIndices(itil(), "2026-02-01"))
        assertEquals(listOf(1), Objectives.focusIndices(itil(enrolled = "x"), "2026-02-01"))
        val waiting = listOf(step(0, completedAt = "x"), step(1, opensOn = "2026-09-01"), step(2, afterPrevious = true))
        assertEquals(listOf(1), Objectives.focusIndices(waiting, "2026-08-01"))
        assertEquals(emptyList<Int>(), Objectives.focusIndices(listOf(step(0, completedAt = "x")), "2026-08-01"))
    }

    @Test
    fun `days until counts down and goes negative`() {
        assertEquals(7L, Objectives.daysUntil("2026-09-30", "2026-09-23"))
        assertEquals(-1L, Objectives.daysUntil("2026-09-22", "2026-09-23"))
        assertEquals(null, Objectives.daysUntil("soon", "2026-09-23"))
    }

    @Test
    fun `an open step due by the week's end belongs on that week`() {
        val steps = itil()
        assertTrue(Objectives.belongsOnWeek(steps, 0, "2026-03-31", "2026-03-27", worked = false))
        // Overdue counts too — it's still owed.
        assertTrue(Objectives.belongsOnWeek(steps, 0, "2026-04-05", "2026-04-01", worked = false))
    }

    @Test
    fun `an open step due later stays off the week until work starts on it`() {
        val steps = itil()
        assertFalse(Objectives.belongsOnWeek(steps, 0, "2026-02-07", "2026-02-02", worked = false))
        assertTrue(Objectives.belongsOnWeek(steps, 0, "2026-02-07", "2026-02-02", worked = true))
    }

    @Test
    fun `a locked, upcoming or done step never belongs on the week`() {
        val steps = itil()
        // Step 2 is due this week but waits on step 1.
        assertFalse(Objectives.belongsOnWeek(steps, 1, "2026-08-01", "2026-07-28", worked = true))
        val dated = listOf(step(0, opensOn = "2026-05-01", dueDate = "2026-05-03"))
        assertFalse(Objectives.belongsOnWeek(dated, 0, "2026-05-03", "2026-04-28", worked = true))
        val done = itil(enrolled = "2026-02-10T00:00:00Z")
        assertFalse(Objectives.belongsOnWeek(done, 0, "2026-03-31", "2026-03-27", worked = true))
    }

    @Test
    fun `an open step with no due date waits until work starts`() {
        val steps = listOf(step(0))
        assertFalse(Objectives.belongsOnWeek(steps, 0, "2026-02-07", "2026-02-02", worked = false))
        assertTrue(Objectives.belongsOnWeek(steps, 0, "2026-02-07", "2026-02-02", worked = true))
    }
}
