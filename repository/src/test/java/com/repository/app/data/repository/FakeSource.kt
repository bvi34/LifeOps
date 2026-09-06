package com.repository.app.data.repository

import com.repository.app.logic.DocumentFacts
import com.repository.app.logic.DocumentKind
import com.repository.app.logic.DocumentOwner
import com.repository.app.source.DocumentSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * An app lending the shelf its own paperwork — Health, as far as the store is concerned.
 *
 * The half of the shelf that is not Repository's own rows, and the half whose rules are easiest to
 * get wrong: a lent document must appear in the one list, must arrive stamped with the app that
 * holds it, must hand over a copy when somebody presses Send, and must be *unreachable* from every
 * write on this class. Nothing here is a stub of the real Health source — it is a real
 * [DocumentSource], which is all Repository has ever known about any lender.
 */
internal class FakeSource(
    override val appKey: String,
    override val label: String = appKey,
    documents: List<DocumentFacts> = emptyList(),
    /** The file this app would hand over for any of its documents; null for one that has gone. */
    private val stored: File? = null
) : DocumentSource {

    private val state = MutableStateFlow(documents)

    override fun observeDocuments(): Flow<List<DocumentFacts>> = state.asStateFlow()

    override suspend fun open(documentId: String): File? =
        stored?.takeIf { state.value.any { doc -> doc.id == documentId } }

    fun lend(documents: List<DocumentFacts>) {
        state.value = documents
    }

    companion object {

        /** A lent document, shaped the way a lending app fills one in. */
        fun document(
            id: String,
            title: String,
            addedAt: Long,
            owner: DocumentOwner = DocumentOwner.HOUSEHOLD
        ) = DocumentFacts(
            id = id,
            title = title,
            kind = DocumentKind.RECORD,
            owner = owner,
            mimeType = "application/pdf",
            sizeBytes = 4_096L,
            addedAt = addedAt
            // `sourceKey` is deliberately left null: stamping it is the shelf's job, and a test that
            // set it here would be asserting its own fixture.
        )
    }
}

/**
 * A lender that throws the moment it is asked what it is holding.
 *
 * A hosted app mid-restore, or one whose database was closed underneath it. The shelf must still
 * open — an app that cannot answer costs a drawer, never the list.
 */
internal class BrokenSource(override val appKey: String) : DocumentSource {
    override val label: String = appKey
    override fun observeDocuments(): Flow<List<DocumentFacts>> = throw IllegalStateException("mid-restore")
    override suspend fun open(documentId: String): File? = null
}
