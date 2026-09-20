package com.utilities.app.keyboard

import android.content.Context
import android.graphics.Typeface
import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.utilities.app.data.LexiconStore
import com.utilities.app.data.LookStore
import com.utilities.app.keyboard.logic.Key
import com.utilities.app.keyboard.logic.KeyAction
import com.utilities.app.keyboard.logic.KeyEffect
import com.utilities.app.keyboard.logic.KeyLayouts
import com.utilities.app.keyboard.logic.KeyboardFit
import com.utilities.app.keyboard.logic.KeyboardLook
import com.utilities.app.keyboard.logic.KeyboardMachine
import com.utilities.app.keyboard.logic.KeyboardState
import com.utilities.app.keyboard.logic.Layer
import com.utilities.app.keyboard.logic.Lexicon
import com.utilities.app.look.UtilityPalette
import com.utilities.app.look.UtilityPalettes
import com.utilities.app.look.UtilityTypeface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

/**
 * The keyboard.
 *
 * ## The promise
 *
 * This module declares no `INTERNET` permission and has no HTTP client on its classpath. That is
 * the whole of the privacy claim and it is worth being precise about what it does and does not
 * mean:
 *
 *  - **it cannot send what it sees anywhere.** Not to a server, not to a crash reporter, not to a
 *    "personalisation" endpoint. There is no route off the device from this code;
 *  - **what it remembers is a word list**, kept in a text file the household can read, capped at a
 *    few thousand words, with no sentences and no sequence in it — see
 *    [com.utilities.app.keyboard.logic.Lexicon];
 *  - **it remembers nothing from the fields that matter.** A password field, a field marked
 *    `noSuggestions`, and any editor that sets `IME_FLAG_NO_PERSONALIZED_LEARNING` — which is what
 *    a browser's private window does — are typed into and forgotten. That check is [learnable], and
 *    it is applied before a word reaches the store rather than inside it;
 *  - **it is not the only keyboard on the phone.** A long press on space hands back to whatever was
 *    there before, always. A keyboard people cannot get out of is one they will not switch to.
 *
 * It is worth noting what *is* still true of any keyboard, including this one, because a promise
 * that overreaches is worse than none: an input method sees everything typed while it is selected.
 * The argument here is not that it doesn't — it is that this one is running in a process with no
 * network permission, storing a word list in a file you can open.
 *
 * ## What it is made of
 *
 * Three pieces, and only the last of them is Android. [KeyLayouts] says what is on each page,
 * [KeyboardMachine] says what a press does to the keyboard, and this class translates the result
 * into `InputConnection` calls. The view is [KeyboardCanvas], which decides nothing.
 */
class UtilitiesKeyboardService : InputMethodService() {

    private lateinit var looks: LookStore
    private lateinit var lexicon: LexiconStore

    private var root: LinearLayout? = null
    private var keys: KeyboardCanvas? = null
    private var strip: SuggestionStrip? = null

    private var state = KeyboardState()
    private var look = KeyboardLook()
    private var palette: UtilityPalette = UtilityPalettes.resolve(look.look, LIGHT_SURFACE, LIGHT_TEXT)
    private var typeface: Typeface? = null

    private var lastShiftTap = 0L

    /** What the editor being typed into asks for. Re-read on every [onStartInputView]. */
    private var editorLayer = Layer.LETTERS
    private var editorAllowsLearning = true
    private var editorAllowsSuggestions = true
    private var enterLabel = "return"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watching: Job? = null

    override fun onCreate() {
        super.onCreate()
        looks = LookStore.get(this)
        lexicon = LexiconStore.get(this)
    }

    /**
     * Build the view once and keep it.
     *
     * The system asks for this again after a configuration change and whenever
     * [setInputView] is called; everything below repaints in place rather than being rebuilt, so a
     * colour changed on the settings screen reaches a keyboard that is already on screen over
     * another app. That is why the look is collected rather than read — see [LookStore].
     */
    override fun onCreateInputView(): View {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val suggestions = SuggestionStrip(this) { word -> complete(word) }
        val canvas = KeyboardCanvas(this) { key, long -> press(key, long) }

        column.addView(
            suggestions,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )
        column.addView(
            canvas,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )

        root = column
        strip = suggestions
        keys = canvas

        fitToSystemBars(column)

        watching?.cancel()
        watching = scope.launch {
            looks.keyboard.collectLatest { next ->
                look = next.sanitized()
                typeface = loadTypeface(look)
                repaint()
                refreshSuggestions()
            }
        }
        return column
    }

    /**
     * Keep the keys off the navigation bar.
     *
     * The input method's window reaches the bottom edge of the screen — it is laid out behind the
     * system bars, and nothing pads it on the keyboard's behalf. Left alone, the bottom row lands in
     * the strip the phone keeps for the gesture handle and the keyboard-switch button: the system
     * draws its glyphs over the caps, and a press near the bottom of the space bar is the gesture's
     * rather than the keyboard's. The keyboard reads as sitting too low because it is.
     *
     * So the window's insets become padding on the column, which pushes every row up by exactly the
     * height of the furniture below it and leaves the surface colour showing through underneath —
     * the same shape every other keyboard on the phone has. Padding rather than a fixed margin
     * because the height is the phone's to say: it differs between gesture and three-button
     * navigation, and it changes when somebody switches between them with the keyboard open.
     *
     * The insets are consumed rather than passed on. The two children below are a canvas and a
     * strip that lay themselves out edge to edge inside whatever they are given; there is nothing
     * further down to inset. What the arithmetic is, and why it takes the larger of two answers, is
     * [KeyboardFit].
     */
    private fun fitToSystemBars(column: LinearLayout) {
        ViewCompat.setOnApplyWindowInsetsListener(column) { view, insets ->
            val fit = KeyboardFit.padding(
                navigation = insets.edges(WindowInsetsCompat.Type.navigationBars()),
                cutout = insets.edges(WindowInsetsCompat.Type.displayCutout()),
                gestures = insets.edges(WindowInsetsCompat.Type.mandatorySystemGestures())
            )
            view.setPadding(fit.left, fit.top, fit.right, fit.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    private fun WindowInsetsCompat.edges(type: Int): KeyboardFit.Edges {
        val insets = getInsets(type)
        return KeyboardFit.Edges(insets.left, insets.top, insets.right, insets.bottom)
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        readEditor(info)
        state = KeyboardState(layer = editorLayer)
        syncShiftToCursor()
        repaint()
        refreshSuggestions()
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        syncShiftToCursor()
        repaint()
        refreshSuggestions()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        // A word half-typed when the field loses focus is still a word that was typed.
        flushWord()
    }

    override fun onDestroy() {
        watching?.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------------------------------
    // Pressing keys
    // ---------------------------------------------------------------------------------------

    private fun press(key: Key, longPress: Boolean) {
        val now = System.currentTimeMillis()
        val (next, effect) = KeyboardMachine.press(
            state = state,
            key = key,
            longPress = longPress,
            lastShiftTapMs = lastShiftTap,
            nowMs = now
        )
        if (key.action == KeyAction.SHIFT) lastShiftTap = now
        state = next

        when (effect) {
            is KeyEffect.Type -> type(effect.text)
            KeyEffect.Delete -> delete()
            KeyEffect.Commit -> commit()
            KeyEffect.SwitchKeyboard -> switchKeyboard()
            KeyEffect.None -> Unit
        }
        repaint()
        refreshSuggestions()
    }

    private fun type(text: String) {
        val connection = currentInputConnection ?: return
        // A word ends when something that is not part of one is typed. Learning happens here rather
        // than on the space key alone, because a sentence ending in a full stop is a word too.
        if (text.isNotEmpty() && !text[0].isLetter()) flushWord()
        connection.commitText(text, 1)
    }

    private fun delete() {
        val connection = currentInputConnection ?: return
        // A selection is deleted whole, which is what every other keyboard does and what somebody
        // who just selected a paragraph means. `deleteSurroundingText` would leave it there and take
        // a character off the end of it instead.
        val selected = connection.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            connection.commitText("", 1)
        } else {
            connection.deleteSurroundingText(1, 0)
        }
    }

    private fun commit() {
        flushWord()
        val connection = currentInputConnection ?: return
        val options = currentInputEditorInfo?.imeOptions ?: 0
        val action = options and EditorInfo.IME_MASK_ACTION
        val noAction = (options and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED && !noAction) {
            connection.performEditorAction(action)
        } else {
            connection.commitText("\n", 1)
        }
    }

    /** Replace the part-typed word with the suggestion, and a space after it. */
    private fun complete(word: String) {
        val connection = currentInputConnection ?: return
        val partial = Lexicon.wordBeforeCursor(textBeforeCursor())
        if (partial.isNotEmpty()) connection.deleteSurroundingText(partial.length, 0)
        // Picking a suggestion is typing that word, so it counts as having typed it.
        learn(word)
        connection.commitText("$word ", 1)
        state = KeyboardMachine.released(state)
        repaint()
        refreshSuggestions()
    }

    /**
     * Hand back to the phone's other keyboard.
     *
     * `switchToNextInputMethod` is the polite version and is what the long press on space should do;
     * when there is nowhere to go — a phone with exactly one other keyboard that has been removed —
     * the picker is shown instead, so the gesture never does nothing.
     */
    private fun switchKeyboard() {
        if (!switchToNextInputMethod(false)) {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.showInputMethodPicker()
        }
    }

    // ---------------------------------------------------------------------------------------
    // Learning and suggesting
    // ---------------------------------------------------------------------------------------

    /** Learn the word that was just finished, if this editor is one words may be learned from. */
    private fun flushWord() {
        if (!learnable()) return
        learn(Lexicon.wordBeforeCursor(textBeforeCursor()))
    }

    private fun learn(word: String) {
        if (!learnable() || word.isEmpty()) return
        lexicon.learn(word)
    }

    /**
     * Whether anything typed into this editor may be remembered.
     *
     * Three refusals, all of them the editor's own statement about itself rather than a guess:
     * a password variation of any input type, `TYPE_TEXT_FLAG_NO_SUGGESTIONS`, and the incognito
     * flag a browser sets on a private-window field. The household's own switch is checked too, and
     * checked last, because it is the only one of the four that is a preference.
     */
    private fun learnable(): Boolean = look.learn && editorAllowsLearning

    private fun refreshSuggestions() {
        val bar = strip ?: return
        val show = look.suggestions && editorAllowsSuggestions
        val words = if (!show) emptyList() else {
            val partial = Lexicon.wordBeforeCursor(textBeforeCursor())
            if (partial.isEmpty()) emptyList() else lexicon.lexicon.value.suggest(partial, SuggestionStrip.SLOTS)
        }
        bar.show(words, palette, look.look.textScale, typeface)
        bar.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun textBeforeCursor(): String =
        currentInputConnection?.getTextBeforeCursor(LOOKBACK, 0)?.toString().orEmpty()

    private fun syncShiftToCursor() {
        state = KeyboardMachine.autoShift(state, textBeforeCursor(), look.autoCapitalize)
    }

    // ---------------------------------------------------------------------------------------
    // The editor, and drawing
    // ---------------------------------------------------------------------------------------

    /**
     * What the field being typed into has asked for.
     *
     * A numeric field gets the numeric pad — the single most irritating thing a keyboard does is
     * offer the letters to somebody entering a phone number — and the enter key is labelled with
     * whatever the editor's action actually is, so "search" says search.
     */
    private fun readEditor(info: EditorInfo?) {
        val type = info?.inputType ?: InputType.TYPE_NULL
        val klass = type and InputType.TYPE_MASK_CLASS
        val variation = type and InputType.TYPE_MASK_VARIATION

        editorLayer = when (klass) {
            InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE -> Layer.NUMBERS
            else -> Layer.LETTERS
        }

        val password = (klass == InputType.TYPE_CLASS_TEXT && variation in PASSWORD_VARIATIONS) ||
            (klass == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        val noSuggestions = (type and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0
        val incognito = ((info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0

        editorAllowsLearning = !password && !noSuggestions && !incognito
        editorAllowsSuggestions = !password && !noSuggestions && editorLayer == Layer.LETTERS

        enterLabel = when ((info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_SEARCH -> "search"
            EditorInfo.IME_ACTION_SEND -> "send"
            EditorInfo.IME_ACTION_GO -> "go"
            EditorInfo.IME_ACTION_NEXT -> "next"
            EditorInfo.IME_ACTION_DONE -> "done"
            else -> "return"
        }
    }

    private fun repaint() {
        palette = UtilityPalettes.resolve(
            look = look.look,
            fallbackSurface = if (systemIsDark()) DARK_SURFACE else LIGHT_SURFACE,
            fallbackText = if (systemIsDark()) DARK_TEXT else LIGHT_TEXT
        )
        val layout = KeyLayouts.of(
            layer = state.layer,
            numberRow = look.numberRow,
            enterLabel = enterLabel
        )
        keys?.setTypeface(typeface)
        keys?.show(layout, look, palette, state.shift)
        root?.setBackgroundColor(palette.surface)
    }

    /**
     * Whether the phone is in dark mode — the fallback the [com.utilities.app.look.UtilityTheme.SYSTEM]
     * look resolves against. The keyboard cannot read the suite's Compose theme: it is drawn in a
     * window that has no activity and therefore no `MaterialTheme` in scope.
     */
    private fun systemIsDark(): Boolean =
        (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    /** The household's own face, if they supplied one and the file is still there. */
    private fun loadTypeface(look: KeyboardLook): Typeface? = when (look.look.typeface) {
        UtilityTypeface.SANS -> Typeface.SANS_SERIF
        UtilityTypeface.SERIF -> Typeface.SERIF
        UtilityTypeface.MONO -> Typeface.MONOSPACE
        UtilityTypeface.CUSTOM -> look.look.fontPath
            ?.let { path -> runCatching { Typeface.createFromFile(File(path)) }.getOrNull() }
            ?: Typeface.SANS_SERIF
    }

    private companion object {
        /** How much of the field to read back. Enough for a word and the sentence it is starting. */
        const val LOOKBACK = 64

        const val LIGHT_SURFACE = 0xFFEDEEF2.toInt()
        const val LIGHT_TEXT = 0xFF1A1C1E.toInt()
        const val DARK_SURFACE = 0xFF1B1D21.toInt()
        const val DARK_TEXT = 0xFFE3E5E8.toInt()

        val PASSWORD_VARIATIONS = setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        )
    }
}
