package com.lifeops.app.connection

import android.util.Log
import com.lifeops.app.connection.local.LocalActivityConnection
import com.lifeops.app.connection.local.LocalAspectConnection
import com.lifeops.app.connection.local.LocalBookConnection
import com.lifeops.app.connection.local.LocalCostConnection
import com.lifeops.app.connection.local.LocalCounterConnection
import com.lifeops.app.connection.local.LocalFoodConnection
import com.lifeops.app.connection.local.LocalFutureProjectConnection
import com.lifeops.app.connection.local.LocalNoteConnection
import com.lifeops.app.connection.local.LocalPersonConnection
import com.lifeops.app.connection.local.LocalProjectConnection
import com.lifeops.app.connection.local.LocalRunbookConnection
import com.lifeops.app.connection.local.LocalScheduleConnection
import com.lifeops.app.connection.local.LocalTaskConnection
import com.lifeops.app.connection.local.LocalWeekConnection
import com.lifeops.app.connection.local.LocalWellnessConnection
import com.lifeops.app.connection.service.ActivityService
import com.lifeops.app.connection.service.AspectService
import com.lifeops.app.connection.service.BookService
import com.lifeops.app.connection.service.BusyBlockService
import com.lifeops.app.connection.service.CostService
import com.lifeops.app.connection.service.CounterService
import com.lifeops.app.connection.service.FoodService
import com.lifeops.app.connection.service.FutureProjectService
import com.lifeops.app.connection.service.NoteService
import com.lifeops.app.connection.service.PersonService
import com.lifeops.app.connection.service.ProjectService
import com.lifeops.app.connection.service.RecipeService
import com.lifeops.app.connection.service.RunbookService
import com.lifeops.app.connection.service.TaskService
import com.lifeops.app.connection.service.TimeEntryService
import com.lifeops.app.connection.service.WeekService
import com.lifeops.app.connection.service.WellnessService

/**
 * Composition root for the connection layer. Builds the registry, wires every connection's routes
 * onto it, and returns a ready dispatcher. New connections (internal resources under `local`, or
 * future named integrations) are registered here.
 */
object Connections {

    /** Services the connection layer dispatches into, gathered so [buildDispatcher] stays stable
     * as more are added. */
    data class Services(
        val task: TaskService,
        val week: WeekService,
        val project: ProjectService,
        val counter: CounterService,
        val note: NoteService,
        val aspect: AspectService,
        val person: PersonService,
        val busyBlock: BusyBlockService,
        val timeEntry: TimeEntryService,
        val book: BookService,
        val recipe: RecipeService,
        val food: FoodService,
        val futureProject: FutureProjectService,
        val cost: CostService,
        val activity: ActivityService,
        val runbook: RunbookService,
        val wellness: WellnessService
    )

    fun buildDispatcher(services: Services): ConnectionDispatcher {
        val registry = ConnectionRegistry()
        // --- local: internal app comms ---
        LocalTaskConnection.register(registry, services.task)
        LocalWeekConnection.register(registry, services.week)
        LocalProjectConnection.register(registry, services.project)
        LocalCounterConnection.register(registry, services.counter)
        LocalNoteConnection.register(registry, services.note)
        LocalAspectConnection.register(registry, services.aspect)
        LocalPersonConnection.register(registry, services.person)
        LocalScheduleConnection.register(registry, services.busyBlock, services.timeEntry)
        LocalBookConnection.register(registry, services.book)
        LocalFoodConnection.register(registry, services.food, services.recipe)
        LocalFutureProjectConnection.register(registry, services.futureProject)
        LocalCostConnection.register(registry, services.cost)
        LocalActivityConnection.register(registry, services.activity)
        LocalRunbookConnection.register(registry, services.runbook)
        LocalWellnessConnection.register(registry, services.wellness)
        // Future named connections (integrations) register their own connection namespace here.
        return ConnectionDispatcher(registry) { address, error ->
            Log.e("ConnectionDispatcher", "Handler failed for $address", error)
        }
    }
}
