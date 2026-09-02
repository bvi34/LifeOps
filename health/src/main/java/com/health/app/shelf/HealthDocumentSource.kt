package com.health.app.shelf

import com.health.app.HealthApp
import com.health.app.logic.DocumentKind as HealthKind
import com.operations.backupkit.AppId
import com.repository.app.logic.DocumentFacts
import com.repository.app.logic.DocumentKind
import com.repository.app.logic.DocumentOwner
import com.repository.app.source.DocumentSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File

/**
 * Health lending its paperwork to the suite's shelf.
 *
 * Health kept documents before Repository existed, and kept them properly: the bytes beside its
 * database, the folder carried by its own backup, a person's documents deleted with the person.
 * Moving them would be a migration of the most sensitive rows in the suite to buy a tidier diagram,
 * and the household would get nothing for the risk. So the shelf reads them where they are.
 *
 * **Read-only, deliberately.** The shelf can find a lab result and send it to a printer; it cannot
 * rename, re-file or delete one, because the rules for that are Health's — and a second writer would
 * either duplicate them or break them. Everything that changes a document still happens in Health.
 *
 * What this costs Health is this file. What it buys the household is that "where is Sam's bloods
 * result" is answered on the shelf, next to the mortgage statement, without anybody remembering
 * which app it went into.
 */
class HealthDocumentSource(private val health: HealthApp) : DocumentSource {

    override val appKey: String = AppId.HEALTH.key

    override val label: String = AppId.HEALTH.defaultDisplayName

    override fun observeDocuments(): Flow<List<DocumentFacts>> =
        health.repository.observeAllDocumentsWithOwner().map { rows ->
            rows.map { (document, person) ->
                DocumentFacts(
                    id = document.id,
                    title = document.title,
                    kind = document.kind.onTheShelf(),
                    // A document about nobody in particular is the household's — an insurance
                    // statement, a consent form — and says so by carrying no label, exactly as it
                    // does inside Health.
                    owner = DocumentOwner(
                        appKey = appKey,
                        recordKey = document.profileId,
                        label = person
                    ),
                    mimeType = document.mimeType,
                    sizeBytes = document.sizeBytes,
                    addedAt = document.createdAt,
                    note = document.note
                )
            }
        }

    override suspend fun open(documentId: String): File? {
        val document = health.repository.getDocument(documentId) ?: return null
        return health.documents.file(document.fileName)
    }

    /**
     * Health's kinds said in the shelf's vocabulary.
     *
     * The two lists are different on purpose: Health names what a household is handed by a practice,
     * and the shelf names what a household is handed by anyone. Mapping loses a little — a lab result
     * and an imaging report are both "Report" out here — and that is the right trade, because the
     * precise label is still on the document inside Health, where somebody chose it.
     */
    private fun HealthKind.onTheShelf(): DocumentKind = when (this) {
        HealthKind.AFTER_VISIT -> DocumentKind.RECORD
        HealthKind.LAB -> DocumentKind.REPORT
        HealthKind.IMAGING -> DocumentKind.REPORT
        HealthKind.PRESCRIPTION -> DocumentKind.RECORD
        HealthKind.REFERRAL -> DocumentKind.CORRESPONDENCE
        HealthKind.VACCINE_RECORD -> DocumentKind.RECORD
        HealthKind.SCHOOL_FORM -> DocumentKind.OTHER
        HealthKind.INSURANCE -> DocumentKind.POLICY
        HealthKind.BILL -> DocumentKind.STATEMENT
        HealthKind.OTHER -> DocumentKind.OTHER
    }
}
