package com.advisor.app.data.source

import android.content.Context
import com.advisor.app.logic.KnowledgeDocument
import com.advisor.app.logic.SourceApp
import com.citation.app.data.db.CitationDatabase

/**
 * Reads Citation's library and notes into [KnowledgeDocument]s: the books the user has (title,
 * author, reading state) and the notes/highlights they captured. This is what lets Advisor answer
 * "what have I been reading" or surface a note without the user hunting for it.
 */
class CitationKnowledgeSource(context: Context) : KnowledgeSource {

    private val appContext = context.applicationContext
    override val source = SourceApp.CITATION

    override suspend fun load(): List<KnowledgeDocument> {
        val db = CitationDatabase.get(appContext)
        val docs = ArrayList<KnowledgeDocument>()

        for (book in db.bookDao().getAll()) {
            docs += KnowledgeDocument(
                id = "citation:book:${book.key}",
                source = source,
                kind = "book",
                title = book.title,
                body = buildString {
                    append("Book: ").append(book.title)
                    book.author?.takeIf { it.isNotBlank() }?.let { append(" by ").append(it) }
                    append(". Source: ").append(book.sourceType)
                    append(". Reading state: ").append(book.readingState)
                    if (book.isFavorite) append(". Favourite.")
                },
                timestamp = book.createdAt
            )
        }

        for (note in db.noteDao().getAllSync()) {
            val label = note.frozenTitle.ifBlank { "Note" }
            docs += KnowledgeDocument(
                id = "citation:note:${note.key}",
                source = source,
                kind = "note",
                title = label,
                body = buildString {
                    append("Note on \"").append(label).append('"')
                    note.frozenAuthor?.takeIf { it.isNotBlank() }?.let { append(" by ").append(it) }
                    append(": ").append(note.body)
                },
                timestamp = note.createdAt
            )
        }

        return docs
    }
}
