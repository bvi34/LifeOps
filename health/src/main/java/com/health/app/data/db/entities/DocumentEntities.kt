package com.health.app.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Scanned paperwork — the row that names a stored file, never the file.
 */

/**
 * One document the household was handed: an after-visit summary, a lab result, a referral letter, a
 * school form, the bill that turned up three weeks later.
 *
 * The bytes are **not** here. [fileName] names a file under `filesDir/documents/`, exactly as the
 * insurance card images name one under `insurance-cards/` and for the same reason — `health.db` is
 * copied whole by every backup, and a folder of PDFs inside it would be copied with every
 * temperature anybody records. See `data/store/DocumentStore`.
 *
 * **[profileId] is nullable, and that is the design.** A lab result is about one person; an
 * insurance statement, a household consent form or a practice's registration pack is about the
 * house. Forcing every document onto somebody would file the family's paperwork under whoever
 * happened to be selected when it was scanned.
 *
 * The three optional links — [episodeId], [conditionId], [immunizationId] — are plain nullable ids
 * like every other cross-link in this file, so deleting an illness never deletes the discharge
 * summary from it. A document filed against nothing is still a document.
 *
 * Health stores what is here and **reads none of it**: no OCR, no extraction, no interpretation.
 * Every fact on this row was typed by a person. `logic/Documents` carries the argument.
 */
@Entity(
    tableName = "documents",
    indices = [
        Index("profileId"), Index("kind"), Index("documentDate"),
        Index("episodeId"), Index("conditionId"), Index("immunizationId")
    ]
)
data class DocumentEntity(
    @PrimaryKey val id: String,
    /** Null for a document about the household rather than about one person. */
    val profileId: String?,
    val title: String,
    /** [com.health.app.logic.DocumentKind]'s key. A label for grouping, never a behaviour. */
    val kind: String,
    /** The date **on the document**, ISO at whatever precision it carries. See `logic/PartialDate`. */
    val documentDate: String?,
    /** A bare file name under `documents/`, never a path and never bytes. */
    val fileName: String,
    /** As stored — an attached photo is a JPEG whatever it arrived as. */
    val mimeType: String?,
    val sizeBytes: Long?,
    val episodeId: String?,
    val conditionId: String?,
    val immunizationId: String?,
    val providerId: String?,
    val note: String?,
    /** When the row was written, as against the date on the document. See [DoseEntity]. */
    val createdAt: Long,
    val updatedAt: Long
)
