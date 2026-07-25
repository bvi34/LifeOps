package com.lifeops.app.connection.local

import com.lifeops.app.connection.ConnectionError
import com.lifeops.app.connection.ConnectionRegistry
import com.lifeops.app.connection.ConnectionResult
import com.lifeops.app.connection.service.BusyBlockService
import com.lifeops.app.connection.service.TimeEntryService

/**
 * Scheduling-adjacent routes: `local/busyBlock` and `local/timeEntry`.
 *
 *  - `busyBlock/create` — params: title (required), startMinutes (int, required), endMinutes (int,
 *    required), daysMask (int; weekly recurrence bitmask, 0 = one-off), specificDate (yyyy-MM-dd
 *    for a one-off), personId (null = own schedule).
 *  - `busyBlock/delete` — params: id (required).
 *  - `timeEntry/log`    — params: taskId (required), durationMinutes (int, required), note,
 *    subtaskId.
 */
object LocalScheduleConnection {
    fun register(
        registry: ConnectionRegistry,
        busyBlockService: BusyBlockService,
        timeEntryService: TimeEntryService
    ) {
        registry.register("local", "busyBlock", "create") { request ->
            val p = request.params
            val block = busyBlockService.create(
                title = p.requireString("title"),
                startMinutes = p.requireInt("startMinutes"),
                endMinutes = p.requireInt("endMinutes"),
                daysMask = p.getInt("daysMask") ?: 0,
                specificDate = p.getString("specificDate"),
                personId = p.getString("personId")
            )
            ConnectionResult.ok("id" to block.id)
        }

        registry.register("local", "busyBlock", "delete") { request ->
            val id = request.params.requireString("id")
            busyBlockService.delete(id)
            ConnectionResult.ok("id" to id)
        }

        registry.register("local", "timeEntry", "log") { request ->
            val p = request.params
            val taskId = p.requireString("taskId")
            val minutes = p.requireInt("durationMinutes")
            if (timeEntryService.log(taskId, minutes, p.getString("note"), p.getString("subtaskId")))
                ConnectionResult.ok("taskId" to taskId, "durationMinutes" to minutes)
            else ConnectionResult.fail(ConnectionError.INVALID_PARAMS, "Unknown task or non-positive duration")
        }
    }
}
