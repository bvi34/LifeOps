package com.citation.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.citation.app.data.clearReaderSettings
import com.citation.app.data.deleteReaderFont
import com.citation.app.data.hasOwnReaderSettings
import com.citation.app.data.readerFonts
import com.citation.app.data.renameReaderFont
import com.citation.app.data.saveReaderSettings
import com.citation.app.data.setNoteHighlight
import com.citation.app.data.storeReaderFont
import com.citation.app.data.sync
import com.citation.core.note.HighlightColor
import com.citation.core.reader.ReaderSettings
import com.citation.core.reader.ReaderTypeface
import com.citation.core.reader.VolumeKeys
import kotlinx.coroutines.launch

/**
 * How the reader looks — type, spacing, theme, page turns — globally and per book.
 */

internal fun ReaderViewModel.refreshPerBookFlag(bookKey: String?) {
    if (bookKey == null) {
        _perBookSettings.value = false
        return
    }
    viewModelScope.launch { _perBookSettings.value = repository.hasOwnReaderSettings(bookKey) }
}

/**
 * Change a setting.
 *
 * Writes to whichever scope the reader is currently editing: this book, when it has been given
 * its own settings, otherwise the global set. That is what makes the "just this book" switch
 * mean something afterwards rather than only at the moment it is flipped.
 */
internal fun ReaderViewModel.updateSettings(transform: (ReaderSettings) -> ReaderSettings) {
    val current = settings.value
    val bookKey = _openBook.value?.key?.toString()
    val scope = if (_perBookSettings.value) bookKey else null
    viewModelScope.launch { repository.saveReaderSettings(transform(current), scope) }
}

/**
 * Give the open book its own settings, or put it back on the global ones.
 *
 * Turning it on forks whatever the book is being read with right now, so nothing visibly
 * changes at the moment of the switch — it only stops following the global set from here.
 */
internal fun ReaderViewModel.setPerBookSettings(own: Boolean) {
    val bookKey = _openBook.value?.key?.toString() ?: return
    val current = settings.value
    viewModelScope.launch {
        if (own) repository.saveReaderSettings(current, bookKey) else repository.clearReaderSettings(bookKey)
        _perBookSettings.value = own
    }
}

/**
 * Recolour a highlight.
 *
 * Not a re-post: what a note *says* travels on the sync wire, what its mark looks like on your
 * page does not. Recolouring one is filing, like tagging it.
 */
internal fun ReaderViewModel.setNoteHighlight(noteKey: String, color: HighlightColor) {
    viewModelScope.launch { repository.setNoteHighlight(noteKey, color) }
}

/**
 * Store a font the reader picked and set the book to use it.
 *
 * [pickedName] is the document's own name, which becomes the font's first label — what the file
 * is called on disk here is a digest of its bytes, and no reader would recognise a face by it.
 */
internal fun ReaderViewModel.addReaderFont(bytes: ByteArray, extension: String, pickedName: String? = null) {
    viewModelScope.launch {
        val path = repository.storeReaderFont(bytes, extension, pickedName)
        if (path == null) {
            _status.value = "Couldn’t read that font file."
            return@launch
        }
        val fonts = repository.readerFonts()
        _readerFonts.value = fonts
        updateSettings { it.copy(typeface = ReaderTypeface.CUSTOM, customFontPath = path) }
        _status.value = "Reading in ${fonts.firstOrNull { it.path == path }?.name ?: "your font"}."
    }
}

/**
 * Rename a font the reader added.
 *
 * A rename touches the label only, never the file: the font's name on disk is its content digest
 * and every book already set in it refers to it by that path.
 */
internal fun ReaderViewModel.renameReaderFont(path: String, name: String) {
    viewModelScope.launch {
        if (!repository.renameReaderFont(path, name)) {
            _status.value = "Couldn’t rename that font."
            return@launch
        }
        _readerFonts.value = repository.readerFonts()
    }
}

/**
 * Remove a font the reader added.
 *
 * If it was the face being read in, the setting goes back to sans rather than being left
 * pointing at a file that is gone. Other books set in it are not rewritten — they fall back to
 * sans when rendered, which is what has always happened to a font that vanished, and rewriting
 * every book's settings to chase one deleted file would be a worse trade.
 */
internal fun ReaderViewModel.deleteReaderFont(path: String) {
    viewModelScope.launch {
        val name = _readerFonts.value.firstOrNull { it.path == path }?.name
        if (!repository.deleteReaderFont(path)) {
            _status.value = "Couldn’t remove that font."
            return@launch
        }
        _readerFonts.value = repository.readerFonts()
        if (settings.value.customFontPath == path) {
            updateSettings {
                it.copy(
                    customFontPath = null,
                    typeface = if (it.typeface == ReaderTypeface.CUSTOM) ReaderTypeface.SANS else it.typeface
                )
            }
        }
        _status.value = name?.let { "Removed $it." } ?: "Font removed."
    }
}

internal fun ReaderViewModel.setKeepAwake(on: Boolean) = updateSettings { it.copy(keepAwake = on) }

/**
 * A volume key was pressed while reading. Returns true when the reader consumed it — so the
 * system's volume UI stays out of the way when the keys are turning pages, and behaves exactly
 * as normal when they are not.
 */
internal fun ReaderViewModel.onVolumeKey(volumeUp: Boolean): Boolean {
    val action = VolumeKeys.action(volumeUp, settings.value)
    if (action == VolumeKeys.Action.IGNORE) return false
    pendingPageTurn = action
    _pageTurns.value = _pageTurns.value + 1
    return true
}

/**
 * Take the pending turn, if any.
 *
 * The reading surface acts on it rather than the ViewModel, because only the surface knows where
 * the page boundaries are — the paginator's page starts live in the composition that measured
 * them.
 */
internal fun ReaderViewModel.consumePageTurn(): VolumeKeys.Action? = pendingPageTurn.also { pendingPageTurn = null }
