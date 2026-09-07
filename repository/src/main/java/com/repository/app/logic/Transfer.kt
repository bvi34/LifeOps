package com.repository.app.logic

import java.util.Locale

/**
 * One file a picker has handed back, before a single byte has been copied.
 *
 * The three facts are what a content resolver will tell you about a file and no more — the same
 * three `data/store/DocumentFiles.Picked` carries, kept here as well because the review list reasons
 * about a *batch* of them and that reasoning belongs in `logic/`. Every field is nullable because
 * providers genuinely supply none of them sometimes, and a cloud provider is the worst offender: a
 * Google Doc has no size until it is exported and may have no extension at all.
 */
data class TransferItem(
    val uri: String,
    val displayName: String?,
    val mimeType: String?,
    val sizeBytes: Long?
) {
    /** Which drive it came from, read off the URI rather than off whatever chip was selected. */
    val drive: Drive? get() = Drives.of(uri)

    /** What to call where it came from, for the line under the file's name. */
    val origin: String? get() = Drives.label(uri)
}

/**
 * One row of the review list: a picked file, the title it would be filed under, and whether it is
 * still in.
 *
 * The review list is the whole difference between this and a file picker. You point at seven things
 * in a folder, and *then* you get to say: not that one, and this one is called something better. A
 * picker that filed all seven the moment it closed would be faster and would be wrong, because the
 * folder on the drive is organised for the drive and the shelf is organised for the household.
 */
data class TransferChoice(
    val item: TransferItem,
    val title: String,
    val include: Boolean = true
)

/**
 * What happened when the batch was filed.
 *
 * Failures are carried by name rather than counted. "One of these couldn't be read" is a sentence
 * that makes somebody re-import all seven; "Q3 budget.xlsx couldn't be read" is one they can act on.
 */
data class TransferOutcome(
    val filed: Int,
    val failed: List<String> = emptyList(),
    /**
     * Documents the folder held that this shelf already has.
     *
     * Only an import with a manifest can know this — see `logic/Sidecar` — and it matters because
     * re-picking a folder is the ordinary case: nobody remembers which four of the twelve files were
     * the new ones. "3 filed · 9 already here" is a household being told its shelf is right, whereas
     * silence after picking twelve files reads like something went wrong.
     */
    val alreadyHere: Int = 0
) {
    val everything: Boolean get() = failed.isEmpty()

    companion object {
        val NOTHING = TransferOutcome(0)
    }
}

/**
 * The reasoning behind grabbing a set of files off a drive, and behind sending some back.
 *
 * Pure, and tested without an emulator. Everything Android-shaped — the picker, the permission
 * grants, the actual copying — lives in `data/store/DriveTransfer`, and the two are kept apart so
 * that the rules a household will notice (what a document ends up called, what gets skipped, what
 * it says afterwards) can be argued about in a test file.
 */
object Transfer {

    /**
     * A picked set, ready to review: every file in, each titled from its own name.
     *
     * The title comes from [Documents.titleFrom], which is deliberately not clever — it takes the
     * extension off and turns separators into spaces. `Q3-budget.xlsx` becomes "Q3 budget" and
     * `IMG_20260214_093122.jpg` stays exactly that ugly, because a wrong guess in a filled-in field
     * gets accepted and an ugly one gets corrected.
     *
     * Order is the picker's, not sorted. The person chose these in some order and a list that
     * rearranges itself between the picker closing and the review opening is a list they have to
     * re-read.
     */
    fun plan(items: List<TransferItem>): List<TransferChoice> =
        items.map { item -> TransferChoice(item, Documents.titleFrom(item.displayName)) }

    /** Only the rows still ticked — what pressing the button will actually file. */
    fun included(choices: List<TransferChoice>): List<TransferChoice> = choices.filter { it.include }

    /**
     * "4 files · 2.1 MB from OneDrive", or "· from OneDrive and this device" when a batch is mixed.
     *
     * The size is a total and may be short: a cloud provider often reports no size at all, and this
     * says the size of what it knows about rather than pretending the unknown ones are empty.
     */
    fun headline(choices: List<TransferChoice>): String {
        val included = included(choices)
        if (included.isEmpty()) return "Nothing selected"
        val count = if (included.size == 1) "1 file" else "${included.size} files"
        val known = included.mapNotNull { it.item.sizeBytes }
        val size = if (known.isEmpty()) null else Documents.formatSize(known.sum())
        val from = origins(included)?.let { "from $it" }
        return listOfNotNull(count, size, from).joinToString(" · ")
    }

    /**
     * Where a batch came from, in words: "OneDrive", "Google Drive and this device", or null when
     * nothing in it came from anywhere this app can name.
     */
    fun origins(choices: List<TransferChoice>): String? {
        val names = choices.mapNotNull { it.item.origin }.distinct()
        return when (names.size) {
            0 -> null
            1 -> names.first()
            2 -> "${names[0]} and ${names[1]}"
            else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
        }
    }

    /**
     * Titles that more than one file in this batch would be filed under, lowercased for comparison.
     *
     * Not corrected — *reported*. Repository has no versions and does not mind two documents with
     * the same name; a household filing this year's and last year's statement out of one folder is
     * the normal case. What it would mind is finding out afterwards. So the review list says "two of
     * these would be called Statement" and leaves the fixing, or the shrugging, to the person.
     */
    fun duplicateTitles(choices: List<TransferChoice>): List<String> =
        included(choices)
            .map { it.title.trim().lowercase(Locale.US) }
            .filter { it.isNotEmpty() }
            .groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }
            .keys
            .sorted()

    /** "Two of these would be called “statement”." — null when there is nothing to say. */
    fun duplicateWarning(choices: List<TransferChoice>): String? {
        val duplicates = duplicateTitles(choices)
        if (duplicates.isEmpty()) return null
        val names = duplicates.joinToString(", ") { "“$it”" }
        return if (duplicates.size == 1) {
            "More than one of these would be filed as $names. That is allowed — rename one if it was not deliberate."
        } else {
            "More than one of these would be filed under each of $names. That is allowed — rename them if it was not deliberate."
        }
    }

    /** "4 documents filed", "3 filed · Q3 budget.xlsx couldn't be read", "Nothing was filed". */
    fun outcomeLine(outcome: TransferOutcome): String {
        val filed = when (outcome.filed) {
            0 -> null
            1 -> "1 document filed"
            else -> "${outcome.filed} documents filed"
        }
        val failed = when {
            outcome.failed.isEmpty() -> null
            outcome.failed.size <= 2 -> outcome.failed.joinToString(" and ") + " couldn't be read"
            else -> "${outcome.failed.size} couldn't be read"
        }
        // Said plainly rather than hidden, because re-picking a folder is the ordinary case and a
        // household told nothing after picking twelve files assumes something went wrong.
        val here = when (outcome.alreadyHere) {
            0 -> null
            1 -> "1 was already here"
            else -> "${outcome.alreadyHere} were already here"
        }
        return listOfNotNull(filed, here, failed).joinToString(" · ").ifBlank { "Nothing was filed" }
    }

    /** "Saved to OneDrive", "Saved 3 documents to Google Drive · Projects". */
    fun savedLine(count: Int, drive: Drive?, folder: String?): String {
        val what = when (count) {
            0 -> return "Nothing was saved"
            1 -> "Saved"
            else -> "Saved $count documents"
        }
        val where = listOfNotNull(drive?.label, folder?.trim()?.takeIf { it.isNotBlank() })
            .joinToString(" · ")
            .ifBlank { "the folder you chose" }
        return "$what to $where"
    }
}
