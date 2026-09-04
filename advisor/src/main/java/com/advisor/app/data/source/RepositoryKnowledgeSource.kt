package com.advisor.app.data.source

import android.content.Context
import com.advisor.app.logic.KnowledgeDocument
import com.advisor.app.logic.SourceApp
import com.operations.backupkit.AppId
import com.repository.app.data.db.RepositoryDatabase
import com.repository.app.logic.DocumentKind
import com.repository.app.logic.Documents
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Reads the suite's shelf — every document the household has been handed and filed — into
 * [KnowledgeDocument]s: what it is, what it is about, when it arrived and any note left on it.
 *
 * **The rows, never the bytes.** Repository stores a file name and keeps the file itself under
 * `filesDir`; nothing here opens one. Advisor can therefore answer "do we have the furnace manual",
 * "when did the policy arrive", "what did we file about the Jeep" — questions about the *shelf* —
 * and cannot answer what is written on page four of a scan, which would need an OCR pass this app
 * does not have and a privacy conversation it has not had.
 *
 * A filed document already carries the label of the thing it is about ("2018 Jeep Wrangler"), which
 * Repository is given by the owning app and keeps in step with it. That label is what makes these
 * documents findable next to the asset or person they belong to, without this source having to know
 * any other app's model — the same discipline Repository itself keeps.
 *
 * Like every source, it is loaded only when the user has granted Repository in
 * [com.advisor.app.logic.AdvisorPermissions] — the permission gate lives above this class.
 */
class RepositoryKnowledgeSource(context: Context) : KnowledgeSource {

    private val appContext = context.applicationContext
    override val source = SourceApp.REPOSITORY

    override suspend fun load(): List<KnowledgeDocument> {
        val dao = RepositoryDatabase.getInstance(appContext).repositoryDao()
        return dao.allDocuments().map { row ->
            val kind = DocumentKind.fromKey(row.kind)
            val owner = row.ownerLabel?.takeIf { it.isNotBlank() }
            val ownerApp = row.ownerApp?.let { AppId.fromKey(it)?.defaultDisplayName ?: it }
            KnowledgeDocument(
                id = "repository:document:${row.id}",
                source = source,
                kind = "document",
                title = row.title,
                body = buildString {
                    append("Filed document: ").append(row.title)
                    append(" (").append(kind.label.lowercase()).append(')')
                    // "About: 2018 Jeep Wrangler (Maintenance)" — the thing it is about, and the
                    // app that filed it. A document with neither belongs to the household itself,
                    // which is a real place on the shelf rather than a missing value.
                    if (owner != null) {
                        append(". About: ").append(owner)
                        ownerApp?.let { append(" (").append(it).append(')') }
                    } else if (ownerApp != null) {
                        append(". Filed from: ").append(ownerApp)
                    } else {
                        append(". In the household's own drawer")
                    }
                    append(". Filed: ").append(dayOf(row.addedAt))
                    Documents.formatSize(row.sizeBytes)?.let { append(". Size: ").append(it) }
                    row.note?.takeIf { it.isNotBlank() }?.let { append(". Note: ").append(it) }
                },
                timestamp = row.updatedAt
            )
        }
    }

    /** Epoch millis as the day it fell on here — a date a person would recognise, not a number. */
    private fun dayOf(millis: Long): LocalDate =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
}
