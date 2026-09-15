package com.project.app.data.repository

import com.project.app.data.db.dao.ProjectDao
import com.project.app.logic.CompileOptions
import com.project.app.logic.DocBlocks
import com.project.app.logic.Manuscript
import com.project.app.logic.ManuscriptDoc
import com.project.app.logic.Outline
import com.project.app.logic.ProjectSearch
import com.project.app.logic.SearchCorpus
import com.project.app.logic.SearchDoc
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * Search across a project, and compiling its documents into one piece of text.
 */
class ProjectSearchStore(
    private val dao: ProjectDao
) {

    /**
     * Everything in a project that has text in it, in the shape the search matcher wants.
     *
     * The *query* is deliberately not part of this: it lives in the search screen, and filtering
     * happens in pure code over an already-loaded corpus. Pushing the query into SQL would mean a
     * round trip per keystroke, five `LIKE` scans a time, and a matching rule split between Kotlin
     * and SQLite that could disagree with itself. A project's text is small enough to hold.
     */
    fun observeSearchCorpus(projectId: String): Flow<SearchCorpus> {
        val documents = combine(
            dao.observeDocs(projectId),
            dao.observeBlocksOfProject(projectId)
        ) { docs, blocks ->
            val byDoc = blocks.groupBy { it.docId }
            docs.map { doc ->
                SearchDoc(
                    id = doc.id,
                    title = doc.title,
                    // The text as it reads, not as it is stored: a search for a word should find
                    // it whether or not somebody put asterisks round it, and a hit inside a table
                    // should show the cells rather than the pipes between them.
                    text = byDoc[doc.id].orEmpty()
                        .joinToString(" ") { DocBlocks.plainText(it.toLogic()) }
                )
            }
        }

        val rest = combine(
            dao.observeLore(projectId),
            dao.observeTimeline(projectId),
            dao.observeCards(projectId)
        ) { lore, events, cards ->
            Triple(lore.map { it.toLogic() }, events.map { it.toLogic() }, cards.map { it.toLogic() })
        }

        return combine(
            dao.observeOutline(projectId),
            documents,
            rest
        ) { outline, docs, (lore, events, cards) ->
            SearchCorpus(
                outline = outline.map { it.toLogic() },
                docs = docs,
                lore = lore,
                events = events,
                cards = cards
            )
        }
    }

    /** Hits for [query], matched over the corpus in pure code. */
    fun observeSearch(projectId: String, query: String): Flow<List<com.project.app.logic.SearchHit>> =
        observeSearchCorpus(projectId).map { corpus ->
            ProjectSearch.search(query, corpus.outline, corpus.docs, corpus.lore, corpus.events, corpus.cards)
        }

    /**
     * The whole project as one manuscript.
     *
     * Read once, on demand, and never stored: a compile is a *view* of the outline and the documents
     * it links, so caching it would only create a second answer to "how long is this" that could go
     * stale the moment somebody typed.
     */
    suspend fun compile(projectId: String, options: CompileOptions): Manuscript? {
        val project = dao.getProject(projectId) ?: return null
        val rows = Outline.flatten(dao.getOutline(projectId).map { it.toLogic() })
        val blocks = dao.blocksOfProject(projectId).groupBy { it.docId }
        val docs = dao.docsOf(projectId).map { doc ->
            ManuscriptDoc(
                id = doc.id,
                outlineNodeId = doc.outlineNodeId,
                title = doc.title,
                sortOrder = doc.sortOrder,
                blocks = blocks[doc.id].orEmpty().map { it.toLogic() }
            )
        }
        return Manuscript.compile(project.name, rows, docs, options)
    }
}
