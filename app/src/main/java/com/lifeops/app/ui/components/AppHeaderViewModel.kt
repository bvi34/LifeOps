package com.lifeops.app.ui.components

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lifeops.app.data.model.Task
import com.lifeops.app.data.model.TaskStatus
import com.lifeops.app.data.model.Week
import com.lifeops.app.data.repository.TaskRepository
import com.lifeops.app.data.repository.WeekRepository
import kotlinx.coroutines.flow.*
import java.time.LocalDate

class AppHeaderViewModel(
    weekRepository: WeekRepository,
    taskRepository: TaskRepository,
) : ViewModel() {

    val sardonicMessage: StateFlow<String> = weekRepository.observeCurrentWeek()
        .flatMapLatest { week ->
            if (week == null) flowOf("Start a new week whenever you're ready.")
            else taskRepository.observeTasksForWeek(week.id).map { computeMessage(it, week) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "Let's see what this week holds.")

    private fun computeMessage(tasks: List<Task>, week: Week): String {
        val today = LocalDate.now()
        val daysElapsed = try {
            (today.toEpochDay() - LocalDate.parse(week.startDate).toEpochDay()).toInt().coerceIn(0, 6)
        } catch (_: Exception) { 3 }

        val isEarlyWeek = daysElapsed <= 1
        val isLateWeek = daysElapsed >= 4

        val todayStr = today.toString()
        val pending = tasks.filter { it.status == TaskStatus.PENDING }
        val hardDueToday = pending.filter { it.hardDeadline && it.dueDate == todayStr }
        val hardDueSoon = pending.filter { it.hardDeadline && it.dueDate != null }
        val carried = pending.filter { it.carriedCount > 0 }
        val completed = tasks.count { it.status == TaskStatus.COMPLETED }

        return when {
            hardDueToday.size > 1 -> when {
                isLateWeek -> "Those ${hardDueToday.size} deadlines are right here — you've got this."
                isEarlyWeek -> "You've got ${hardDueToday.size} hard deadlines today. Tackle them head-on."
                else -> "${hardDueToday.size} hard deadlines today. You're on it."
            }
            hardDueToday.size == 1 -> when {
                isLateWeek -> "One deadline left today — end the week strong."
                isEarlyWeek -> "One hard deadline today. You have everything you need."
                else -> "Deadline today. You've handled harder."
            }
            hardDueSoon.size > 2 -> when {
                isLateWeek -> "A few deadlines still ahead — look how far you've already come."
                isEarlyWeek -> "Glad you're seeing those deadlines early. You're set up well."
                else -> "Hard deadlines incoming. You're ahead of them."
            }
            hardDueSoon.size == 1 -> when {
                isLateWeek -> "One more deadline to close. You're in control."
                isEarlyWeek -> "One deadline coming — plenty of time to get there."
                else -> "One deadline on the horizon. Keep it in sight."
            }
            carried.size > 3 -> when {
                isLateWeek -> "${carried.size} tasks carried this week — what you did finish matters."
                isEarlyWeek -> "${carried.size} carried tasks. Fresh week, fresh shot at them."
                else -> "${carried.size} carried tasks still in reach. Keep going."
            }
            carried.size in 1..3 -> when {
                isLateWeek -> "${carried.size} carried task${if (carried.size > 1) "s" else ""} — no shame in it. You showed up."
                isEarlyWeek -> "${carried.size} task${if (carried.size > 1) "s" else ""} from last week. This week is their week."
                else -> "${carried.size} carried task${if (carried.size > 1) "s" else ""} still in your hands."
            }
            pending.size > 12 -> when {
                isLateWeek -> "A full week. Everything you completed already counts."
                isEarlyWeek -> "Big week ahead. One task at a time — you'll move through it."
                else -> "Solid list. Keep the momentum going."
            }
            pending.size in 5..12 -> when {
                isLateWeek -> "Look at what you've built this week. The finish line is close."
                isEarlyWeek -> "Good setup. You're in a strong position."
                else -> "Making real progress. Keep it up."
            }
            pending.size in 1..4 -> when {
                isLateWeek -> "Almost there — this week has been yours."
                isEarlyWeek -> "${pending.size} task${if (pending.size > 1) "s" else ""} — tight and focused. Exactly right."
                else -> "${pending.size} left. The end is in sight."
            }
            pending.isEmpty() && completed > 0 -> when {
                isLateWeek -> "You did it. Every single task. Be proud of this week."
                isEarlyWeek -> "All clear already — incredible start."
                else -> "All done! You're ahead of the game."
            }
            pending.isEmpty() -> when {
                isLateWeek -> "A calm, complete week. Well earned."
                isEarlyWeek -> "A blank slate — set it up exactly how you want."
                else -> "Nothing pending. Add what matters this week."
            }
            else -> if (isLateWeek) "A week worth having." else "Let's build something good this week."
        }
    }
}

class AppHeaderViewModelFactory(
    private val weekRepository: WeekRepository,
    private val taskRepository: TaskRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        AppHeaderViewModel(weekRepository, taskRepository) as T
}
