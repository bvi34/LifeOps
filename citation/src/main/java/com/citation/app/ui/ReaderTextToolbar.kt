package com.citation.app.ui

import android.content.ClipboardManager
import android.content.Context
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus

/**
 * The reader's own text-selection toolbar. It is the seam that makes selecting a passage a
 * *first-class* action: alongside the platform's Copy/Select-all it adds **Add note** and
 * **Highlight**, so the reader's answer to "I selected this, now what" is right there in the
 * contextual bar rather than a separate screen where you re-paste the quote.
 *
 * Compose deliberately does not hand the app the selected string (a [androidx.compose.foundation.text.selection.SelectionContainer]
 * keeps it private), so we extract it the one sanctioned way: trigger the platform copy the menu
 * already offers, then read the clipboard back. The passage is what both actions need, so putting it
 * on the clipboard is a benign side effect rather than a surprise.
 *
 * Everything else routes to the callbacks the framework supplies per [showMenu], so the standard
 * Copy/Paste/Cut/Select-all keep working exactly as the default toolbar would.
 *
 * [onAddNote]/[onHighlight] are `var`s reassigned every recomposition (the toolbar itself is
 * remembered across them), which keeps the captured lambdas from going stale without churning a new
 * toolbar — and therefore a new [ActionMode] — on each frame.
 */
class ReaderTextToolbar(private val view: View) : TextToolbar {

    /** Selected passage → open the note composer pre-filled with the quote. */
    var onAddNote: (String) -> Unit = {}

    /** Selected passage → capture a bare highlight (an annotatable note with no body yet). */
    var onHighlight: (String) -> Unit = {}

    private var actionMode: ActionMode? = null

    override var status: TextToolbarStatus = TextToolbarStatus.Hidden
        private set

    private var onCopyRequested: (() -> Unit)? = null
    private var onPasteRequested: (() -> Unit)? = null
    private var onCutRequested: (() -> Unit)? = null
    private var onSelectAllRequested: (() -> Unit)? = null

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?
    ) {
        this.onCopyRequested = onCopyRequested
        this.onPasteRequested = onPasteRequested
        this.onCutRequested = onCutRequested
        this.onSelectAllRequested = onSelectAllRequested

        val callback = Callback(rect)
        if (actionMode == null) {
            status = TextToolbarStatus.Shown
            actionMode = view.startActionMode(callback, ActionMode.TYPE_FLOATING)
        } else {
            actionMode?.invalidate()
        }
    }

    override fun hide() {
        status = TextToolbarStatus.Hidden
        actionMode?.finish()
        actionMode = null
    }

    /**
     * Read the current selection by borrowing the copy the menu already exposes. Returns "" if there
     * is nothing selected, so callers can no-op gracefully.
     */
    private fun selectedText(): String {
        val copy = onCopyRequested ?: return ""
        copy() // places the selection on the clipboard (synchronous on Android)
        val clipboard = view.context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = clipboard?.primaryClip ?: return ""
        if (clip.itemCount == 0) return ""
        return clip.getItemAt(0)?.coerceToText(view.context)?.toString().orEmpty()
    }

    private inner class Callback(private var rect: Rect) : ActionMode.Callback2() {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            // Reader actions first so they read as the primary intent of a selection, then the
            // platform verbs. Only add an item when its callback exists (i.e. it applies here).
            if (onCopyRequested != null) {
                menu.add(0, ITEM_NOTE, 0, "Add note")
                menu.add(0, ITEM_HIGHLIGHT, 1, "Highlight")
                menu.add(0, ITEM_COPY, 2, android.R.string.copy)
            }
            if (onPasteRequested != null) menu.add(0, ITEM_PASTE, 3, android.R.string.paste)
            if (onCutRequested != null) menu.add(0, ITEM_CUT, 4, android.R.string.cut)
            if (onSelectAllRequested != null) menu.add(0, ITEM_SELECT_ALL, 5, android.R.string.selectAll)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            when (item.itemId) {
                ITEM_NOTE -> {
                    val quote = selectedText()
                    mode.finish()
                    if (quote.isNotBlank()) onAddNote(quote)
                }
                ITEM_HIGHLIGHT -> {
                    val quote = selectedText()
                    mode.finish()
                    if (quote.isNotBlank()) onHighlight(quote)
                }
                ITEM_COPY -> { onCopyRequested?.invoke(); mode.finish() }
                ITEM_PASTE -> { onPasteRequested?.invoke(); mode.finish() }
                ITEM_CUT -> { onCutRequested?.invoke(); mode.finish() }
                ITEM_SELECT_ALL -> onSelectAllRequested?.invoke()
                else -> return false
            }
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            actionMode = null
            status = TextToolbarStatus.Hidden
        }

        override fun onGetContentRect(mode: ActionMode, view: View, outRect: android.graphics.Rect) {
            outRect.set(
                rect.left.toInt(),
                rect.top.toInt(),
                rect.right.toInt(),
                rect.bottom.toInt()
            )
        }
    }

    private companion object {
        const val ITEM_NOTE = 1
        const val ITEM_HIGHLIGHT = 2
        const val ITEM_COPY = 3
        const val ITEM_PASTE = 4
        const val ITEM_CUT = 5
        const val ITEM_SELECT_ALL = 6
    }
}
