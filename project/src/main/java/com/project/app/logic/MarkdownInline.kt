package com.project.app.logic

/**
 * A run of text and the emphasis it carries.
 *
 * Deliberately not a Compose type: emphasis is a fact about the text, and keeping it out of the UI
 * layer is what lets the whole inline parser be tested without an emulator. The renderer in
 * `ui/docs/DocMarkdown` turns these into an `AnnotatedString`, and nothing else needs to know how.
 */
data class MarkdownSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val strike: Boolean = false,
    val code: Boolean = false,
    /** The target of a link, when this run is one. */
    val link: String? = null
) {
    /** Whether this run is plain text carrying no emphasis at all. */
    val isPlain: Boolean get() = !bold && !italic && !strike && !code && link == null
}

/**
 * The *inline* half of Markdown — emphasis, code, strikethrough and links inside a line.
 *
 * `DocBlocks` handles structure (what a line *is*); this handles what the characters inside it
 * mean. They are separate on purpose: structure is stored, because a heading is a fact about a
 * block that survives editing, whereas emphasis is only ever the asterisks you typed. Nothing here
 * rewrites the stored text — [spans] is a *reading* of it, computed at draw time — so the text you
 * typed is the text that exports, and a stray `*` in a shell command is still there tomorrow.
 *
 * The grammar is the common subset people actually type:
 *
 * - `**bold**` and `__bold__`
 * - `*italic*` and `_italic_`
 * - `~~struck through~~`
 * - `` `code` `` (and longer backtick fences, so a span can contain a backtick)
 * - `[label](https://example.com)`, and `![alt](url)` shown as its alt text
 * - `\*` and friends, for when you meant the character
 *
 * Unmatched delimiters stay literal, which is the behaviour that matters most in a document full of
 * half-written notes: a lone asterisk at the end of a line is an asterisk, not the start of an
 * italic run that swallows the rest of the paragraph.
 */
object MarkdownInline {

    private const val ESCAPABLE = "\\`*_~[]()#+-.!>|"

    /** Read [text] as a sequence of styled runs. Always returns at least one span for non-empty text. */
    fun spans(text: String): List<MarkdownSpan> {
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<MarkdownSpan>()
        parse(text, Marks(), out)
        return merge(out)
    }

    /** [text] with its emphasis markers removed — for previews, search snippets and word counts. */
    fun plain(text: String): String = spans(text).joinToString("") { it.text }

    /** Whether [text] carries any inline markup at all, worth the parse on a redraw. */
    fun hasMarkup(text: String): Boolean =
        text.any { it == '*' || it == '_' || it == '`' || it == '~' || it == '[' || it == '\\' }

    /** The emphasis in force at some point in the parse. */
    private data class Marks(
        val bold: Boolean = false,
        val italic: Boolean = false,
        val strike: Boolean = false,
        val link: String? = null
    ) {
        fun span(text: String, code: Boolean = false) =
            MarkdownSpan(text, bold, italic, strike, code, link)
    }

    private fun parse(text: String, marks: Marks, out: MutableList<MarkdownSpan>) {
        val buffer = StringBuilder()
        fun flush() {
            if (buffer.isNotEmpty()) {
                out += marks.span(buffer.toString())
                buffer.setLength(0)
            }
        }

        var i = 0
        while (i < text.length) {
            val c = text[i]

            // An escape is the one rule that beats every other: `\*` is an asterisk, full stop.
            if (c == '\\' && i + 1 < text.length && text[i + 1] in ESCAPABLE) {
                buffer.append(text[i + 1])
                i += 2
                continue
            }

            if (c == '`') {
                val fence = runLengthAt(text, i, '`')
                val close = indexOfRun(text, i + fence, '`', fence)
                if (close >= 0) {
                    flush()
                    // A code span is literal all the way through: no emphasis, no escapes, nothing
                    // rewritten. That is the entire point of typing one.
                    out += marks.span(text.substring(i + fence, close).trim(' '), code = true)
                    i = close + fence
                    continue
                }
            }

            if (c == '~' && runLengthAt(text, i, '~') >= 2 && !marks.strike) {
                val close = closingDelimiter(text, i + 2, "~~")
                if (close >= 0) {
                    flush()
                    parse(text.substring(i + 2, close), marks.copy(strike = true), out)
                    i = close + 2
                    continue
                }
            }

            if ((c == '*' || c == '_') && emphasisOpens(text, i)) {
                val run = runLengthAt(text, i, c)
                val strong = run >= 2
                val delimiter = if (strong) "$c$c" else "$c"
                val bodyStart = i + delimiter.length
                val close = closingDelimiter(text, bodyStart, delimiter)
                val alreadySet = if (strong) marks.bold else marks.italic
                if (close >= 0 && !alreadySet && close > bodyStart) {
                    flush()
                    val inner = if (strong) marks.copy(bold = true) else marks.copy(italic = true)
                    parse(text.substring(bodyStart, close), inner, out)
                    i = close + delimiter.length
                    continue
                }
            }

            if (c == '[' || (c == '!' && i + 1 < text.length && text[i + 1] == '[')) {
                val image = c == '!'
                val open = if (image) i + 1 else i
                val label = matchingBracket(text, open)
                if (label > 0 && label + 1 < text.length && text[label + 1] == '(') {
                    val target = text.indexOf(')', label + 2)
                    if (target > 0) {
                        val url = text.substring(label + 2, target).trim().substringBefore(' ')
                        val body = text.substring(open + 1, label)
                        flush()
                        // An image shows as its alt text pointing at the file. A document editor
                        // that renders `![](…)` as nothing loses the caption somebody wrote.
                        val shown = if (image && body.isBlank()) url.substringAfterLast('/') else body
                        parse(shown, marks.copy(link = url.ifBlank { null } ?: marks.link), out)
                        i = target + 1
                        continue
                    }
                }
            }

            buffer.append(c)
            i++
        }
        flush()
    }

    /**
     * Whether the delimiter at [at] can *open* emphasis.
     *
     * Two rules, both earning their keep in real documents: an opener is never followed by a space
     * (so `2 * 3 * 4` is arithmetic, not italics), and an underscore never opens inside a word (so
     * `snake_case_names` survive, which asterisks-only parsers get wrong every time).
     */
    private fun emphasisOpens(text: String, at: Int): Boolean {
        val c = text[at]
        val run = runLengthAt(text, at, c)
        val after = text.getOrNull(at + run) ?: return false
        if (after.isWhitespace()) return false
        if (c == '_') {
            val before = text.getOrNull(at - 1)
            if (before != null && (before.isLetterOrDigit() || before == '_')) return false
        }
        return true
    }

    /**
     * The index of the [delimiter] that closes a run opened at [from], or -1 when nothing does.
     *
     * Skips code spans and escapes so a backtick-quoted `**` cannot close an emphasis run started
     * outside it, and refuses a closer that follows a space — the mirror of the opener rule.
     */
    private fun closingDelimiter(text: String, from: Int, delimiter: String): Int {
        var i = from
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\\' -> i += 2

                c == '`' -> {
                    val fence = runLengthAt(text, i, '`')
                    val close = indexOfRun(text, i + fence, '`', fence)
                    i = if (close < 0) i + fence else close + fence
                }

                text.startsWith(delimiter, i) -> {
                    val before = text.getOrNull(i - 1)
                    val opensLonger = delimiter.length == 1 &&
                        runLengthAt(text, i, delimiter[0]) > 1 && i == from
                    val closesInsideWord = delimiter[0] == '_' &&
                        text.getOrNull(i + delimiter.length)?.isLetterOrDigit() == true
                    if (before != null && !before.isWhitespace() && !opensLonger && !closesInsideWord) {
                        return i
                    }
                    i += delimiter.length
                }

                else -> i++
            }
        }
        return -1
    }

    /** The index of the `]` matching the `[` at [open], honouring nesting, or -1. */
    private fun matchingBracket(text: String, open: Int): Int {
        var depth = 0
        var i = open
        while (i < text.length) {
            when {
                text[i] == '\\' -> i++
                text[i] == '[' -> depth++
                text[i] == ']' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return -1
    }

    /** How many [c] in a row start at [at]. */
    private fun runLengthAt(text: String, at: Int, c: Char): Int {
        var n = 0
        while (at + n < text.length && text[at + n] == c) n++
        return n
    }

    /** The next run of exactly [length] [c]s at or after [from], or -1. */
    private fun indexOfRun(text: String, from: Int, c: Char, length: Int): Int {
        var i = from
        while (i < text.length) {
            if (text[i] == c) {
                val run = runLengthAt(text, i, c)
                if (run == length) return i
                i += run
            } else {
                i++
            }
        }
        return -1
    }

    /** Glue neighbouring runs that ended up with identical styling back together. */
    private fun merge(spans: List<MarkdownSpan>): List<MarkdownSpan> {
        val out = ArrayList<MarkdownSpan>(spans.size)
        spans.filter { it.text.isNotEmpty() }.forEach { span ->
            val last = out.lastOrNull()
            if (last != null && last.copy(text = "") == span.copy(text = "")) {
                out[out.size - 1] = last.copy(text = last.text + span.text)
            } else {
                out += span
            }
        }
        return out
    }
}
