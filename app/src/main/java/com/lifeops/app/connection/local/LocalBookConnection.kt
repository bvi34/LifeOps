package com.lifeops.app.connection.local

import com.lifeops.app.connection.ConnectionError
import com.lifeops.app.connection.ConnectionRegistry
import com.lifeops.app.connection.ConnectionResult
import com.lifeops.app.connection.service.BookService
import com.lifeops.app.data.model.BookStatus

/**
 * The `local/book/*` routes.
 *
 *  - `create`   — params: title (required), author.
 *  - `update`   — params: id (required), title, author.
 *  - `setStatus`— params: id (required), status (to_read | reading | done).
 *  - `delete`   — params: id (required).
 *  - `addNote`  — params: id (required), content (required).
 *  - `logTime`  — params: id (required), durationMinutes (int, required), note.
 */
object LocalBookConnection {
    fun register(registry: ConnectionRegistry, bookService: BookService) {
        registry.register("local", "book", "create") { request ->
            val p = request.params
            val book = bookService.create(p.requireString("title"), p.getString("author"))
            ConnectionResult.ok("id" to book.id)
        }

        registry.register("local", "book", "update") { request ->
            val p = request.params
            val id = p.requireString("id")
            bookService.update(id, p.getString("title"), p.getString("author"))
                ?.let { ConnectionResult.ok("id" to it.id) } ?: notFound(id)
        }

        registry.register("local", "book", "setStatus") { request ->
            val p = request.params
            val id = p.requireString("id")
            val status = BookStatus.from(p.requireString("status").uppercase())
            if (bookService.setStatus(id, status)) ConnectionResult.ok("id" to id, "status" to status.name)
            else notFound(id)
        }

        registry.register("local", "book", "delete") { request ->
            val id = request.params.requireString("id")
            if (bookService.delete(id)) ConnectionResult.ok("id" to id) else notFound(id)
        }

        registry.register("local", "book", "addNote") { request ->
            val p = request.params
            val id = p.requireString("id")
            if (bookService.addNote(id, p.requireString("content"))) ConnectionResult.ok("id" to id)
            else ConnectionResult.fail(ConnectionError.INVALID_PARAMS, "Unknown book or blank note")
        }

        registry.register("local", "book", "logTime") { request ->
            val p = request.params
            val id = p.requireString("id")
            if (bookService.logTime(id, p.requireInt("durationMinutes"), p.getString("note")))
                ConnectionResult.ok("id" to id)
            else ConnectionResult.fail(ConnectionError.INVALID_PARAMS, "Unknown book or non-positive duration")
        }
    }

    private fun notFound(id: String): ConnectionResult =
        ConnectionResult.fail(ConnectionError.NOT_FOUND, "No book with id '$id'")
}
