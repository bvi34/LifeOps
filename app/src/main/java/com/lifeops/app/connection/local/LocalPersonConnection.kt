package com.lifeops.app.connection.local

import com.lifeops.app.connection.ConnectionError
import com.lifeops.app.connection.ConnectionRegistry
import com.lifeops.app.connection.ConnectionResult
import com.lifeops.app.connection.service.PersonService

/**
 * The `local/person/*` routes.
 *
 *  - `create`  — params: name (required).
 *  - `rename`  — params: id (required), name (required).
 *  - `archive` — params: id (required), archived (bool, default true).
 *  - `delete`  — params: id (required).
 *  - `addNote` — params: id (required), content (required).
 *  - `attach`  — params: taskId (required), id (person, required).
 *  - `detach`  — params: taskId (required), id (person, required).
 */
object LocalPersonConnection {
    fun register(registry: ConnectionRegistry, personService: PersonService) {
        registry.register("local", "person", "create") { request ->
            val person = personService.create(request.params.requireString("name"))
            ConnectionResult.ok("id" to person.id)
        }

        registry.register("local", "person", "rename") { request ->
            val p = request.params
            val id = p.requireString("id")
            personService.rename(id, p.requireString("name"))
                ?.let { ConnectionResult.ok("id" to it.id) } ?: notFound(id)
        }

        registry.register("local", "person", "archive") { request ->
            val p = request.params
            val id = p.requireString("id")
            val archived = if (p.has("archived")) p.getBoolean("archived") else true
            if (personService.setArchived(id, archived)) ConnectionResult.ok("id" to id, "archived" to archived)
            else notFound(id)
        }

        registry.register("local", "person", "delete") { request ->
            val id = request.params.requireString("id")
            if (personService.delete(id)) ConnectionResult.ok("id" to id) else notFound(id)
        }

        registry.register("local", "person", "addNote") { request ->
            val p = request.params
            val id = p.requireString("id")
            if (personService.addNote(id, p.requireString("content"))) ConnectionResult.ok("id" to id)
            else ConnectionResult.fail(ConnectionError.INVALID_PARAMS, "Unknown person or blank note")
        }

        registry.register("local", "person", "attach") { request ->
            val p = request.params
            val id = p.requireString("id")
            val taskId = p.requireString("taskId")
            if (personService.attach(taskId, id)) ConnectionResult.ok("taskId" to taskId, "personId" to id)
            else notFound(id)
        }

        registry.register("local", "person", "detach") { request ->
            val p = request.params
            val id = p.requireString("id")
            val taskId = p.requireString("taskId")
            personService.detach(taskId, id)
            ConnectionResult.ok("taskId" to taskId, "personId" to id)
        }
    }

    private fun notFound(id: String): ConnectionResult =
        ConnectionResult.fail(ConnectionError.NOT_FOUND, "No person with id '$id'")
}
