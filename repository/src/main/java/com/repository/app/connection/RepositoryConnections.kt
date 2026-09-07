package com.repository.app.connection

import android.util.Log
import com.operations.connectkit.ConnectionDispatcher
import com.operations.connectkit.ConnectionRegistry
import com.repository.app.connection.local.LocalDocumentConnection
import com.repository.app.connection.local.LocalDrawerConnection
import com.repository.app.data.repository.DocumentRepository

/**
 * Composition root for Repository's connection layer — the suite's third.
 *
 * The shelf is the app most obviously worth addressing in a sentence, because *where is that
 * document* is a sentence. It is also the app where a route can do the most damage, so the line the
 * route table sits on is drawn tighter here than anywhere else:
 *
 * **These routes read, and they correct captions. They cannot put a document on the shelf, take one
 * off it, or hand one out.**
 *
 * - **Filing is not routable at all**, and not out of caution: a picked document is an Android `Uri`
 *   plus a permission grant, not a serialisable payload. It is the same exclusion LifeOps makes for
 *   task image attachments, for the same reason.
 * - **Nothing deletes.** `delete` here destroys bytes, and the bytes may be the only copy of that
 *   document in the house — the scan of the title, the letter the solicitor sent once. Creating a
 *   row is undone by deleting it; deleting a document is undone by nothing.
 * - **Nothing exports.** A document leaves this device by exactly one road: somebody presses Open or
 *   Send and picks where it goes. A route that handed a file out would be a second road, opened by a
 *   caller rather than by the household, and the promise in the manifest would stop being true.
 *
 * What is left is genuinely useful and genuinely safe: finding a document, saying what is on the
 * shelf, fixing a title somebody mistyped, and putting a document back in the household's drawer.
 * `RepositoryConnectionsTest` asserts each of the refused addresses is `ROUTE_NOT_FOUND`, so the
 * line is a test rather than an intention.
 *
 * There is no service layer between these handlers and `DocumentRepository`, for the reason Project
 * gives: the store already *is* the use-case layer — every rule about what happens to a row and the
 * file under it lives there and is tested there — and a second one would be a second place for those
 * rules to live. See `docs/CONNECTIONS.md`.
 */
object RepositoryConnections {

    /** The `application` segment Repository answers for. */
    const val APPLICATION = "Repository"

    fun buildDispatcher(documents: DocumentRepository): ConnectionDispatcher {
        val registry = ConnectionRegistry()
        LocalDocumentConnection.register(registry, documents)
        LocalDrawerConnection.register(registry, documents)
        return ConnectionDispatcher(registry, APPLICATION) { address, error ->
            Log.e("RepositoryConnections", "Handler failed for $address", error)
        }
    }
}
