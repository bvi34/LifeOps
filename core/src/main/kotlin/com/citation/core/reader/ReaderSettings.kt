package com.citation.core.reader

/**
 * Everything about how a book is set on the page and how the screen behaves while you read it.
 *
 * Gathered into one value rather than a dozen loose flags for three reasons: it can be persisted in
 * one place (the reader's settings used to vanish on every app restart), a book can be given its own
 * copy, and the decisions that have real logic in them — what warmth does to a colour, which volume
 * key turns which way — become testable without a device.
 */
data class ReaderSettings(
    // --- Type --------------------------------------------------------------------------------
    val fontSize: Float = 18f,
    val lineSpacing: Float = 1.6f,
    val marginDp: Float = 20f,
    /** Tracking, in em. Slightly positive helps some readers; negative is almost never wanted. */
    val letterSpacing: Float = 0f,
    val typeface: ReaderTypeface = ReaderTypeface.SERIF,
    /**
     * A font file the reader supplied themselves.
     *
     * Citation ships no font beyond the platform's own, because the faces people actually want here
     * — OpenDyslexic above all — are ones it has no right to redistribute. Letting you point at a
     * file you already have is both the honest answer and the more capable one: it covers dyslexia
     * faces, a preferred serif, a language-specific face, without Citation curating any of them.
     */
    val customFontPath: String? = null,

    // --- Setting -----------------------------------------------------------------------------
    /**
     * Justify the text.
     *
     * Off by default, and that is a considered choice rather than an oversight: justification
     * without good hyphenation opens rivers of whitespace on a narrow phone column. It is worth
     * having, and worth pairing with [hyphenate].
     */
    val justify: Boolean = false,
    val hyphenate: Boolean = true,
    val paragraphs: ParagraphSpacing = ParagraphSpacing.INDENT,

    // --- Colour ------------------------------------------------------------------------------
    val theme: ReaderTheme = ReaderTheme.SYSTEM,
    /** Pure black rather than near-black, so an OLED panel actually switches those pixels off. */
    val trueBlack: Boolean = false,
    /** 0 = untouched, 1 = strongly amber. Cuts blue light without dimming the panel. */
    val warmth: Float = 0f,
    /** In-reader screen brightness, 0..1; [SYSTEM_BRIGHTNESS] follows the device setting. */
    val brightness: Float = SYSTEM_BRIGHTNESS,

    // --- Behaviour ---------------------------------------------------------------------------
    val paged: Boolean = true,
    val keepAwake: Boolean = true,
    val volumeKeyTurns: Boolean = false,
    /** Swap which volume key goes forward, for readers who hold the phone the other way. */
    val volumeKeysReversed: Boolean = false,
    val orientation: ScreenOrientation = ScreenOrientation.FOLLOW_SYSTEM,
    /** Hide the status and navigation bars, leaving only the page. */
    val immersive: Boolean = false
) {

    /** Clamp every value into a range that can actually be rendered. */
    fun sanitized(): ReaderSettings = copy(
        fontSize = fontSize.coerceIn(10f, 36f),
        lineSpacing = lineSpacing.coerceIn(1.0f, 2.6f),
        marginDp = marginDp.coerceIn(0f, 72f),
        letterSpacing = letterSpacing.coerceIn(-0.05f, 0.4f),
        warmth = warmth.coerceIn(0f, 1f),
        brightness = if (brightness < 0f) SYSTEM_BRIGHTNESS else brightness.coerceIn(0.01f, 1f)
    )

    val followsSystemBrightness: Boolean get() = brightness < 0f

    companion object {
        const val SYSTEM_BRIGHTNESS = -1f
    }
}

/** The faces the reader can set text in. */
enum class ReaderTypeface(val label: String) {
    SERIF("Serif"),
    SANS("Sans"),
    MONO("Mono"),

    /**
     * A face the reader supplied, named by [ReaderSettings.customFontPath]. Falls back to sans when
     * the file has gone — a missing font must not make a book unopenable.
     */
    CUSTOM("Your font")
}

/**
 * How one paragraph is separated from the next.
 *
 * Both are correct; they belong to different traditions. [INDENT] is how printed prose has always
 * done it and is what makes a novel read like a novel. [SPACED] is the web's convention and is
 * easier on some readers, particularly at large type.
 */
enum class ParagraphSpacing(val label: String) {
    INDENT("Indented"),
    SPACED("Spaced")
}

/** Reading themes. [SYSTEM] follows the app's own light/dark colours. */
enum class ReaderTheme(val label: String) {
    SYSTEM("System"),
    PAPER("Paper"),
    SEPIA("Sepia"),
    NIGHT("Night")
}

enum class ScreenOrientation(val label: String) {
    FOLLOW_SYSTEM("Follow system"),
    PORTRAIT("Portrait"),
    LANDSCAPE("Landscape")
}

/**
 * The reader's colours, as plain ARGB.
 *
 * Kept in `:core` and free of any framework so the interesting part — what warmth actually does to
 * a colour — is a tested function rather than a magic overlay someone tuned by eye once.
 */
object ReaderPalette {

    /** Base background for a theme, or `null` for [ReaderTheme.SYSTEM] (the app's own colours). */
    fun background(theme: ReaderTheme, trueBlack: Boolean): Int? = when {
        theme == ReaderTheme.NIGHT && trueBlack -> BLACK
        theme == ReaderTheme.NIGHT -> NIGHT_BG
        theme == ReaderTheme.PAPER -> PAPER_BG
        theme == ReaderTheme.SEPIA -> SEPIA_BG
        else -> null
    }

    /** Base foreground for a theme, or `null` for [ReaderTheme.SYSTEM]. */
    fun foreground(theme: ReaderTheme, trueBlack: Boolean): Int? = when {
        theme == ReaderTheme.NIGHT && trueBlack -> TRUE_BLACK_FG
        theme == ReaderTheme.NIGHT -> NIGHT_FG
        theme == ReaderTheme.PAPER -> PAPER_FG
        theme == ReaderTheme.SEPIA -> SEPIA_FG
        else -> null
    }

    /**
     * Warm a colour by cutting its blue.
     *
     * This is what a night-shift filter does, and doing it to the *colours* rather than by laying a
     * translucent orange sheet over the page matters: an overlay dims everything it covers,
     * flattening contrast exactly when a reader has turned to warm colours because it is late and
     * their eyes are tired. Blue drops most, green a little, red not at all — so the page warms
     * while staying as legible as it was.
     */
    fun warm(argb: Int, warmth: Float): Int {
        val w = warmth.coerceIn(0f, 1f)
        if (w <= 0f) return argb
        val a = (argb ushr 24) and 0xFF
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        val warmedG = (g * (1f - GREEN_CUT * w)).toInt().coerceIn(0, 255)
        val warmedB = (b * (1f - BLUE_CUT * w)).toInt().coerceIn(0, 255)
        return (a shl 24) or (r shl 16) or (warmedG shl 8) or warmedB
    }

    /** Both of a theme's colours, warmed together so the page stays coherent. */
    fun of(settings: ReaderSettings): Pair<Int, Int>? {
        val bg = background(settings.theme, settings.trueBlack) ?: return null
        val fg = foreground(settings.theme, settings.trueBlack) ?: return null
        return warm(bg, settings.warmth) to warm(fg, settings.warmth)
    }

    private const val GREEN_CUT = 0.10f
    private const val BLUE_CUT = 0.45f

    const val PAPER_BG = 0xFFFBF7EF.toInt()
    const val PAPER_FG = 0xFF2B2B2B.toInt()
    const val SEPIA_BG = 0xFFF4ECD8.toInt()
    const val SEPIA_FG = 0xFF5B4636.toInt()
    const val NIGHT_BG = 0xFF121212.toInt()
    const val NIGHT_FG = 0xFFD7D7D2.toInt()
    const val BLACK = 0xFF000000.toInt()

    /**
     * Slightly dimmer than the ordinary night foreground. On a true-black background, full-strength
     * text is a harsh edge in a dark room — the contrast is already at its maximum.
     */
    const val TRUE_BLACK_FG = 0xFFC8C8C4.toInt()
}

/**
 * What the volume keys do while reading.
 *
 * The default is volume **down** for the next page: down is forward, the same direction the text
 * moves and the same way a scroll gesture reads. Readers who hold the phone the other way disagree
 * strongly enough that it is worth a switch rather than an argument.
 */
object VolumeKeys {

    enum class Action { NEXT_PAGE, PREVIOUS_PAGE, IGNORE }

    fun action(volumeUp: Boolean, settings: ReaderSettings): Action {
        if (!settings.volumeKeyTurns) return Action.IGNORE
        val forward = if (settings.volumeKeysReversed) volumeUp else !volumeUp
        return if (forward) Action.NEXT_PAGE else Action.PREVIOUS_PAGE
    }
}
