package com.utilities.app.keyboard.logic

/**
 * What a key is, and what the keyboard is made of.
 *
 * Deliberately data rather than views. A keyboard's bugs are nearly all in the *arrangement* — a row
 * whose widths do not add up, a layer with no way back out of it, a shift key that leaves the letters
 * lowercase — and every one of those is decidable from this description without drawing anything.
 * The drawing (`keyboard/KeyboardCanvas`) does no thinking: it is handed rows of keys with widths
 * that sum to one and paints them.
 */
data class Key(
    /** What pressing it types. Null for a key that does something instead — see [action]. */
    val output: String? = null,
    /** What is written on the cap. Defaults to what it types. */
    val label: String = output.orEmpty(),
    /**
     * What a long press types, drawn small in the cap's corner.
     *
     * A popup row of alternates is the usual answer and is deliberately not what this does. A popup
     * is a second surface to lay out, a second thing to dismiss and a gesture people discover by
     * accident; printing the alternate on the cap makes it discoverable by *reading*, and the long
     * press then does one predictable thing. The accented vowels a Latin keyboard needs fit in one
     * alternate each; a language that needs three would need the popup, and would be the reason to
     * build it.
     */
    val alternate: String? = null,
    /** A key that does something to the keyboard or the editor rather than typing. */
    val action: KeyAction? = null,
    /** How wide, relative to a plain letter key. */
    val weight: Float = 1f,
    /** Repeat while held — backspace and the arrows, and nothing else. */
    val repeats: Boolean = false
) {
    /** The cap's text for a given shift state. Only letters change; punctuation does not shout. */
    fun cap(shift: ShiftState): String =
        if (shift == ShiftState.OFF || action != null) label else label.uppercase()

    /** What it actually types for a given shift state. */
    fun typed(shift: ShiftState): String? =
        output?.let { if (shift == ShiftState.OFF) it else it.uppercase() }
}

/** The keys that are not letters. */
enum class KeyAction {
    SHIFT,
    BACKSPACE,
    SPACE,
    ENTER,

    /** To the symbols layer, and back to the letters. Two actions because the caps differ. */
    TO_SYMBOLS,
    TO_LETTERS,

    /** The second symbols page — brackets, maths, the currency signs. */
    TO_MORE,

    /**
     * Hand back to whatever keyboard the phone was using before.
     *
     * A keyboard that cannot be escaped from is a keyboard nobody dares select. This is the one
     * every IME is expected to offer and the reason the long press on space is spent on it.
     */
    SWITCH_KEYBOARD
}

/** A row of keys. Widths are relative; the canvas normalises them. */
data class KeyRow(val keys: List<Key>)

/** One page of keys. */
data class KeyboardLayout(val rows: List<KeyRow>)

/** Which page is showing. */
enum class Layer { LETTERS, SYMBOLS, MORE, NUMBERS }

/** Off, held for one letter, or locked on by a double tap. */
enum class ShiftState { OFF, ON, LOCKED }

/**
 * The layouts themselves.
 *
 * QWERTY, because a keyboard people cannot touch-type on is a keyboard they will not keep, however
 * much better Dvorak is. The number row is optional and off by default: it costs a fifth of the
 * screen to save one tap on a key most people press rarely, and the households that want it want it
 * badly enough to find a switch.
 */
object KeyLayouts {

    /** The page for [layer], with or without a number row across the top. */
    fun of(layer: Layer, numberRow: Boolean = false, enterLabel: String = "return"): KeyboardLayout {
        val rows = when (layer) {
            Layer.LETTERS -> letters(enterLabel)
            Layer.SYMBOLS -> symbols(enterLabel)
            Layer.MORE -> more(enterLabel)
            Layer.NUMBERS -> return KeyboardLayout(numbers(enterLabel))
        }
        return KeyboardLayout(if (numberRow && layer == Layer.LETTERS) listOf(digits()) + rows else rows)
    }

    /** The digits, as a row across the top. Their alternates are the symbols above them on a laptop. */
    private fun digits() = KeyRow(
        listOf(
            Key("1", alternate = "!"), Key("2", alternate = "@"), Key("3", alternate = "#"),
            Key("4", alternate = "$"), Key("5", alternate = "%"), Key("6", alternate = "^"),
            Key("7", alternate = "&"), Key("8", alternate = "*"), Key("9", alternate = "("),
            Key("0", alternate = ")")
        )
    )

    private fun letters(enterLabel: String) = listOf(
        KeyRow(
            listOf(
                Key("q", alternate = "1"), Key("w", alternate = "2"), Key("e", alternate = "é"),
                Key("r", alternate = "4"), Key("t", alternate = "5"), Key("y", alternate = "6"),
                Key("u", alternate = "ü"), Key("i", alternate = "í"), Key("o", alternate = "ó"),
                Key("p", alternate = "0")
            )
        ),
        KeyRow(
            listOf(
                Key("a", alternate = "á"), Key("s", alternate = "ß"), Key("d"), Key("f"), Key("g"),
                Key("h"), Key("j"), Key("k"), Key("l")
            )
        ),
        KeyRow(
            listOf(
                Key(label = "⇧", action = KeyAction.SHIFT, weight = 1.5f),
                Key("z"), Key("x"), Key("c", alternate = "ç"), Key("v"), Key("b"),
                Key("n", alternate = "ñ"), Key("m"),
                Key(label = "⌫", action = KeyAction.BACKSPACE, weight = 1.5f, repeats = true)
            )
        ),
        bottomRow(toggleLabel = "?123", toggle = KeyAction.TO_SYMBOLS, enterLabel = enterLabel)
    )

    private fun symbols(enterLabel: String) = listOf(
        KeyRow(
            listOf(
                Key("1"), Key("2"), Key("3"), Key("4"), Key("5"),
                Key("6"), Key("7"), Key("8"), Key("9"), Key("0")
            )
        ),
        KeyRow(
            listOf(
                Key("-", alternate = "_"), Key("/", alternate = "\\"), Key(":"), Key(";"),
                Key("(", alternate = "["), Key(")", alternate = "]"), Key("$", alternate = "€"),
                Key("&"), Key("@", alternate = "©"), Key("\"", alternate = "“")
            )
        ),
        KeyRow(
            listOf(
                Key(label = "=\\<", action = KeyAction.TO_MORE, weight = 1.5f),
                Key("."), Key(","), Key("?"), Key("!"), Key("'", alternate = "’"),
                Key("*"), Key("#"),
                Key(label = "⌫", action = KeyAction.BACKSPACE, weight = 1.5f, repeats = true)
            )
        ),
        bottomRow(toggleLabel = "ABC", toggle = KeyAction.TO_LETTERS, enterLabel = enterLabel)
    )

    private fun more(enterLabel: String) = listOf(
        KeyRow(
            listOf(
                Key("~"), Key("`"), Key("|"), Key("•"), Key("√"),
                Key("π"), Key("÷"), Key("×"), Key("¶"), Key("∆")
            )
        ),
        KeyRow(
            listOf(
                Key("£"), Key("¢"), Key("€"), Key("¥"), Key("^"),
                Key("°"), Key("="), Key("{"), Key("}"), Key("\\")
            )
        ),
        KeyRow(
            listOf(
                Key(label = "?123", action = KeyAction.TO_SYMBOLS, weight = 1.5f),
                Key("%"), Key("©"), Key("®"), Key("™"), Key("✓"),
                Key("["), Key("]"),
                Key(label = "⌫", action = KeyAction.BACKSPACE, weight = 1.5f, repeats = true)
            )
        ),
        bottomRow(toggleLabel = "ABC", toggle = KeyAction.TO_LETTERS, enterLabel = enterLabel)
    )

    /**
     * The numeric pad, for a field that only takes numbers.
     *
     * Its own layout rather than the symbols page, because an editor that asked for a phone number
     * and got the letters is the single most irritating thing a keyboard does. There is no letters
     * key on it at all: there is nothing to go back to.
     */
    private fun numbers(enterLabel: String) = listOf(
        KeyRow(listOf(Key("1"), Key("2"), Key("3"))),
        KeyRow(listOf(Key("4"), Key("5"), Key("6"))),
        KeyRow(listOf(Key("7"), Key("8"), Key("9"))),
        KeyRow(
            listOf(
                Key(label = "⌫", action = KeyAction.BACKSPACE, repeats = true),
                Key("0", alternate = "+"),
                Key(label = enterLabel, action = KeyAction.ENTER)
            )
        )
    )

    /**
     * The row every page shares: layer toggle, comma, space, full stop, enter.
     *
     * Space carries the keyboard switcher on a long press — see [KeyAction.SWITCH_KEYBOARD] — and
     * the comma and full stop are here rather than only on the symbols page because a keyboard that
     * makes you change layer to end a sentence is a keyboard that gets uninstalled on day one.
     */
    private fun bottomRow(toggleLabel: String, toggle: KeyAction, enterLabel: String) = KeyRow(
        listOf(
            Key(label = toggleLabel, action = toggle, weight = 1.5f),
            Key(",", alternate = ";"),
            Key(
                label = "space",
                action = KeyAction.SPACE,
                weight = 4f,
                alternate = null
            ),
            Key(".", alternate = ":"),
            Key(label = enterLabel, action = KeyAction.ENTER, weight = 2f)
        )
    )
}
