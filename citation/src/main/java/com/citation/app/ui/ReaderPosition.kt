package com.citation.app.ui

import com.citation.core.reader.ReadingPace
import com.citation.core.reader.ReadingProgress

/**
 * Where you are in the open book, and how much of it is left.
 */

/**
 * The reader moved. [charOffset] is a **canonical** offset, so both reading modes report the
 * same thing and the numbers do not jump when you switch between them.
 *
 * Forward movement is banked toward the pace estimate; moving backwards is re-reading and
 * measures nothing. A large forward jump (following a search hit, tapping the contents) is
 * banked too, and then discarded by [ReadingPace] for being implausibly fast — which is the
 * right place for that judgement, since only it knows what a plausible rate looks like.
 */
internal fun ReaderViewModel.onPositionChanged(chapterOrdinal: Int, charOffset: Int) {
    val book = _openBook.value ?: return
    val before = ReadingProgress.at(book, _position.value.first, _position.value.second)
    val after = ReadingProgress.at(book, chapterOrdinal, charOffset)
    val advanced = after.charactersRead - before.charactersRead
    if (advanced > 0) paceCharacters += advanced
    _position.value = chapterOrdinal to charOffset
}
