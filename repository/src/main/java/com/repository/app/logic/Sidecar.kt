package com.repository.app.logic

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException

/**
 * The shelf, travelling with its documents.
 *
 * Repository already moves documents between devices, and does it the way this suite moves anything:
 * targeted, one-shot, over a drive the household already has — save these four to OneDrive, grab
 * those four on the other phone. What that round trip lost was the *shelf*. The bytes arrived; the
 * title went back to whatever the file was called, the kind became "Other", the note was gone, and
 * "the 2018 Jeep Wrangler's manual" became `Manual.pdf` in the household's own drawer.
 *
 * So an export writes one small JSON file beside the documents, and an import reads it. That is the
 * whole mechanism, and what it deliberately is not:
 *
 * - **It is not sync.** Nothing watches the folder, nothing polls, and no credential is involved.
 *   The rule the drive feature was built on still holds — *these files, now* — and a manifest
 *   changes only what a copy taken at a moment carries with it.
 * - **It is not a second store.** Every field here is one a person typed or an owning app supplied.
 *   Nothing is derived, and nothing is read out of the documents.
 * - **It cannot overwrite anything.** An entry naming a document this shelf already holds is
 *   *skipped*, not applied — see [ShelfManifest.entryFor] and the import in `DocumentRepository`.
 *   Two phones both editing a title is a conflict this app has no way to resolve and no business
 *   guessing at.
 *
 * ### Why the id travels
 *
 * [ShelfEntry.id] is the id minted by the shelf that exported the document, and carrying it is what
 * makes importing the same folder twice free. Ids are UUIDs, so one from another install can never
 * collide with one of this shelf's own; a document that arrives with an id already here is the same
 * document coming round again, which is the ordinary case when somebody re-picks a folder rather
 * than remembering exactly which four files were new.
 *
 * ### Why the owner travels
 *
 * A document filed against `maintenance/a3f2` names an asset that may not exist on the other phone.
 * Carrying it anyway costs nothing and is the payoff of the label being **on the row** rather than
 * looked up: if that asset is there, the document lands on it; if it is not, the drawer still reads
 * "Maintenance · 2018 Jeep Wrangler", which is more than a folder of scans could ever say.
 */
data class ShelfEntry(
    /**
     * The name of the file this entry describes, **as it was actually written into the folder**.
     *
     * Not the name the export asked for. A drive that already holds a `Statement.pdf` answers by
     * making `Statement (1).pdf` — every provider does, and that is the right behaviour for a copy
     * of somebody's records — so the name is read back from what was created. A manifest keyed on
     * the name we *wanted* would describe the wrong file the first time two statements met.
     */
    val file: String,
    /** The id the exporting shelf minted. See the note above on why it travels. */
    val id: String,
    val title: String,
    /** `DocumentKind`'s key. An unrecognised one reads as OTHER, because kind is vocabulary. */
    val kind: String,
    val note: String? = null,
    val app: String? = null,
    val record: String? = null,
    val about: String? = null,
    /** When the exporting shelf filed it, so the other one orders it the same way. */
    val addedAt: Long? = null
) {
    val owner: DocumentOwner get() = DocumentOwner(app, record, about)

    val documentKind: DocumentKind get() = DocumentKind.fromKey(kind)
}

/** Everything one export wrote, and the version of this format that wrote it. */
data class ShelfManifest(
    val version: Int = Sidecar.VERSION,
    val documents: List<ShelfEntry> = emptyList()
) {
    /**
     * The entry describing a picked file, matched on its name.
     *
     * The name is all an importing device has to go on: it picked files out of a folder, and the
     * only thing the picker tells it about each one is what it is called.
     */
    fun entryFor(displayName: String?): ShelfEntry? {
        val name = displayName?.trim().orEmpty()
        if (name.isEmpty()) return null
        return documents.firstOrNull { it.file == name }
    }
}

object Sidecar {

    /**
     * The format version.
     *
     * A manifest from a *newer* Repository is ignored rather than half-read — see [decode]. Every
     * field in it is an enrichment, so ignoring one costs captions on an import that still works,
     * whereas reading a v2 field under v1 rules is how a document ends up filed against the wrong
     * thing.
     */
    const val VERSION = 1

    /**
     * What the file is called in the folder, beside the documents.
     *
     * Named for this app rather than something generic like `shelf.json`, because it lands in a
     * folder on somebody's Google Drive next to whatever else is in there, and a household deleting
     * a mystery file is a household that loses its captions.
     */
    const val FILE_NAME = "repository-shelf.json"

    /** The manifest's own type, so a drive shows it as text rather than as an unknown blob. */
    const val MIME_TYPE = "application/json"

    private val gson = Gson()

    fun encode(manifest: ShelfManifest): String = gson.toJson(manifest)

    /**
     * A manifest read back, or null for anything that is not one.
     *
     * Total and quiet: a truncated file, a folder that never had a manifest, something else entirely
     * called the same thing, or a version this build does not understand all come back null, and the
     * import carries on filing the documents plainly. **A missing manifest must never be able to
     * stop somebody importing their own files** — the captions are the nice-to-have here and the
     * documents are the point.
     */
    fun decode(text: String?): ShelfManifest? {
        if (text.isNullOrBlank()) return null
        val manifest = runCatching { gson.fromJson(text, ShelfManifest::class.java) }
            .getOrElse { error -> if (error is JsonSyntaxException) null else null }
            ?: return null
        if (manifest.version > VERSION) return null
        // Gson will happily build an object out of any JSON, so a file that parsed but describes no
        // documents is not a manifest — it is some other JSON that happens to sit in this folder.
        val entries = manifest.documents.filter { it.file.isNotBlank() && it.id.isNotBlank() }
        if (entries.isEmpty()) return null
        return manifest.copy(documents = entries)
    }

    /** Whether a picked file is the manifest rather than a document somebody wants filed. */
    fun isManifest(displayName: String?): Boolean =
        displayName?.trim().equals(FILE_NAME, ignoreCase = true)
}

/**
 * A document on this shelf, as the manifest describes it to another one.
 *
 * [writtenName] is what the drive actually called the copy, which is the only handle the importing
 * device gets — it picked files out of a folder, and all the picker tells it about each one is the
 * name.
 *
 * Deliberately not carried: the size and the MIME type. Both are facts about the *file*, which the
 * importing shelf reads for itself from the bytes it is handed, and a manifest that disagreed with
 * the file beside it would be a row describing something that is not there.
 */
fun DocumentFacts.entry(writtenName: String): ShelfEntry = ShelfEntry(
    file = writtenName,
    id = id,
    title = title,
    kind = kind.key,
    note = note,
    app = owner.appKey,
    record = owner.recordKey,
    about = owner.label,
    addedAt = addedAt
)
