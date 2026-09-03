package com.repository.app.logic

import java.util.Locale

/**
 * The paperwork, for the whole household.
 *
 * Every app in this suite eventually reaches the same wall: a thing it tracks has a piece of paper
 * attached to it. The mortgage statement belongs to the house, the manual to the furnace, the
 * warranty to the mower, the lab result to a person. Health solved it for itself — bytes beside the
 * database, a file name on the row, the directory carried by the backup — and that mechanism was
 * right. This generalises it, so the next app does not solve it a third time and so a household can
 * find its documents without first remembering which app it filed them in.
 *
 * ### Repository stores documents. It does not read them.
 *
 * No OCR, no extraction, no parsing, no "we noticed your rate went up". A stored document is handed
 * back exactly as it arrived, and every fact this app holds about one — what kind it is, what it is
 * called, what it belongs to — is something a person typed or an owning app supplied. That line is
 * what makes it safe to put a mortgage statement and a lab result in the same drawer: nothing here
 * is capable of knowing what is in either.
 *
 * Parsing a statement into a balance is a real feature and a useful one. It belongs in the change
 * that owns it, not smuggled in underneath a file picker.
 */

/**
 * What a document is, as the person filing it says.
 *
 * A label rather than a schema: it decides how the shelf groups and reads, and nothing else. The app
 * does not behave differently for a deed than for a receipt, because it cannot tell the difference
 * and does not look.
 *
 * The list is deliberately household-shaped rather than per-app. An owning app may pass the kind it
 * knows ("manual" for a furnace's PDF) and everything else on the shelf still sorts beside it.
 */
enum class DocumentKind(val key: String, val label: String) {
    STATEMENT("statement", "Statement or bill"),
    POLICY("policy", "Policy or cover"),
    CONTRACT("contract", "Contract or agreement"),
    TITLE("title", "Title or deed"),
    RECEIPT("receipt", "Receipt or invoice"),
    MANUAL("manual", "Manual or instructions"),
    WARRANTY("warranty", "Warranty"),
    REPORT("report", "Report or inspection"),
    CORRESPONDENCE("correspondence", "Letter or notice"),
    IDENTIFICATION("identification", "Identification"),
    RECORD("record", "Record"),
    OTHER("other", "Other");

    companion object {
        fun fromKey(key: String?): DocumentKind = entries.firstOrNull { it.key == key } ?: OTHER
    }
}

/**
 * Where a document was filed from — the app that owns the thing it is about, and which of that
 * app's things.
 *
 * [label] is carried rather than looked up, and that is the decision that keeps this module free of
 * every other one: Repository cannot ask Maintenance what asset `a3f2` is, because it does not know
 * Maintenance exists. The owning app says "2018 Jeep Wrangler" when it files the document, and says
 * it again when the asset is renamed. A label that drifts is a cosmetic problem; a dependency on
 * eight apps' models would be a structural one.
 *
 * All three are null for a document filed straight onto the shelf — a will, a passport, the house's
 * survey. Those belong to the household rather than to anything the suite tracks, and they are the
 * reason this app exists as more than a library.
 */
data class DocumentOwner(
    /** `com.operations.backupkit.AppId.key`, kept as a string so `logic/` stays framework-free. */
    val appKey: String? = null,
    val recordKey: String? = null,
    val label: String? = null
) {
    val isFiled: Boolean get() = appKey != null

    companion object {
        val HOUSEHOLD = DocumentOwner()
    }
}

/**
 * One document as this file reasons about it — the row, never the bytes.
 *
 * [sourceKey] says which drawer it came out of: null for Repository's own store, and an app's key
 * for a document that lives in *that* app and is only being shown here (see
 * `source/DocumentSource`). The shelf shows both in one list because a household looking for a lab
 * result does not care which app is holding it, and the distinction only matters at the moment
 * somebody edits or deletes — which is why it is on the row rather than inferred.
 */
data class DocumentFacts(
    val id: String,
    val title: String,
    val kind: DocumentKind,
    val owner: DocumentOwner,
    val mimeType: String?,
    val sizeBytes: Long?,
    /** When it was filed. Not the date on the document — nothing here reads the document. */
    val addedAt: Long,
    val note: String? = null,
    val sourceKey: String? = null
) {
    val isForeign: Boolean get() = sourceKey != null

    /** "Statement or bill · 1.2 MB" — everything known, nothing inferred. */
    val descriptor: String
        get() = listOfNotNull(kind.label, Documents.formatSize(sizeBytes)).joinToString(" · ")
}

object Documents {

    /** The kinds a picker offers first, because they are what households actually file. */
    val COMMON: List<DocumentKind> = listOf(
        DocumentKind.STATEMENT,
        DocumentKind.POLICY,
        DocumentKind.RECEIPT,
        DocumentKind.MANUAL,
        DocumentKind.WARRANTY,
        DocumentKind.OTHER
    )

    /**
     * "1.2 MB". Null in, null out — an unknown size is not "0 bytes".
     *
     * Rounded to one decimal above a megabyte and to none below, because the figure is here to say
     * *whether this is the scan or the photo of the scan*, and nobody needs three digits for that.
     */
    fun formatSize(bytes: Long?): String? {
        if (bytes == null || bytes < 0L) return null
        return when {
            bytes < 1_000L -> "$bytes bytes"
            bytes < 1_000_000L -> "${bytes / 1_000} KB"
            else -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
        }
    }

    /** Whether the operating system says this is a picture, which is the one thing storage cares. */
    fun isImage(mimeType: String?): Boolean = mimeType?.startsWith("image/", ignoreCase = true) == true

    /**
     * The extension to store a file under, from what the picker said.
     *
     * The display name wins when it carries one, because that is the file the household was given.
     * Failing that a handful of types are mapped by hand — the ones a household is actually handed —
     * and anything else is `bin`, which is honest: the row keeps the real MIME type, and the
     * extension here only names a file on disk.
     */
    fun extensionFor(displayName: String?, mimeType: String?): String {
        val fromName = displayName?.substringAfterLast('.', "")
            ?.lowercase(Locale.US)
            ?.takeIf { it.isNotEmpty() && it.length <= 8 && it.all { c -> c.isLetterOrDigit() } }
        if (fromName != null) return fromName
        return when (mimeType?.lowercase(Locale.US)?.substringBefore(';')?.trim()) {
            "application/pdf" -> "pdf"
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/heic" -> "heic"
            "image/webp" -> "webp"
            "text/plain" -> "txt"
            "text/csv" -> "csv"
            "application/msword" -> "doc"
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx"
            "application/vnd.ms-excel" -> "xls"
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "xlsx"
            else -> "bin"
        }
    }

    /**
     * A title to start the form with, from the file name the picker gave.
     *
     * The extension goes, separators become spaces, and that is all. It is deliberately *not*
     * clever: `2026-03-statement.pdf` becomes "2026 03 statement" rather than something that guesses
     * at a month, because a wrong guess in a filled-in field is worse than an ugly one — somebody
     * reads and corrects the ugly one, and accepts the wrong one.
     */
    fun titleFrom(displayName: String?): String {
        val stem = displayName?.substringBeforeLast('.', displayName)?.trim().orEmpty()
        return stem.map { if (it == '_' || it == '-') ' ' else it }
            .joinToString("")
            .replace(WHITESPACE, " ")
            .trim()
    }

    /**
     * The name a document leaves under: its title, made safe for a file system, plus [extension].
     *
     * Used by both roads out — the copy handed to a share sheet and the copy written into a folder
     * on a drive — so that a statement arrives called "Mortgage statement March 2026.pdf" in an
     * email and under exactly the same name in OneDrive. One rule, because two would drift and the
     * household would end up with two names for one document.
     *
     * Everything that is not a letter, a digit or a space becomes a hyphen. That is blunter than the
     * set of characters any one file system actually forbids, and deliberately so: this name is
     * about to be handed to a file system nobody here can see, which may be FAT on an SD card or a
     * cloud provider with rules of its own.
     */
    fun exportFileName(title: String, extension: String): String {
        val safeTitle = title.map { if (it.isLetterOrDigit() || it == ' ') it else '-' }
            .joinToString("")
            .replace(WHITESPACE, " ")
            .trim()
            .ifBlank { "document" }
        return "$safeTitle.${extension.ifBlank { "bin" }}"
    }

    /**
     * Whether a stored name is a bare file name and nothing else.
     *
     * A row should only ever hold one. Anything with a separator in it, or the two dots that climb a
     * directory, is refused rather than resolved — the store is a flat folder, and the only way a
     * path could appear on a row is a bug or a restored file somebody edited.
     */
    fun isSafeFileName(name: String?): Boolean {
        val value = name?.trim().orEmpty()
        return value.isNotEmpty() &&
            !value.contains('/') &&
            !value.contains('\\') &&
            value != "." &&
            value != ".." &&
            !value.contains("..")
    }

    private val WHITESPACE = Regex("\\s+")
}
