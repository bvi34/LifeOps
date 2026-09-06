package com.citation.core.speech

/**
 * What the narrator says, and how long it rests between saying it.
 *
 * Separate from [SpeechSettings] — which is about the *voice* — because this is about the *text*,
 * and the two have different lifetimes: a reader picks a voice once and then spends a year deciding,
 * book by book, whether they want the footnotes read out. It is also the whole input to
 * [SpeechPlanner] beyond the chapter itself, which keeps planning a pure function of two values and
 * so trivially testable.
 *
 * The defaults describe reading a novel aloud: prose, headings and quotations spoken; the apparatus
 * around them left on the page. Every one of them is a judgement about what a *listener* wants, and
 * each is stated where it is made rather than buried in the planner.
 */
data class SpeechOptions(
    /** Longest unit handed to an engine; see [SentenceSplitter.DEFAULT_MAX_CHARACTERS]. */
    val maxUtteranceCharacters: Int = SentenceSplitter.DEFAULT_MAX_CHARACTERS,

    /** Speak headings. On: a listener navigating by ear has nothing else to navigate by. */
    val speakHeadings: Boolean = true,

    /** Speak figure and table captions. On: a caption is usually a sentence about the book. */
    val speakCaptions: Boolean = true,

    /**
     * Speak an illustration's alt text.
     *
     * Off by default, which is not a slight to alt text — it is that most alt text in an ebook is
     * `cover.jpg` or a decorative flourish, and "image, ornament" every three pages is worse than
     * silence. A reader who wants figures described turns it on and gets every one.
     */
    val speakImageAlt: Boolean = false,

    /**
     * Speak code blocks and preformatted text.
     *
     * Off by default: source code read aloud is noise even to the person who wrote it. Worth having
     * for the reader working through a book of short snippets, which is exactly the O'Reilly-shaped
     * technical reading Citation is otherwise good at.
     */
    val speakCode: Boolean = false,

    /**
     * Speak tables, one row at a time with cells separated in the ear.
     *
     * Off by default. The canonical reduction runs cells together without a separator, so a table
     * spoken as flat text is gibberish; with the separator it is merely tedious. On, it is at least
     * comprehensible — see [SpeechPlanner]'s row handling, which is why [Utterance] carries a
     * spoken-to-canonical mapping at all.
     */
    val speakTables: Boolean = false,

    /**
     * Read footnote markers aloud.
     *
     * Off by default: a superscript `12` becomes "twelve" in the middle of a clause, which is the
     * single most jarring thing an ebook narrator does. The marker's characters stay in the text and
     * keep their offsets; they are simply excluded from what gets said.
     */
    val speakFootnoteMarkers: Boolean = false,

    /** How long to rest after each kind of unit. */
    val pauses: SpeechPauses = SpeechPauses()
)

/**
 * The silences. Pacing is most of what separates a narrator from a screen reader, and none of it
 * comes free from an engine: a TTS voice pauses for a comma and stops dead at a period, with no
 * notion that one paragraph ended and another began, or that a scene just changed.
 *
 * Values are milliseconds of added silence *after* a unit, and are deliberately conservative — long
 * enough to hear as structure, short enough that nobody thinks the app has hung.
 */
data class SpeechPauses(
    /** Between sentences inside a paragraph. The engine's own full-stop pause usually suffices. */
    val betweenSentences: Int = 0,
    /** After the last sentence of a paragraph. */
    val betweenParagraphs: Int = 350,
    /** After a heading, which is an announcement and wants room. */
    val afterHeading: Int = 600,
    /** Between lines of verse, where the line break is the meaning. */
    val betweenVerseLines: Int = 250,
    /** Between list items. */
    val betweenListItems: Int = 300,
    /** After a caption. */
    val afterCaption: Int = 400,
    /** Between table rows. */
    val betweenTableRows: Int = 300,
    /** At a scene break — the longest rest in the book, because that is what it is on the page. */
    val atSceneBreak: Int = 900,
    /** After the last unit of a chapter, before the next one begins. */
    val betweenChapters: Int = 1200
)
