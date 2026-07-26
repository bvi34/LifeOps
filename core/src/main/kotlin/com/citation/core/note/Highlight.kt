package com.citation.core.note

import com.citation.core.anchor.TextAnchor
import com.citation.core.key.EntityKey

/**
 * A highlighted passage: the atom of capture-in-place. The reader turns a text selection into a
 * [Highlight], and a passage-anchored [Note] hangs off it.
 *
 * A highlight already carries everything a note needs to survive its source — the frozen quoted
 * snapshot and the typed anchor — so a highlight can stand on its own (a highlight with no note is a
 * perfectly good bookmark) and a note built from it inherits that durability.
 *
 * @property key the highlight's minted key (e.g. `ER-Highlight-12`).
 * @property source where the passage lives.
 * @property quotedSnapshot the exact selected text, frozen at capture.
 * @property anchor the typed anchor for re-resolution / jump-to-context.
 * @property createdAt epoch millis.
 */
data class Highlight(
    val key: EntityKey,
    val source: SourceDescriptor,
    val quotedSnapshot: String,
    val anchor: TextAnchor,
    val createdAt: Long
) {
    /** Project this highlight into a note's passage reference. */
    fun toReference(): PassageReference = PassageReference(quotedSnapshot, anchor)

    companion object {
        /**
         * Capture a highlight from a flowing-text selection. Slices the surrounding [prefix]/[suffix]
         * context out of [chapterText] so the anchor can later disambiguate a repeated quote, and
         * freezes the selected span as the snapshot.
         */
        fun captureFlowing(
            key: EntityKey,
            source: SourceDescriptor,
            chapterText: String,
            chapterOrdinal: Int,
            selectionStart: Int,
            selectionEnd: Int,
            createdAt: Long,
            contextChars: Int = 32
        ): Highlight {
            val start = selectionStart.coerceIn(0, chapterText.length)
            val end = selectionEnd.coerceIn(start, chapterText.length)
            val quote = chapterText.substring(start, end)
            val prefix = chapterText.substring(maxOf(0, start - contextChars), start)
            val suffix = chapterText.substring(end, minOf(chapterText.length, end + contextChars))
            val anchor = TextAnchor.Flowing(
                chapterOrdinal = chapterOrdinal,
                approxStart = start,
                quote = quote,
                prefix = prefix,
                suffix = suffix
            )
            return Highlight(key, source, quote, anchor, createdAt)
        }
    }
}
