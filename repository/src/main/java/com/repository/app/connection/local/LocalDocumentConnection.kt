package com.repository.app.connection.local

import com.operations.connectkit.ConnectionError
import com.operations.connectkit.ConnectionRegistry
import com.operations.connectkit.ConnectionResult
import com.operations.connectkit.NameLookup
import com.operations.connectkit.Resolution
import com.operations.connectkit.orProblem
import com.repository.app.data.repository.DocumentRepository
import com.repository.app.logic.DocumentFacts
import com.repository.app.logic.DocumentKind
import com.repository.app.logic.DocumentOwner
import com.repository.app.logic.Shelf

/**
 * `/v1/Repository/local/document/…` — finding what the household filed, and correcting what it is
 * called.
 *
 * Every route here names its target the way a sentence does: by title, or by id when the caller has
 * one. That resolution is `NameLookup` in the kit, so "the mortgage statement" behaves here exactly
 * as "the Kestrel" behaves in Project — **and two documents of the same name resolve to neither**,
 * with both named back, rather than to whichever was filed first. Picking one would rename or detach
 * a document nobody asked about, and the caller would never find out.
 */
object LocalDocumentConnection {

    fun register(registry: ConnectionRegistry, documents: DocumentRepository) {

        /**
         * Everything on the shelf, or one drawer of it.
         *
         * `app` and `record` narrow it the same way the screen's chips do. Read-only, and metadata
         * only: what a document is called, what kind somebody said it is, what it is about, how big
         * it is. Never a byte of it — this module does not read documents, and a route is not the
         * place it would start.
         */
        registry.register("local", "document", "list") { request ->
            val p = request.params
            val shelf = documents.everything()
            val app = p.getString("app")
            val record = p.getString("record")
            val listed = when {
                app != null && record != null -> Shelf.on(shelf, app, record)
                app != null -> shelf.filter { it.owner.appKey == app }
                // `household` is a real drawer rather than a missing value: the will, the passport,
                // the survey. Naming it is the only way to ask for it.
                p.getBoolean("household") -> shelf.filter { it.owner.appKey == null }
                else -> shelf
            }
            ConnectionResult.ok("documents" to listed.map { it.wire() }, "count" to listed.size)
        }

        /**
         * What a search finds — the same search the shelf's own box runs, over what a document is
         * called, its note, its kind and **what it is about**.
         *
         * So "wrangler" finds the truck's manual through a route, while this module still has no
         * idea what a Wrangler is.
         */
        registry.register("local", "document", "search") { request ->
            val found = Shelf.search(documents.everything(), request.params.requireString("query"))
            ConnectionResult.ok("documents" to found.map { it.wire() }, "count" to found.size)
        }

        /** One document, by name or id. */
        registry.register("local", "document", "get") { request ->
            val reference = request.params.requireString("document")
            when (val found = documents.locate(reference)) {
                is Resolution.Problem -> found.failure
                is Resolution.Ok -> ConnectionResult.ok("document" to found.value.wire())
            }
        }

        /**
         * Fix what a document is called, what kind it is, or the note on it.
         *
         * The one write here that changes a row, and it is deliberately the only kind of write that
         * *can* be undone by making it again: a caption is something a person typed, and a person
         * who mistyped it is exactly who would ask a sentence to fix it. The bytes are untouched and
         * unreachable from this address.
         *
         * An omitted field is left alone; a blank note clears it — the same convention Project's
         * `doc/update` uses, so a caller does not have to learn two.
         */
        registry.register("local", "document", "update") { request ->
            val p = request.params
            when (val found = documents.locate(p.requireString("document"))) {
                is Resolution.Problem -> found.failure
                is Resolution.Ok -> {
                    val document = found.value
                    documents.lentRefusal(document, "renamed")?.let { return@register it }

                    val kind = p.getString("kind")?.let { named ->
                        // A kind is vocabulary, and the app behaves identically for every value of
                        // it — but a kind nobody recognises would silently file the document under
                        // "Other" and lose what the caller said. Better to say so.
                        DocumentKind.entries.firstOrNull { it.key.equals(named, ignoreCase = true) }
                            ?: return@register ConnectionResult.fail(
                                ConnectionError.INVALID_PARAMS,
                                "No such kind '$named'. One of: " +
                                    DocumentKind.entries.joinToString(", ") { it.key }
                            )
                    } ?: document.kind

                    documents.update(
                        id = document.id,
                        title = p.getString("title") ?: document.title,
                        kind = kind,
                        // Present and blank clears it; absent leaves what is there.
                        note = if (p.has("note")) p.getString("note") else document.note
                    )
                    ConnectionResult.ok("id" to document.id)
                }
            }
        }

        /**
         * Put a document back in the household's own drawer.
         *
         * Detaching is not deleting, and the difference is the whole reason this one is routable at
         * all: the document stays on the shelf, still findable, still openable — it simply stops
         * being filed against the asset or the project it was on. Somebody who asked for that and
         * got it wrong attaches it again from the owning app's own screen.
         *
         * There is deliberately no route the other way. Filing a document *onto* a record means
         * naming a record in an app this module knows nothing about, by a key no sentence carries;
         * the app that owns the thing does it in-process, where it has the id in its hand.
         */
        registry.register("local", "document", "detach") { request ->
            when (val found = documents.locate(request.params.requireString("document"))) {
                is Resolution.Problem -> found.failure
                is Resolution.Ok -> {
                    val document = found.value
                    documents.lentRefusal(document, "moved")?.let { return@register it }
                    documents.refile(document.id, DocumentOwner.HOUSEHOLD)
                    ConnectionResult.ok("id" to document.id)
                }
            }
        }
    }
}

/**
 * A document as a route hands it back: the row, never the bytes.
 *
 * `lentBy` is on it because the shelf is one list and the distinction only matters at the moment
 * somebody tries to change something — a caller that reads it knows, before asking, which documents
 * this app can be asked to rename.
 */
internal fun DocumentFacts.wire(): Map<String, Any?> = mapOf(
    "id" to id,
    "title" to title,
    "kind" to kind.key,
    "note" to note,
    "sizeBytes" to sizeBytes,
    "addedAt" to addedAt,
    "app" to owner.appKey,
    "record" to owner.recordKey,
    "about" to owner.label,
    "lentBy" to sourceKey
)

/**
 * Find the document a caller named, on the whole shelf — the lent ones included.
 *
 * Lent documents are findable on purpose. A household asking where the lab result is does not care
 * which app is holding it, and a route that could not see Health's documents would answer "no such
 * document" about one sitting in plain view on the shelf. What a route cannot do is *write* to one;
 * see [lentRefusal].
 */
internal suspend fun DocumentRepository.locate(reference: String): Resolution<DocumentFacts> =
    NameLookup.resolve(reference, everything(), { it.id }, { it.title })
        .orProblem(noun = "document", reference = reference)

/**
 * The refusal to write to a document another app is only lending, or null if it is ours to change.
 *
 * Health deletes a person's documents with the person, and has rules of its own about renaming one.
 * A second writer would either duplicate those rules or break them, so the shelf shows a lent
 * document and never writes to it — and says so, rather than reporting a success that changed
 * nothing, which is the failure mode a caller cannot see.
 */
internal fun DocumentRepository.lentRefusal(document: DocumentFacts, verb: String): ConnectionResult.Failure? {
    val lender = document.sourceKey ?: return null
    return ConnectionResult.fail(
        ConnectionError.INVALID_PARAMS,
        "“${document.title}” is lent to the shelf by $lender and can only be $verb there."
    )
}
