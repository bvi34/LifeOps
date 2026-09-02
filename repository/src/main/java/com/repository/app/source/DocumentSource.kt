package com.repository.app.source

import com.repository.app.logic.DocumentFacts
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

/**
 * An app lending its own paperwork to the shelf.
 *
 * Health already stored documents before this app existed — properly, with the bytes beside its
 * database and its own backup carrying them. Moving those into Repository would be a migration of
 * the most sensitive rows in the suite to buy a tidier diagram, and the household would get nothing
 * for the risk. So the shelf reads them where they are.
 *
 * That is the whole of this interface: an app says what it is holding and hands over a file when
 * somebody asks to open one. **Read-only, on purpose.** The shelf can show a lab result and send it
 * to a printer; it cannot rename it, re-file it or delete it, because the app that owns it has rules
 * about those — Health deletes a person's documents with the person — and a second writer would
 * either duplicate those rules or break them.
 *
 * A document filed *through* Repository is the other case entirely: it lives here, and the owning
 * app edits it through [com.repository.app.data.repository.DocumentRepository] like any other
 * caller. Maintenance works that way, because it had no store of its own to keep.
 */
interface DocumentSource {

    /** The lending app's `AppId.key`. Documents arrive on the shelf labelled with it. */
    val appKey: String

    /** What to call the drawer — the app's own name. */
    val label: String

    /**
     * What this app is holding, as it changes.
     *
     * The facts are the shelf's own type, so the lending app decides how its rows read here: which
     * of its documents are worth showing, what each is called, and which of its records each belongs
     * to. `sourceKey` is set for it, so nothing has to remember to mark its own rows foreign.
     */
    fun observeDocuments(): Flow<List<DocumentFacts>>

    /**
     * The file behind one of those documents, or null when it has gone.
     *
     * Called only when somebody presses Open or Send. The file is the lending app's — the shelf
     * copies it for the hand-over and never writes to it.
     */
    suspend fun open(documentId: String): File?
}

/**
 * Who is lending the shelf anything, right now.
 *
 * A registry rather than a dependency list, for the same reason LifeOps announces task completions
 * on a bus: Repository must not know which apps exist. An app registers itself when it starts (see
 * each app's `install`), and a build without that app simply has one fewer drawer — no compile-time
 * arrow, no stub, nothing to keep in sync.
 *
 * Registration is idempotent and keyed by [DocumentSource.appKey], so an app that installs twice
 * lends once.
 */
object DocumentSources {

    private val sources = MutableStateFlow<List<DocumentSource>>(emptyList())

    /** Every source registered, as it changes — the shelf collects this. */
    val all = sources.asStateFlow()

    fun register(source: DocumentSource) {
        sources.update { current ->
            if (current.any { it.appKey == source.appKey }) current else current + source
        }
    }

    fun unregister(appKey: String) {
        sources.update { current -> current.filterNot { it.appKey == appKey } }
    }

    fun of(appKey: String): DocumentSource? = sources.value.firstOrNull { it.appKey == appKey }

    /** For tests, which must not inherit whatever an earlier one registered. */
    fun clear() {
        sources.value = emptyList()
    }
}
