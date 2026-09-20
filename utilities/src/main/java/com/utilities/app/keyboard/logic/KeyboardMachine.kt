package com.utilities.app.keyboard.logic

/**
 * What the keyboard is showing, between presses.
 *
 * Two fields, and every awkward keyboard behaviour anybody has ever complained about is a rule
 * about how they change: shift that sticks when it should not, a symbols page you cannot get out
 * of, a sentence that starts lowercase after a full stop. They are rules rather than reflexes
 * scattered through a touch handler, which is the entire reason this file exists.
 */
data class KeyboardState(
    val layer: Layer = Layer.LETTERS,
    val shift: ShiftState = ShiftState.OFF
)

/** What a press asks the editor to do. */
sealed interface KeyEffect {
    /** Type this. Already shifted. */
    data class Type(val text: String) : KeyEffect

    /** Delete backwards — one character, or the selection if there is one. */
    data object Delete : KeyEffect

    /** The editor's own action: search, send, next field, or a newline where there is none. */
    data object Commit : KeyEffect

    /** Hand back to the phone's other keyboard. */
    data object SwitchKeyboard : KeyEffect

    /** The press changed the keyboard and asked the editor for nothing. */
    data object None : KeyEffect
}

/**
 * The rules.
 *
 * All of it is a function from (state, key, how it was pressed) to (state, effect), which is what
 * makes a keyboard testable at all: the interesting cases — double-tapping shift, long-pressing a
 * vowel while shift is held, leaving the symbols page by typing rather than by tapping ABC — are
 * three-line assertions here rather than something somebody has to sit and try on a phone.
 */
object KeyboardMachine {

    /** How long two shift taps may be apart and still mean caps lock. */
    const val SHIFT_LOCK_WINDOW_MS = 400L

    /**
     * Press [key].
     *
     * [longPress] is the held version. [lastShiftTapMs] and [nowMs] are only consulted for the shift
     * key and are how a double tap becomes a lock without this object holding a clock.
     */
    fun press(
        state: KeyboardState,
        key: Key,
        longPress: Boolean = false,
        lastShiftTapMs: Long = 0L,
        nowMs: Long = 0L
    ): Pair<KeyboardState, KeyEffect> {
        when (key.action) {
            KeyAction.SHIFT -> return shift(state, lastShiftTapMs, nowMs) to KeyEffect.None
            KeyAction.BACKSPACE -> return state to KeyEffect.Delete
            KeyAction.ENTER -> return released(state) to KeyEffect.Commit
            KeyAction.TO_SYMBOLS -> return state.copy(layer = Layer.SYMBOLS) to KeyEffect.None
            KeyAction.TO_MORE -> return state.copy(layer = Layer.MORE) to KeyEffect.None
            KeyAction.TO_LETTERS -> return state.copy(layer = Layer.LETTERS) to KeyEffect.None
            KeyAction.SWITCH_KEYBOARD -> return state to KeyEffect.SwitchKeyboard

            KeyAction.SPACE ->
                // The switcher is here because space is the only key big enough to hold a second
                // meaning without anybody hitting it by mistake.
                return if (longPress) state to KeyEffect.SwitchKeyboard
                else released(state) to KeyEffect.Type(" ")

            null -> Unit
        }

        // A long press types the alternate, and types it *unshifted* — somebody holding `a` for `á`
        // with shift on wants á, not Á. Shift is spent either way, because the press was a press.
        if (longPress && key.alternate != null) {
            return released(state) to KeyEffect.Type(key.alternate)
        }

        val typed = key.typed(state.shift) ?: return state to KeyEffect.None
        return released(state) to KeyEffect.Type(typed)
    }

    /**
     * Tap shift: off → on → off, and on again inside [SHIFT_LOCK_WINDOW_MS] → locked.
     *
     * Tapping a locked shift unlocks it all the way to off rather than back to held, which is what
     * every keyboard does and what anybody who has just typed a word in capitals means.
     */
    fun shift(state: KeyboardState, lastTapMs: Long, nowMs: Long): KeyboardState {
        val doubleTapped = lastTapMs > 0L && nowMs - lastTapMs in 0..SHIFT_LOCK_WINDOW_MS
        val next = when {
            state.shift == ShiftState.LOCKED -> ShiftState.OFF
            state.shift == ShiftState.ON && doubleTapped -> ShiftState.LOCKED
            state.shift == ShiftState.ON -> ShiftState.OFF
            else -> ShiftState.ON
        }
        return state.copy(shift = next)
    }

    /**
     * After a key has been typed: a held shift is spent, a locked one is not.
     *
     * The symbols page is *not* left after one character, which is the opposite of what several
     * keyboards do and is a deliberate disagreement with them: somebody who went to the symbols
     * page to type `£` very often wants `£30.00`, and being thrown back to the letters after the
     * pound sign means two more taps every time.
     */
    fun released(state: KeyboardState): KeyboardState =
        if (state.shift == ShiftState.ON) state.copy(shift = ShiftState.OFF) else state

    /**
     * What shift should be, given the text immediately before the cursor.
     *
     * Called when the editor's contents change under the keyboard rather than because of it — a
     * field being focused, a cursor being moved, text being pasted. Capitalising after a sentence
     * ending is the behaviour people expect without being able to name it; capitalising an *empty*
     * field is the same rule applied to the start of the first sentence.
     *
     * A locked shift is left alone: somebody typing in capitals did not ask for help.
     */
    fun autoShift(state: KeyboardState, textBefore: String, enabled: Boolean = true): KeyboardState {
        if (!enabled || state.shift == ShiftState.LOCKED) return state
        val wanted = startsASentence(textBefore)
        return state.copy(shift = if (wanted) ShiftState.ON else ShiftState.OFF)
    }

    /**
     * Whether the cursor is at the start of a sentence.
     *
     * Only spaces and tabs are trimmed, so a trailing newline survives to be looked at. That
     * matters: a newline is a boundary *in itself* — a new line is a new sentence, and unlike a full
     * stop there is no "still deciding" moment after one.
     *
     * After a full stop there is. `Hi.` with the cursor hard against the stop is somebody mid-thought
     * and capitalising nothing yet is free; `Hi. ` is a new sentence.
     */
    fun startsASentence(textBefore: String): Boolean {
        val trimmed = textBefore.trimEnd(' ', '\t')
        if (trimmed.isEmpty()) return true
        if (trimmed.last() == '\n') return true
        if (trimmed.length == textBefore.length) return false
        return trimmed.last() in SENTENCE_ENDINGS
    }

    /** A newline is handled above, because trimming would otherwise hide it. */
    private val SENTENCE_ENDINGS = charArrayOf('.', '!', '?')
}
