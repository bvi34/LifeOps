package com.citation.core.note

import com.citation.core.anchor.TextAnchor
import com.citation.core.key.EntityKey
import com.citation.core.model.SourceType

/**
 * A note captured while reading. Notes live in the **sovereign store** and **outlive their source**:
 * when a borrowed source is evicted or vanishes, the note stands alone — degraded (you may not be
 * able to jump back to live context) but never lost, because everything needed to understand it was
 * frozen at capture time.
 *
 * Two distinct kinds share this record, kept deliberately separate (see [NoteType]):
 *  - a **passage-anchored** note hangs off exactly one highlight in one source;
 *  - a **freestanding synthesis** note is your own artifact and may reference several anchors.
 *
 * @property key the note's minted key (e.g. `ER-Note-88`).
 * @property type which kind this is.
 * @property body your text.
 * @property source descriptor of where the note points (frozen; legible without the live source).
 * @property references the anchored passages: exactly one for [NoteType.PASSAGE_ANCHORED], zero or
 *   more for [NoteType.FREESTANDING_SYNTHESIS].
 * @property createdAt epoch millis.
 * @property tags free-form organizational labels (normalized lowercase, no leading `#`). A local
 *   retrieval layer — they let a pile of highlights be queried and grouped. Not carried on the sync
 *   wire (a note's *text* syncs; its filing is Citation's own).
 * @property highlightColor the colour this note's passage is shaded in. Filing, like [tags], and
 *   kept off the sync wire for the same reason — and, unlike tags, filing you can see while reading.
 */
data class Note(
    val key: EntityKey,
    val type: NoteType,
    val body: String,
    val source: SourceDescriptor,
    val references: List<PassageReference>,
    val createdAt: Long,
    val tags: List<String> = emptyList(),
    val highlightColor: HighlightColor = HighlightColor.YELLOW
) {
    init {
        if (type == NoteType.PASSAGE_ANCHORED) {
            require(references.size == 1) {
                "A passage-anchored note must reference exactly one passage, got ${references.size}"
            }
        }
    }

    companion object {
        /** Build a passage-anchored note from a single [highlight]. */
        fun anchored(
            key: EntityKey,
            body: String,
            highlight: Highlight,
            createdAt: Long,
            color: HighlightColor = HighlightColor.YELLOW
        ): Note = Note(
            key = key,
            type = NoteType.PASSAGE_ANCHORED,
            body = body,
            source = highlight.source,
            references = listOf(highlight.toReference()),
            createdAt = createdAt,
            highlightColor = color
        )

        /** Build a freestanding synthesis note, optionally citing several passages. */
        fun synthesis(
            key: EntityKey,
            body: String,
            source: SourceDescriptor,
            references: List<PassageReference> = emptyList(),
            createdAt: Long
        ): Note = Note(
            key = key,
            type = NoteType.FREESTANDING_SYNTHESIS,
            body = body,
            source = source,
            references = references,
            createdAt = createdAt
        )
    }
}

/** The two note kinds, kept distinct so synthesis is never mistaken for a passage annotation. */
enum class NoteType {
    /** Hangs off one highlight in one source. */
    PASSAGE_ANCHORED,
    /** Your own artifact; may reference several anchors, or none. */
    FREESTANDING_SYNTHESIS
}

/**
 * A reference from a note to a passage: the **quoted-passage snapshot** plus its **typed anchor**.
 *
 * The snapshot is the self-sufficient context — no reconstructing months later what a concept was,
 * even if the source is long gone. The anchor is how the reader jumps back to live context when the
 * source is still present; it is best-effort for borrowed sources and reliable for owned/internal.
 *
 * @property quotedSnapshot the passage text as it read at capture — frozen, never re-derived.
 * @property anchor the typed anchor for jump-to-context.
 */
data class PassageReference(
    val quotedSnapshot: String,
    val anchor: TextAnchor
)

/**
 * Where a note points, captured in a form legible without the live source. Carried into sync
 * packets verbatim so LifeOps can read a note without ever holding the book.
 *
 * @property bookKey the book's entity key when bound (`null` for a not-yet-bound target).
 * @property sourceType the kind of source (drives jump reliability).
 * @property sourceId the source's own id (RR fiction id, ISBN, file hash) as a string.
 * @property title frozen title for display when the source is absent.
 * @property author frozen author for display.
 */
data class SourceDescriptor(
    val bookKey: EntityKey?,
    val sourceType: SourceType,
    val sourceId: String?,
    val title: String,
    val author: String?
)

/**
 * The colour a highlighted passage is shaded in.
 *
 * A fixed, named set rather than a colour wheel, and that is the point rather than a shortcut. A
 * highlight colour is only useful if it *means* something — yellow for what the book says, blue for
 * what you disagree with — and a meaning has to stay stable across a year of reading to be worth
 * anything. Five you can tell apart at a glance and remember beats a million you cannot.
 *
 * It pairs with [Note.tags]: tags are the filing you search, these are the filing you can see while
 * turning pages, without opening a single note.
 *
 * The [tint] is a full-strength colour, not the shade actually drawn. What reaches the page depends
 * on the page — see `ReaderPalette.highlight`, which mixes it into whatever colour the reader has
 * set their pages to, as strongly as the text on top can still be read over.
 */
enum class HighlightColor(val label: String, val tint: Int) {
    YELLOW("Yellow", 0xFFFFD54F.toInt()),
    GREEN("Green", 0xFF81C784.toInt()),
    BLUE("Blue", 0xFF64B5F6.toInt()),
    PINK("Pink", 0xFFF06292.toInt()),
    PURPLE("Purple", 0xFFBA68C8.toInt());

    companion object {
        /** Tolerant lookup by name; anything unknown (or absent) is a plain [YELLOW] highlight. */
        fun from(value: String?): HighlightColor =
            entries.firstOrNull { it.name == value } ?: YELLOW
    }
}
