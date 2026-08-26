package com.health.app.logic

import java.util.Locale

/**
 * The paperwork — after-visit summaries, lab results, referral letters, imaging reports, school
 * forms, the bill that arrived three weeks later.
 *
 * Health could already keep one kind of document: a photograph of an insurance card. That mechanism
 * turned out to be the right one — the bytes live beside the database rather than inside it, the row
 * stores only a file name, and the backup carries the directory and restores it *before* the rows
 * that name it. This generalises that to everything else a household is handed.
 *
 * ### Health stores documents. It does not read them.
 *
 * There is no OCR here, no extraction, no "we noticed your cholesterol is up". A stored document is
 * handed back exactly as it arrived, and every fact Health holds about it — what kind it is, what
 * date it carries, who it is about — is something a person typed. That is the same line the drug
 * lookup draws at not computing a dose from a label, and it is what makes the feature safe to build
 * at all: an app that parsed a lab report would be interpreting a medical document, and this one is
 * not qualified to.
 *
 * It is also the seam a later change can build on. Reading an EOB into claim rows is a real feature
 * and a genuinely useful one, but it is *parsing*, and parsing belongs in the change that owns it —
 * not smuggled in underneath a file picker. [DocumentKind.BILL] exists here so a household can file
 * the PDF; nothing reads it.
 */

/**
 * What a document is, as the person filing it says.
 *
 * A label rather than a schema: it decides how the list groups and reads, and nothing else. Health
 * does not behave differently for a lab result than for a school form, because it cannot tell the
 * difference and does not look.
 */
enum class DocumentKind(val key: String, val label: String) {
    AFTER_VISIT("after_visit", "After-visit summary"),
    LAB("lab", "Lab or test result"),
    IMAGING("imaging", "Imaging report"),
    PRESCRIPTION("prescription", "Prescription"),
    REFERRAL("referral", "Referral"),
    VACCINE_RECORD("vaccine_record", "Vaccination record"),
    SCHOOL_FORM("school_form", "School or camp form"),
    INSURANCE("insurance", "Insurance paperwork"),

    /** A bill, a statement, an EOB. Filed as a document; **nothing reads it** — see this file's note. */
    BILL("bill", "Bill or statement"),

    OTHER("other", "Other");

    companion object {
        fun fromKey(key: String?): DocumentKind = entries.firstOrNull { it.key == key } ?: OTHER
    }
}

/**
 * One stored document as this file reasons about it.
 *
 * [profileId] is nullable, and the nullability is the point: a lab result is about one person, while
 * an insurance statement or a household consent form is about the house. Forcing every document onto
 * somebody would mean filing the family's paperwork under whoever happened to be selected.
 */
data class DocumentFacts(
    val id: String,
    val title: String,
    val kind: DocumentKind,
    val profileId: String?,
    /** ISO at whatever precision the document itself carries. See [PartialDates]. */
    val documentDate: String?,
    val mimeType: String?,
    val sizeBytes: Long?
) {
    val date: PartialDate? get() = PartialDates.parse(documentDate)

    /** "Lab or test result · 14 March 2026 · 1.2 MB" — everything known, nothing inferred. */
    val descriptor: String
        get() = listOfNotNull(
            kind.label,
            PartialDates.format(date),
            Documents.formatSize(sizeBytes)
        ).joinToString(" · ")
}

object Documents {

    /**
     * Newest first, by the date **on the document** rather than by when it was filed.
     *
     * Those differ constantly — a household types up a folder of last year's paperwork in one
     * sitting — and the date that matters is the one a person is looking for. Undated documents sort
     * last: they are still real records, and they are the ones nobody can place in a sequence.
     */
    fun <T> sort(items: List<T>, date: (T) -> String?, title: (T) -> String): List<T> =
        items.sortedWith(
            compareByDescending<T> { PartialDates.parse(date(it))?.date }
                .thenBy { title(it).lowercase() }
        )

    /** Whether the picked file is a picture, which is the only kind Health re-encodes. */
    fun isImage(mimeType: String?): Boolean = mimeType?.startsWith("image/", ignoreCase = true) == true

    /**
     * The extension to store a file under.
     *
     * Taken from the picked file's own name when it has one, and only otherwise from its MIME type.
     * A name is what the person recognises, and a content resolver's idea of a type is sometimes
     * `application/octet-stream` for a perfectly ordinary PDF.
     */
    fun extensionFor(displayName: String?, mimeType: String?): String {
        val fromName = displayName?.substringAfterLast('.', "")
            ?.lowercase(Locale.ROOT)
            ?.filter { it.isLetterOrDigit() }
            ?.takeIf { it.isNotBlank() && it.length <= 5 }
        if (fromName != null) return fromName
        return when (mimeType?.lowercase(Locale.ROOT)) {
            "application/pdf" -> "pdf"
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/heic" -> "heic"
            "image/webp" -> "webp"
            "text/plain" -> "txt"
            else -> "bin"
        }
    }

    /**
     * A title to start the form with, from the picked file's own name.
     *
     * The extension goes, underscores and dashes become spaces, and that is all — nothing is
     * capitalised or prettified. "AVS_2026-03-14" is what the practice called it and is what the
     * person will recognise in a list; rewriting it as "Avs 2026 03 14" helps nobody.
     */
    fun titleFrom(displayName: String?): String? =
        displayName?.substringBeforeLast('.')
            ?.replace('_', ' ')
            ?.replace('-', ' ')
            ?.trim()
            ?.ifBlank { null }

    /**
     * "1.2 MB". Null when nobody recorded a size, rather than "0 bytes" — a document whose size is
     * unknown and one that is empty are different things, and only one of them is a problem.
     */
    fun formatSize(bytes: Long?): String? {
        if (bytes == null || bytes <= 0) return null
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        return when {
            mb >= 1.0 -> String.format(Locale.getDefault(), "%.1f MB", mb)
            kb >= 1.0 -> String.format(Locale.getDefault(), "%.0f KB", kb)
            else -> "$bytes bytes"
        }
    }

    /**
     * Whether a stored file name is one this app wrote.
     *
     * Rows should only ever hold a bare name; a value containing a path separator or a parent
     * reference is either a bug or something worse, and is refused rather than resolved. The same
     * check guards the card store and the backup's restore path.
     */
    fun isSafeFileName(name: String?): Boolean {
        val value = name?.trim() ?: return false
        return value.isNotBlank() && !value.contains('/') && !value.contains('\\') && !value.contains("..")
    }
}
