package com.citation.app.ui

import androidx.lifecycle.viewModelScope
import com.citation.app.data.captureNote
import com.citation.app.data.captureSynthesis
import com.citation.app.data.editNoteBody
import com.citation.app.data.linkNoteToBook
import com.citation.app.data.resolveNote
import com.citation.app.data.setNoteTags
import com.citation.core.note.Note
import com.citation.core.note.NoteResolver
import com.citation.core.note.Tags
import kotlinx.coroutines.launch

/**
 * Capturing and editing notes against the open book, and anchoring a quote back into it.
 */

/** Capture a highlight over the current chapter's selection and attach a note. */
internal fun ReaderViewModel.captureNote(selectionStart: Int, selectionEnd: Int, body: String) {
    val book = _openBook.value ?: return
    if (book.key == null) return // unkeyed book — shouldn't happen once opened via the repository
    viewModelScope.launch {
        val note = repository.captureNote(
            book = book,
            chapterOrdinal = _chapterOrdinal.value,
            selectionStart = selectionStart,
            selectionEnd = selectionEnd,
            noteBody = body,
            color = settings.value.highlightColor
        )
        _status.value = "Note ${note.key} captured — queued for LifeOps."
    }
}

/**
 * Locate [quote] in the current chapter and capture a note anchored to it. When a passage repeats
 * in one chapter, the plain first-match would anchor the wrong copy (and freeze the wrong
 * prefix/suffix); [nearOffset] — the character offset near the reader's viewport at capture — breaks
 * the tie toward the occurrence you were actually looking at.
 */
internal fun ReaderViewModel.captureNoteForQuote(quote: String, body: String, nearOffset: Int = 0) {
    val book = _openBook.value ?: return
    val chapter = book.chapterAt(_chapterOrdinal.value) ?: return
    val cleaned = withoutRenderedOnlyCharacters(quote)
    if (cleaned.isBlank()) {
        _status.value = "Select some text to quote."
        return
    }
    val start = nearestIndexOf(chapter.text, cleaned, nearOffset)
    if (start < 0) {
        _status.value = "Couldn’t find that passage in this chapter."
        return
    }
    captureNote(start, start + cleaned.length, body)
}

/**
 * Strip the characters that exist only in the drawn page.
 *
 * A selection is read back as the text the user sees, and the reader draws things the chapter's
 * canonical text does not contain: an image placeholder, a list bullet, the separators between
 * table cells the reduction ran together. Anchoring works against the canonical text, so a
 * selection that happened to span one of those would otherwise fail to match anything and the
 * note would be refused — over a passage the reader can plainly see.
 */
internal fun ReaderViewModel.withoutRenderedOnlyCharacters(quote: String): String {
    var cleaned = quote.replace("\uFFFD", "")
    cleaned = cleaned.replace("   ·   ", "")
    cleaned = cleaned.removePrefix("· · ·")
    cleaned = cleaned.trimStart()
    cleaned = cleaned.removePrefix("• ")
    cleaned = Regex("^\\d+\\. ").replace(cleaned, "")
    return cleaned.trim()
}

/**
 * Capture a freestanding synthesis note over the open book, citing zero or more exact quotes
 * (each located and anchored where found). Your own artifact, kept distinct from a passage note.
 */
internal fun ReaderViewModel.captureSynthesis(citedQuotes: List<String>, body: String) {
    val book = _openBook.value ?: return
    if (book.key == null) return
    viewModelScope.launch {
        val note = repository.captureSynthesis(book, citedQuotes.filter { it.isNotBlank() }, body)
        _status.value = "Synthesis note ${note.key} captured (${note.references.size} citations)."
    }
}

/**
 * Add or edit your own words on a note — the annotation on a captured quote. Captures arrive with
 * an empty body; this is how you make something of them.
 */
internal fun ReaderViewModel.editNote(noteKey: String, body: String) {
    viewModelScope.launch {
        repository.editNoteBody(noteKey, body)
        _status.value = "Note updated."
    }
}

/**
 * Manually link a capture to a book in your library — bind ("capture") its source when no hard
 * identity ever arrived to promote it automatically. Binds the whole cluster the note belongs to, so
 * linking one capture claims all captures from the same source (e.g. a Kindle-notebook export).
 */
internal fun ReaderViewModel.linkNoteToBook(noteKey: String, bookKey: String) {
    viewModelScope.launch {
        val linked = repository.linkNoteToBook(noteKey, bookKey)
        _status.value = if (linked > 0) {
            "Linked $linked capture${if (linked == 1) "" else "s"} to its source."
        } else {
            "Couldn't link that note to a source."
        }
    }
}

/** Delete a note (its inline highlight goes with it). Local-only — see the repository. */
internal fun ReaderViewModel.deleteNote(noteKey: String) {
    viewModelScope.launch {
        repository.deleteNote(noteKey)
        _status.value = "Note deleted."
    }
}

/** Parse raw editor text into normalized tags and store them on the note (local organizational). */
internal fun ReaderViewModel.setNoteTags(noteKey: String, raw: String) {
    viewModelScope.launch {
        repository.setNoteTags(noteKey, Tags.parse(raw))
        _status.value = "Tags updated."
    }
}

/**
 * Render the currently-visible notes to Markdown and hand them to the share sheet as a `.md`
 * file. Exports the filtered set, so narrowing by tag/search first exports just that slice.
 */
internal fun ReaderViewModel.exportVisibleNotes(context: android.content.Context) {
    val visible = filteredNotes.value
    val tag = _activeTag.value
    val subtitle = buildString {
        append("Exported ")
        append(java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date()))
        append(" · ${visible.size} note${if (visible.size == 1) "" else "s"}")
        if (tag != null) append(" · #$tag")
    }
    val md = com.citation.core.note.MarkdownExport.render(visible, subtitle = subtitle)
    val ok = com.citation.app.data.NotesExporter.share(context, md)
    _status.value = if (ok) "Exporting notes…" else "Couldn't export notes."
}

/** Resolve a note's overall degradation state for the Notes list badge. */
internal suspend fun ReaderViewModel.overallState(note: Note): NoteResolver.State =
    NoteResolver.overallState(repository.resolveNote(note))
