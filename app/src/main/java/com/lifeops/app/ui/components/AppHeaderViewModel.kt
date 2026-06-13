package com.lifeops.app.ui.components

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lifeops.app.data.model.Task
import com.lifeops.app.data.model.TaskStatus
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
            if (week == null) flowOf("No active week. A bold life choice.")
            else taskRepository.observeTasksForWeek(week.id).map { computeMessage(it) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "Surveying the damage...")

    private fun computeMessage(tasks: List<Task>): String {
        val today = LocalDate.now().toString()
        val pending = tasks.filter { it.status == TaskStatus.PENDING }
        val hardDueToday = pending.filter { it.hardDeadline && it.dueDate == today }
        val hardDueSoon = pending.filter { it.hardDeadline && it.dueDate != null }
        val carried = pending.filter { it.carriedCount > 0 }
        val completed = tasks.count { it.status == TaskStatus.COMPLETED }

        return when {
            hardDueToday.size > 1 ->
                "${hardDueToday.size} hard deadlines due TODAY. The calendar is not negotiating."
            hardDueToday.size == 1 ->
                "Hard deadline TODAY: \"${hardDueToday[0].title.take(28)}\". Clock's ticking."
            hardDueSoon.size > 2 ->
                "${hardDueSoon.size} hard deadlines looming. Sleep is aspirational."
            hardDueSoon.size == 1 ->
                "One hard deadline incoming. No pressure — crushing pressure."
            carried.size > 3 ->
                "${carried.size} tasks carried forward. A dynasty of deferrals."
            carried.size in 1..3 ->
                "${carried.size} carried task${if (carried.size > 1) "s" else ""}. The past refuses to stay past."
            pending.size > 12 ->
                "${pending.size} pending tasks. The list grows; the hours do not."
            pending.size in 5..12 ->
                "${pending.size} tasks left. Your future self is already disappointed."
            pending.size in 1..4 ->
                "${pending.size} task${if (pending.size > 1) "s" else ""} to go. Almost there, theoretically."
            pending.isEmpty() && completed > 0 ->
                "All done. Suspicious. What did you skip?"
            pending.isEmpty() ->
                "No tasks this week. An unsettling amount of freedom."
            else -> "Veni, vidi, procrastinavi."
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
