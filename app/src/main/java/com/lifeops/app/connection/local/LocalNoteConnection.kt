package com.lifeops.app.connection.local

import com.lifeops.app.connection.ConnectionError
import com.lifeops.app.connection.ConnectionRegistry
import com.lifeops.app.connection.ConnectionResult
import com.lifeops.app.connection.service.NoteService

/**
 * The `local/note` routes (task notes).
 *
 *  - `/v1/LifeOps/local/note/add`    — params: taskId (required), content (required), subtaskId.
 *  - `/v1/LifeOps/local/note/delete` — params: id (required).
 */
object LocalNoteConnection {
    fun register(registry: ConnectionRegistry, noteService: NoteService) {
        registry.register("local", "note", "add") { request ->
            val p = request.params
            val added = noteService.add(
                taskId = p.requireString("taskId"),
                content = p.requireString("content"),
                subtaskId = p.getString("subtaskId")
            )
            if (added) ConnectionResult.ok()
            else ConnectionResult.fail(ConnectionError.INVALID_PARAMS, "Note content must not be blank")
        }

        registry.register("local", "note", "delete") { request ->
            val id = request.params.requireString("id")
            noteService.delete(id)
            ConnectionResult.ok("id" to id)
        }
    }
}
