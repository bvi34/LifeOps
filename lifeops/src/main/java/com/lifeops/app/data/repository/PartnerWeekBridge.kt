package com.lifeops.app.data.repository

import com.lifeops.app.connection.service.TaskService
import com.lifeops.app.data.model.TaskStatus
import com.people.app.partner.Contribution
import com.people.app.partner.HouseholdWeek
import com.people.app.partner.SharedTask
import com.people.app.partner.SharedWeek

/**
 * LifeOps' side of People's **partner seam**: this household's week, for a paired household to see,
 * and the one place a task they added to it becomes real.
 *
 * It is here, in LifeOps, rather than in People, because of which way the modules point. People
 * needs the week; LifeOps owns it — but `:lifeops` already depends on `:people` (it mints people from
 * calendar attendees and publishes them over the directory seam), so People reaching back would be a
 * module cycle. So People declares the port ([HouseholdWeek]) and this class is the adapter,
 * registered in `LifeOpsApp`. Every call between the two modules runs the same direction as a result.
 *
 * A sibling of [PeopleSyncRepository], and worth telling apart from it. That one reconciles the
 * *household roster* with apps inside this install, where every peer is trusted. This one hands a
 * week to somebody else's install, behind a pairing two people performed with a camera. They share a
 * neighbouring module and nothing else.
 *
 * Two things it deliberately does **not** do, both for the same reason — a partner is contributing
 * one task to one week, and anything more is authority "add a task to my week" never granted:
 *
 * - **It never sets `isRecurring`.** A partner could otherwise install a repeating obligation on
 *   somebody else's planner that outlives the pairing itself.
 * - **It never sets a hard deadline.** That expires the task at week close, quietly binning a job
 *   that simply did not get done.
 */
class PartnerWeekBridge(
    private val taskService: TaskService,
    private val taskRepository: TaskRepository,
    private val weekRepository: WeekRepository
) : HouseholdWeek {

    /**
     * The open week, reduced to what a partner is shown: what, when, and whether it is done.
     *
     * Everything else about a LifeOps task — its aspect, priority, resource value, estimate, notes,
     * carry-forward history — stays here. Those are how *this* household runs its week; they mean
     * nothing in another one, and a partner who could see them would be reading the household's
     * planning rather than its plans.
     */
    override suspend fun current(): SharedWeek {
        val week = weekRepository.getOrCreateCurrentWeek()
        return SharedWeek(
            weekStart = week.startDate,
            weekEnd = week.endDate,
            tasks = taskRepository.getTasksForWeek(week.id).map { task ->
                SharedTask(
                    taskId = task.id,
                    title = task.title,
                    dueDate = task.dueDate,
                    done = task.status == TaskStatus.COMPLETED
                )
            }
        )
    }

    /**
     * Put a partner's contribution on the week.
     *
     * `allowDuplicateTitle` is set for the reason Maintenance sets it (see `LifeOpsTasks` over in
     * `:maintenance`): the duplicate-title rule exists to stop a *person* adding the same thing
     * twice, and it counts completed rows too. De-duplication on this seam is by contribution id,
     * held durably in People's `partner_taken`, so a partner suggesting "Call the vet" again next
     * week — or after the first one was done — is a second real task rather than one silently
     * swallowed.
     *
     * The note names who it came from, because a task that appears on your week overnight should say
     * why it is there.
     */
    override suspend fun createTask(contribution: Contribution, partnerName: String): String? {
        val outcome = taskService.create(
            TaskService.CreateInput(
                title = contribution.title,
                note = "Added by $partnerName".takeIf { partnerName.isNotBlank() },
                dueDate = contribution.dueDate,
                isRecurring = false,
                hardDeadline = false,
                allowDuplicateTitle = true
            )
        )
        return (outcome as? TaskService.CreateOutcome.Created)?.task?.id
    }
}
