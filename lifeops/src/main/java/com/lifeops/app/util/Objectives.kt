package com.lifeops.app.util

import com.lifeops.app.data.model.ObjectiveStep
import com.lifeops.app.data.model.ObjectiveWithSteps

/** Where a step of an objective stands on a given day. */
enum class StepState {
    /** Ticked off. */
    DONE,
    /** Waiting on the step before it. */
    LOCKED,
    /** Its opening date hasn't come yet. */
    UPCOMING,
    /** Open, and not past its due date. */
    OPEN,
    /** Open, and past its due date. */
    OVERDUE
}

/**
 * The rules of an Objective's steps, kept Android-free so they are unit-testable on the JVM.
 *
 * A step opens when both gates are met: its `opensOn` date has arrived (none = open from the
 * start) and, if `afterPrevious` is set, the step before it is done. Dates are ISO yyyy-MM-dd, so
 * plain string comparison orders them. Steps are expected in position order.
 */
object Objectives {

    fun stateOf(steps: List<ObjectiveStep>, index: Int, today: String): StepState {
        val step = steps[index]
        return when {
            step.isDone -> StepState.DONE
            step.afterPrevious && index > 0 && !steps[index - 1].isDone -> StepState.LOCKED
            step.opensOn != null && today < step.opensOn -> StepState.UPCOMING
            step.dueDate != null && today > step.dueDate -> StepState.OVERDUE
            else -> StepState.OPEN
        }
    }

    fun states(steps: List<ObjectiveStep>, today: String): List<StepState> =
        steps.indices.map { stateOf(steps, it, today) }

    /** Whether the step can be ticked off today — it has opened (a done step counts as open). */
    fun isOpen(steps: List<ObjectiveStep>, index: Int, today: String): Boolean =
        stateOf(steps, index, today).let { it != StepState.LOCKED && it != StepState.UPCOMING }

    /** Success can be reported once every step is done (an objective with no steps: any time). */
    fun canReportSuccess(steps: List<ObjectiveStep>): Boolean = steps.all { it.isDone }

    fun isOverdue(item: ObjectiveWithSteps, today: String): Boolean = today > item.objective.dueDate

    /**
     * The steps worth showing on the week board without expanding the card: every open (or
     * overdue) step, and — when nothing is open — the next one waiting, so the card always says
     * what comes next. Indices into [steps].
     */
    fun focusIndices(steps: List<ObjectiveStep>, today: String): List<Int> {
        val states = states(steps, today)
        val open = states.indices.filter { states[it] == StepState.OPEN || states[it] == StepState.OVERDUE }
        if (open.isNotEmpty()) return open
        return listOfNotNull(states.indices.firstOrNull { states[it] != StepState.DONE })
    }

    /** Days from [today] until [date], negative once it has passed; null when unparseable. */
    fun daysUntil(date: String, today: String): Long? = try {
        java.time.temporal.ChronoUnit.DAYS.between(
            java.time.LocalDate.parse(today), java.time.LocalDate.parse(date)
        )
    } catch (_: Exception) { null }
}
