package com.repository.app.connection.local

import com.operations.backupkit.AppId
import com.operations.connectkit.ConnectionRegistry
import com.operations.connectkit.ConnectionResult
import com.repository.app.data.repository.DocumentRepository
import com.repository.app.logic.Shelf

/**
 * `/v1/Repository/local/drawer/…` — what the shelf is divided into, so a caller can ask before it
 * narrows.
 *
 * One route, and it is the answer to "what have we got paperwork about". The household's own drawer
 * leads, as it does on screen, because a document belonging to no app is the one nothing else will
 * ever show you.
 */
object LocalDrawerConnection {

    fun register(registry: ConnectionRegistry, documents: DocumentRepository) {

        registry.register("local", "drawer", "list") { _ ->
            val drawers = Shelf.drawers(documents.everything()) { key ->
                AppId.fromKey(key)?.defaultDisplayName
            }
            ConnectionResult.ok(
                "drawers" to drawers.map { group ->
                    mapOf(
                        // Null for the household's, which `document/list` takes as `household: true`
                        // rather than as an app key it would have to invent a name for.
                        "app" to group.appKey,
                        "label" to group.label,
                        "count" to group.documents.size,
                        "sizeBytes" to group.sizeBytes
                    )
                },
                "count" to drawers.size
            )
        }
    }
}
