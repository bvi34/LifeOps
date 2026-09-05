package com.advisor.app.data.source

import android.content.Context
import com.advisor.app.logic.KnowledgeDocument
import com.advisor.app.logic.SourceApp
import com.project.app.data.db.ProjectDatabase
import com.project.app.logic.BlockType
import com.project.app.logic.DocBlock
import com.project.app.logic.DocBlocks
import com.project.app.logic.LoreCategory
import com.project.app.logic.OutlineStatus
import com.project.app.logic.ProjectKind

/**
 * Reads the project shelf into [KnowledgeDocument]s: the projects themselves, their outlines, the
 * documents written under them, the lore, the timeline and the board.
 *
 * Three things are deliberate here:
 *
 *  - **Every document names its project.** The corpus is flat text with no per-row scoping, and a
 *    scene called "The bridge" that doesn't say which book it is in is a document that can be
 *    retrieved into an answer about the wrong one. Nothing from this source is anonymous.
 *  - **A project's own nouns travel with it.** Project keeps a `ProjectKind` so a manuscript's
 *    outline is made of chapters and scenes while a piece of software's is made of features and
 *    tasks; the flattened text says "scene" where the app would, rather than flattening everyone's
 *    work into "outline node".
 *  - **Document bodies are excerpted, not reproduced.** A drafted chapter is thousands of words
 *    that would drown every other source in the corpus and blow the prompt budget on one row. The
 *    opening of each document is indexed — enough for "what is this document about" and for the
 *    retriever to find it — and the document says when there is more.
 *
 * Project's own paperwork — the brief, the contract, the reference PDFs — is not here: those are
 * documents the household filed, they live on Repository's shelf, and
 * [RepositoryKnowledgeSource] indexes them once from there. This source keeps to the writing.
 *
 * Like every source, it is loaded only when the user has granted Project in
 * [com.advisor.app.logic.AdvisorPermissions] — the permission gate lives above this class.
 */
class ProjectKnowledgeSource(context: Context) : KnowledgeSource {

    private val appContext = context.applicationContext
    override val source = SourceApp.PROJECT

    override suspend fun load(): List<KnowledgeDocument> {
        val dao = ProjectDatabase.getInstance(appContext).projectDao()
        val docs = ArrayList<KnowledgeDocument>()

        val projects = dao.allProjects()
        if (projects.isEmpty()) return docs

        val kinds = projects.associate { it.id to ProjectKind.fromKey(it.kind) }
        val names = projects.associate { it.id to it.name }
        fun which(projectId: String) = names[projectId] ?: "a project"

        // --- the projects themselves, each with the shape of what is under it ---
        val outline = dao.allOutlineNodes()
        val projectDocs = dao.allDocs()
        val lore = dao.allLoreEntries()
        val events = dao.allTimelineEvents()
        val columns = dao.allColumns()
        val cards = dao.allCards()

        val outlineByProject = outline.groupBy { it.projectId }
        val cardsByProject = cards.groupBy { it.projectId }
        val outlineTitles = outline.associate { it.id to it.title }
        val columnsById = columns.associateBy { it.id }
        val docTitles = projectDocs.associate { it.id to it.title }

        for (project in projects) {
            val kind = kinds.getValue(project.id)
            val pieces = outlineByProject[project.id].orEmpty()
            val complete = pieces.count { OutlineStatus.fromKey(it.status).isComplete }
            docs += KnowledgeDocument(
                id = "project:project:${project.id}",
                source = source,
                kind = "project",
                title = project.name,
                body = buildString {
                    append("Project: ").append(project.name)
                    append(" (").append(kind.label.lowercase()).append(')')
                    project.summary?.takeIf { it.isNotBlank() }?.let { append(". Summary: ").append(it) }
                    if (pieces.isNotEmpty()) {
                        append(". Outline: ").append(complete).append(" of ").append(pieces.size)
                        append(' ').append(kind.pieces).append(" finished")
                    }
                    val open = cardsByProject[project.id].orEmpty().count { it.doneAt == null }
                    if (open > 0) append(". Board: ").append(open).append(" card(s) still open")
                    if (project.archived) append(". Archived.")
                },
                timestamp = project.updatedAt
            )
        }

        // --- the outline: what the work is made of, and how far each piece has got ---
        for (node in outline) {
            val kind = kinds[node.projectId] ?: ProjectKind.GENERAL
            val status = OutlineStatus.fromKey(node.status)
            val parent = node.parentId?.let { outlineTitles[it] }
            docs += KnowledgeDocument(
                id = "project:outline:${node.id}",
                source = source,
                kind = "outline",
                title = node.title,
                body = buildString {
                    append(kind.piece.replaceFirstChar { it.uppercase() })
                    append(" in ").append(which(node.projectId)).append(": ").append(node.title)
                    // The drafting ladder, in the same word the app shows, so a question about what
                    // is drafted is answered from Project's own reading of it.
                    append(". Status: ").append(status.label.lowercase())
                    parent?.let { append(". Part of: ").append(it) }
                    node.synopsis?.takeIf { it.isNotBlank() }?.let { append(". Synopsis: ").append(it) }
                    if (node.targetWords > 0) {
                        append(". Words: ").append(node.actualWords).append(" of ").append(node.targetWords)
                    } else if (node.actualWords > 0) {
                        append(". Words: ").append(node.actualWords)
                    }
                    if (status == OutlineStatus.CUT) append(". Cut — kept, but not part of the work any more.")
                },
                timestamp = node.updatedAt
            )
        }

        // --- the documents, excerpted ---
        val blocksByDoc = dao.allBlocks().groupBy { it.docId }
        for (doc in projectDocs) {
            val blocks = blocksByDoc[doc.id].orEmpty().map {
                DocBlock(it.id, BlockType.fromKey(it.type), it.text, it.checked)
            }
            val excerpt = excerpt(blocks)
            val scene = doc.outlineNodeId?.let { outlineTitles[it] }
            val folder = doc.parentDocId?.let { docTitles[it] }
            docs += KnowledgeDocument(
                id = "project:doc:${doc.id}",
                source = source,
                kind = "project-doc",
                title = doc.title,
                body = buildString {
                    append("Document in ").append(which(doc.projectId)).append(": ").append(doc.title)
                    folder?.let { append(". Filed under: ").append(it) }
                    scene?.let { append(". The text of: ").append(it) }
                    if (doc.wordCount > 0) append(". ").append(doc.wordCount).append(" words")
                    if (excerpt.text.isNotBlank()) {
                        append(". ").append(if (excerpt.truncated) "Opening: " else "Text: ")
                        append(excerpt.text)
                        if (excerpt.truncated) append(" …")
                    }
                },
                timestamp = doc.updatedAt
            )
        }

        // --- the lore: the wiki a project keeps about its own world ---
        for (entry in lore) {
            val aliases = entry.aliases.orEmpty()
                .split(',').map { it.trim() }.filter { it.isNotBlank() }
            docs += KnowledgeDocument(
                id = "project:lore:${entry.id}",
                source = source,
                kind = "lore",
                title = entry.name,
                body = buildString {
                    append("Lore entry in ").append(which(entry.projectId)).append(": ").append(entry.name)
                    append(" (").append(LoreCategory.fromKey(entry.category).label.lowercase()).append(')')
                    if (aliases.isNotEmpty()) append(". Also known as: ").append(aliases.joinToString(", "))
                    entry.summary?.takeIf { it.isNotBlank() }?.let { append(". Summary: ").append(it) }
                    entry.body.takeIf { it.isNotBlank() }?.let { append(". ").append(clip(it)) }
                },
                timestamp = entry.updatedAt
            )
        }

        // --- the timeline ---
        for (event in events) {
            val scene = event.outlineNodeId?.let { outlineTitles[it] }
            docs += KnowledgeDocument(
                id = "project:event:${event.id}",
                source = source,
                kind = "timeline",
                title = event.title,
                body = buildString {
                    append("Timeline event in ").append(which(event.projectId)).append(": ").append(event.title)
                    event.era?.takeIf { it.isNotBlank() }?.let { append(". Era: ").append(it) }
                    event.whenLabel?.takeIf { it.isNotBlank() }?.let { append(". When: ").append(it) }
                    scene?.let { append(". Happens in: ").append(it) }
                    event.detail?.takeIf { it.isNotBlank() }?.let { append(". ").append(clip(it)) }
                },
                timestamp = event.createdAt
            )
        }

        // --- the board ---
        for (card in cards) {
            val column = columnsById[card.columnId]
            // A card's column *is* its status, and a card whose column was deleted is an orphan
            // rather than a card with no state — Project's own board screen says as much.
            val state = when {
                card.doneAt != null -> "done"
                column == null -> "todo"
                column.isDone -> "done"
                else -> "todo"
            }
            docs += KnowledgeDocument(
                id = "project:card:${card.id}",
                source = source,
                kind = "card",
                title = card.title,
                body = buildString {
                    append("Board card in ").append(which(card.projectId)).append(": ").append(card.title)
                    append(". Status: ").append(state)
                    append(". Column: ").append(column?.name ?: "none — stranded by a deleted column")
                    card.outlineNodeId?.let { id -> outlineTitles[id]?.let { append(". For: ").append(it) } }
                    card.docId?.let { id -> docTitles[id]?.let { append(". Document: ").append(it) } }
                    card.notes?.takeIf { it.isNotBlank() }?.let { append(". Notes: ").append(clip(it)) }
                },
                timestamp = card.doneAt ?: card.createdAt
            )
        }

        return docs
    }

    /** A document's opening, as plain prose: enough to find it and to say what it is about. */
    private fun excerpt(blocks: List<DocBlock>): Excerpt {
        val text = blocks
            .filter { it.type != BlockType.DIVIDER }
            .joinToString(" ") { DocBlocks.plainText(it).trim() }
            .replace(WHITESPACE, " ")
            .trim()
        return if (text.length <= BODY_LIMIT) Excerpt(text, truncated = false)
        else Excerpt(text.take(BODY_LIMIT).substringBeforeLast(' '), truncated = true)
    }

    /** The same cap for a free-text field that is one field rather than a whole document. */
    private fun clip(text: String): String {
        val flat = text.replace(WHITESPACE, " ").trim()
        return if (flat.length <= FIELD_LIMIT) flat else flat.take(FIELD_LIMIT).substringBeforeLast(' ') + " …"
    }

    private data class Excerpt(val text: String, val truncated: Boolean)

    private companion object {
        /** How much of a document's text is indexed. A drafted chapter is not a corpus row. */
        const val BODY_LIMIT = 1_200
        const val FIELD_LIMIT = 600
        val WHITESPACE = Regex("""\s+""")
    }
}
