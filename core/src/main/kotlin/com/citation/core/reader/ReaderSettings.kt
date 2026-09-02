package com.citation.core.reader

import com.citation.core.note.HighlightColor
import kotlin.math.pow
import kotlin.math.roundToInt

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
    /**
     * The page colour, as ARGB, when [theme] is [ReaderTheme.CUSTOM]; `null` falls back to
     * [ReaderPalette.CUSTOM_BG] until the reader picks one.
     *
     * Held even while another theme is selected, so switching to Sepia to compare and back again
     * does not throw away colours somebody sat and tuned.
     */
    val customBackground: Int? = null,
    /** The text colour, as ARGB, when [theme] is [ReaderTheme.CUSTOM]. See [customBackground]. */
    val customText: Int? = null,
    /**
     * The heading colour, as ARGB, when [theme] is [ReaderTheme.CUSTOM]; `null` sets headings in
     * the body's own colour.
     *
     * Its own setting because a heading is not simply large text. It is the one thing on the page a
     * reader navigates by, and readers who tint a page for visual stress routinely want the
     * structure to stand off it in a second colour — while a reader who wants nothing of the kind
     * leaves it alone and gets a heading that matches the prose, which is what a printed book does.
     */
    val customHeading: Int? = null,
    /**
     * The colour of links and note references, as ARGB, when [theme] is [ReaderTheme.CUSTOM];
     * `null` derives one that is legible on the chosen page.
     *
     * Separated out because it was the colour doing the most damage: links used to be painted in the
     * app's own accent, which is neither warmed with the page nor chosen by anybody reading, so a
     * book with anchors through its prose came out in app-purple over whatever colours the reader
     * had set.
     */
    val customLink: Int? = null,
    /** Pure black rather than near-black, so an OLED panel actually switches those pixels off. */
    val trueBlack: Boolean = false,
    /** 0 = untouched, 1 = strongly amber. Cuts blue light without dimming the panel. */
    val warmth: Float = 0f,
    /**
     * Carry these settings into the **read-in-place** readers (Kindle, O'Reilly) by styling their
     * pages — see [ReaderWebStyle].
     *
     * On by default, because settings that reach one of four reading tracks are barely settings at
     * all. A switch rather than a certainty, because those are other people's readers: they change
     * without notice, and the reader looking at a page Citation has made worse needs a way to stop
     * it that is faster than uninstalling.
     */
    val styleReadInPlace: Boolean = true,
    /**
     * The colour a new highlight is made in.
     *
     * A default rather than a prompt: capturing a passage has to stay one gesture. A reader who
     * files by colour changes it here when they change what they are looking for, and recolours the
     * odd one afterwards from the note itself.
     */
    val highlightColor: HighlightColor = HighlightColor.YELLOW,
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
        brightness = if (brightness < 0f) SYSTEM_BRIGHTNESS else brightness.coerceIn(0.01f, 1f),
        // A part-transparent page would let the app's own surface show through the book, which is
        // never what somebody choosing a colour means. Keep the hue, drop the transparency.
        customBackground = customBackground?.let { ReaderPalette.opaque(it) },
        customText = customText?.let { ReaderPalette.opaque(it) },
        customHeading = customHeading?.let { ReaderPalette.opaque(it) },
        customLink = customLink?.let { ReaderPalette.opaque(it) }
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
    NIGHT("Night"),

    /**
     * Page and text colours the reader chose themselves, held in
     * [ReaderSettings.customBackground] and [ReaderSettings.customText].
     *
     * The four presets cover the usual answers, but not everybody's: readers with Irlen syndrome or
     * a light sensitivity are routinely told a specific tint helps them, dyslexic readers are often
     * given one too, and neither is a colour anybody could guess in advance. Rather than adding a
     * preset per condition, the reader names the two colours and Citation gets out of the way.
     */
    CUSTOM("Custom")
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

    /**
     * Base background for a theme, or `null` for [ReaderTheme.SYSTEM] (the app's own colours).
     *
     * [custom] is the reader's own page colour and is consulted only under [ReaderTheme.CUSTOM] —
     * a colour they picked must not quietly repaint Sepia.
     */
    fun background(theme: ReaderTheme, trueBlack: Boolean, custom: Int? = null): Int? = when {
        theme == ReaderTheme.NIGHT && trueBlack -> BLACK
        theme == ReaderTheme.NIGHT -> NIGHT_BG
        theme == ReaderTheme.PAPER -> PAPER_BG
        theme == ReaderTheme.SEPIA -> SEPIA_BG
        theme == ReaderTheme.CUSTOM -> opaque(custom ?: CUSTOM_BG)
        else -> null
    }

    /** Base foreground for a theme, or `null` for [ReaderTheme.SYSTEM]. See [background]. */
    fun foreground(theme: ReaderTheme, trueBlack: Boolean, custom: Int? = null): Int? = when {
        theme == ReaderTheme.NIGHT && trueBlack -> TRUE_BLACK_FG
        theme == ReaderTheme.NIGHT -> NIGHT_FG
        theme == ReaderTheme.PAPER -> PAPER_FG
        theme == ReaderTheme.SEPIA -> SEPIA_FG
        theme == ReaderTheme.CUSTOM -> opaque(custom ?: CUSTOM_FG)
        else -> null
    }

    /**
     * The heading colour for a theme, or `null` when it simply follows the body text.
     *
     * Only [ReaderTheme.CUSTOM] answers with anything: the presets set headings in the prose colour
     * and let size and weight do the work, which is the printed convention and the one that never
     * goes wrong on a page somebody else tinted.
     */
    fun heading(theme: ReaderTheme, custom: Int? = null): Int? =
        if (theme == ReaderTheme.CUSTOM) custom?.let { opaque(it) } else null

    /** The link colour for a theme, or `null` to derive one against the page. See [readable]. */
    fun link(theme: ReaderTheme, custom: Int? = null): Int? =
        if (theme == ReaderTheme.CUSTOM) custom?.let { opaque(it) } else null

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

    /**
     * Every colour the page is drawn in, for these settings, or `null` under [ReaderTheme.SYSTEM] —
     * which means "the app's own colours" and is answered by the overload taking fallbacks.
     */
    fun colors(settings: ReaderSettings): ReaderColors? {
        val page = background(settings.theme, settings.trueBlack, settings.customBackground) ?: return null
        val text = foreground(settings.theme, settings.trueBlack, settings.customText) ?: return null
        return derive(page, text, settings)
    }

    /**
     * Every colour the page is drawn in, falling back to the colours the app itself is using where
     * the theme has nothing to say.
     *
     * This is the call the reader screen makes, and the reason it exists is that under
     * [ReaderTheme.SYSTEM] the page is the app's surface — but the *rest* of the palette still has
     * to be derived from it rather than pulled out of the app's Material scheme. A link painted in
     * the app's accent is a colour nobody reading chose, does not warm with the page, and is exactly
     * how a book ends up set in the launcher's purple.
     */
    fun colors(settings: ReaderSettings, fallbackPage: Int, fallbackText: Int): ReaderColors {
        val page = background(settings.theme, settings.trueBlack, settings.customBackground) ?: fallbackPage
        val text = foreground(settings.theme, settings.trueBlack, settings.customText) ?: fallbackText
        return derive(page, text, settings)
    }

    /**
     * Build the full palette from a page and a text colour.
     *
     * Everything is warmed together — including a colour the reader typed in — so the page stays one
     * coherent thing rather than warm prose with a cold heading sitting in it. The derived colours
     * are computed *after* warming, against the colours that will actually be on screen, so a link
     * or a caption cannot be legible in the settings sheet and unreadable on the page.
     */
    private fun derive(page: Int, text: Int, settings: ReaderSettings): ReaderColors {
        val warmedPage = warm(page, settings.warmth)
        val warmedText = warm(text, settings.warmth)
        val heading = heading(settings.theme, settings.customHeading)
            ?.let { warm(it, settings.warmth) }
            ?: warmedText
        val link = link(settings.theme, settings.customLink)
            ?.let { warm(it, settings.warmth) }
            ?: readable(LINK_TINT, warmedPage, warmedText)
        return ReaderColors(
            page = warmedPage,
            text = warmedText,
            heading = heading,
            link = link,
            secondary = mix(warmedPage, warmedText, SECONDARY_FADE)
        )
    }

    /**
     * [tint] if it can be read on [page], otherwise the nearest version of it that can — pulled
     * toward [fallback], the colour the page's own text is set in, until there is enough contrast.
     *
     * This is what lets a link keep being recognisably a link on a black page, a cream one and a
     * page somebody tinted deep green, without a table of per-theme accents that would still have
     * nothing to say about a colour the reader chose themselves. A tint that cannot be rescued gives
     * way to the text colour: a link the reader cannot read is worse than one they cannot spot.
     */
    fun readable(tint: Int, page: Int, fallback: Int): Int {
        if (contrast(tint, page) >= MIN_CONTRAST) return opaque(tint)
        READABLE_BLENDS.forEach { amount ->
            val shade = mix(fallback, tint, amount)
            if (contrast(shade, page) >= MIN_CONTRAST) return shade
        }
        return opaque(fallback)
    }

    /** Force a colour fully opaque, keeping its hue. */
    fun opaque(argb: Int): Int = argb or ALPHA

    /**
     * Parse `#RRGGBB`, `RRGGBB`, `#RGB` or `#AARRGGBB` into an opaque ARGB colour, or `null`.
     *
     * Typing a hex code is here because it is the only way to reach an *exact* colour: readers
     * arrive with one they were given — from an overlay they already own, or a value someone
     * recommended — and matching it by dragging a slider is guesswork.
     */
    fun parseHex(text: String): Int? {
        val hex = text.trim().removePrefix("#").removePrefix("0x").removePrefix("0X")
        if (hex.any { it.digitToIntOrNull(16) == null }) return null
        val rgb = when (hex.length) {
            3 -> hex.map { "$it$it" }.joinToString("")
            6 -> hex
            8 -> hex.substring(2)
            else -> return null
        }
        return opaque(rgb.toLong(16).toInt())
    }

    /** A colour as the `#RRGGBB` a reader would recognise, alpha dropped. */
    fun hex(argb: Int): String = "#%06X".format(argb and 0xFFFFFF)

    /**
     * WCAG relative luminance, 0 (black) to 1 (white).
     *
     * The sRGB channels are gamma-encoded, so averaging them would call a mid green as dark as a
     * mid blue — which is exactly the mistake that lets an unreadable pair of colours through.
     */
    fun luminance(argb: Int): Double {
        fun channel(value: Int): Double {
            val c = value / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        val r = channel((argb ushr 16) and 0xFF)
        val g = channel((argb ushr 8) and 0xFF)
        val b = channel(argb and 0xFF)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    /** WCAG contrast ratio between two colours, 1 (identical) to 21 (black on white). */
    fun contrast(a: Int, b: Int): Double {
        val one = luminance(a)
        val two = luminance(b)
        return (maxOf(one, two) + 0.05) / (minOf(one, two) + 0.05)
    }

    /**
     * Whether text of one colour can comfortably be read on the other.
     *
     * Checked rather than prevented: somebody who wants pale grey on cream for a reason of their own
     * is entitled to it, and the reader is the one looking at the page. But a pair that is genuinely
     * unreadable is nearly always a slip — a colour picked against the wrong swatch — and it is
     * worth saying so before they close the sheet and find the book has vanished.
     */
    fun isLegible(foreground: Int, background: Int): Boolean =
        contrast(foreground, background) >= MIN_CONTRAST

    /** Mix [tint] into [base] by [amount] (0 = all base, 1 = all tint). Always opaque. */
    fun mix(tint: Int, base: Int, amount: Float): Int {
        val t = amount.coerceIn(0f, 1f)
        fun channel(shift: Int): Int {
            val a = (tint ushr shift) and 0xFF
            val b = (base ushr shift) and 0xFF
            return (b + (a - b) * t).roundToInt().coerceIn(0, 255)
        }
        return opaque((channel(16) shl 16) or (channel(8) shl 8) or channel(0))
    }

    /**
     * The shade actually drawn behind a highlighted passage.
     *
     * A highlight cannot be a fixed colour, because it is not drawn on a fixed page. A translucent
     * yellow that reads as a highlighter on white becomes a muddy olive on a night page and a
     * near-invisible smear on a page somebody tinted themselves — and the reader has just been given
     * a colour picker, so "whatever the app's accent is at 28% alpha" stopped being an answer.
     *
     * So it is derived instead: [tint] is mixed into the [page] as strongly as the [text] on top can
     * still be read over, stepping back until the passage is comfortable to read rather than merely
     * marked. That way the mark is as visible as it can be on a white page *and* on a black one, and
     * a highlight can never render its own sentence unreadable — the failure that makes a reader
     * think their book is broken.
     *
     * The floor is deliberate: below it there is no visible mark at all, and a highlight nobody can
     * see is worse than a faint one.
     */
    fun highlight(tint: Int, page: Int, text: Int): Int {
        HIGHLIGHT_STRENGTHS.forEach { strength ->
            val shade = mix(tint, page, strength)
            if (contrast(text, shade) >= MIN_CONTRAST) return shade
        }
        return mix(tint, page, HIGHLIGHT_STRENGTHS.last())
    }

    /**
     * Blend strengths tried for a highlight, strongest first. The last is the floor: a highlight has
     * to be visible even where nothing keeps the text at full contrast.
     */
    private val HIGHLIGHT_STRENGTHS = listOf(0.60f, 0.50f, 0.40f, 0.32f, 0.25f, 0.18f)

    /** Blends tried by [readable], nearest the original tint first. */
    private val READABLE_BLENDS = listOf(0.2f, 0.4f, 0.6f, 0.8f)

    /**
     * How far a caption or a quote is faded toward the page. Enough to read as secondary, not enough
     * to stop being prose — these are the author's words too.
     */
    private const val SECONDARY_FADE = 0.32f

    /**
     * The link colour before it is fitted to the page: the blue that has meant "link" since the web
     * had links. It is a starting point, not a result — see [readable].
     */
    const val LINK_TINT = 0xFF1A5FB4.toInt()

    private const val GREEN_CUT = 0.10f
    private const val BLUE_CUT = 0.45f
    private const val ALPHA = 0xFF shl 24

    /** WCAG AA for body text. Below this, prose stops being comfortable rather than merely dim. */
    const val MIN_CONTRAST = 4.5

    const val PAPER_BG = 0xFFFBF7EF.toInt()
    const val PAPER_FG = 0xFF2B2B2B.toInt()
    const val SEPIA_BG = 0xFFF4ECD8.toInt()
    const val SEPIA_FG = 0xFF5B4636.toInt()
    const val NIGHT_BG = 0xFF121212.toInt()
    const val NIGHT_FG = 0xFFD7D7D2.toInt()
    const val BLACK = 0xFF000000.toInt()

    /** Where [ReaderTheme.CUSTOM] starts before the reader has chosen anything: plain paper. */
    const val CUSTOM_BG = PAPER_BG
    const val CUSTOM_FG = PAPER_FG

    /**
     * Slightly dimmer than the ordinary night foreground. On a true-black background, full-strength
     * text is a harsh edge in a dark room — the contrast is already at its maximum.
     */
    const val TRUE_BLACK_FG = 0xFFC8C8C4.toInt()

    /**
     * Page colours offered as a starting point — white through the tints readers are most often
     * given for glare and visual stress, then the dark end.
     *
     * A starting point is all they are: the hex field beside them is what makes an exact colour
     * reachable, and these only save the common cases a trip through it.
     */
    val PAGE_SWATCHES: List<Int> = listOf(
        0xFFFFFFFF.toInt(), PAPER_BG, SEPIA_BG, 0xFFFFF3C4.toInt(),
        0xFFEAF3E7.toInt(), 0xFFE4F0F6.toInt(), 0xFFEDE7F6.toInt(), 0xFFFBE9E7.toInt(),
        0xFF3A3A3A.toInt(), NIGHT_BG, BLACK
    )

    /** Text colours offered as a starting point, dark through to the light end for a dark page. */
    val TEXT_SWATCHES: List<Int> = listOf(
        BLACK, PAPER_FG, SEPIA_FG, 0xFF1A3A5C.toInt(),
        0xFF2E4A2E.toInt(), 0xFF5A2D4A.toInt(), 0xFF7A7A7A.toInt(),
        NIGHT_FG, 0xFFFFFFFF.toInt()
    )

    /**
     * Link colours offered as a starting point. Blues first, because that is what a link looks like,
     * then the alternatives that stay distinguishable for the commonest colour-vision deficiencies.
     */
    val LINK_SWATCHES: List<Int> = listOf(
        LINK_TINT, 0xFF0B7285.toInt(), 0xFF1F7A4D.toInt(), 0xFF8A5A00.toInt(),
        0xFF9A3412.toInt(), 0xFF7C3AED.toInt(), 0xFF6B7280.toInt(),
        0xFF7FB3FF.toInt(), 0xFF9ADCE8.toInt()
    )
}

/**
 * The colours a page is actually drawn in: the page itself, the prose, and the three roles that used
 * to be taken from whatever the app's Material scheme happened to hold.
 *
 * Gathered into one value because they are only correct *together* — a link is chosen against the
 * page it sits on and the text beside it, a caption is a fade of the prose toward the page, and all
 * of them are warmed as a set. Handing the reader screen five loose colours is how they drift apart.
 */
data class ReaderColors(
    val page: Int,
    val text: Int,
    /** Headings; equal to [text] unless the reader set one. */
    val heading: Int,
    /** Links and note references. */
    val link: Int,
    /** Captions, quotes and anything else set quieter than the prose. */
    val secondary: Int
)

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
