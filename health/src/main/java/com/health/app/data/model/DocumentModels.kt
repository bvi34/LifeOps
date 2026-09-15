package com.health.app.data.model

import com.health.app.logic.DocumentFacts
import com.health.app.logic.DocumentKind

/**
 * Scanned paperwork.
 */

/**
 * One filed document, with the enums resolved.
 *
 * [fileName] names a file in `filesDir/documents/`; the bytes are never on this object and never in
 * the database. [profileId] is null for paperwork that belongs to the household rather than to
 * anybody in it.
 */
data class Document(
    val id: String,
    val profileId: String?,
    val title: String,
    val kind: DocumentKind,
    val documentDate: String?,
    val fileName: String,
    val mimeType: String?,
    val sizeBytes: Long?,
    val episodeId: String?,
    val conditionId: String?,
    val immunizationId: String?,
    val providerId: String?,
    val note: String?,
    val createdAt: Long
) {
    /** The shape `logic/Documents` reasons about — resolved here, once. */
    val facts: DocumentFacts
        get() = DocumentFacts(
            id = id,
            title = title,
            kind = kind,
            profileId = profileId,
            documentDate = documentDate,
            mimeType = mimeType,
            sizeBytes = sizeBytes
        )

    /** "Lab or test result · 14 March 2026 · 1.2 MB" */
    val descriptor: String get() = facts.descriptor

    val isHouseholdDocument: Boolean get() = profileId == null
}
