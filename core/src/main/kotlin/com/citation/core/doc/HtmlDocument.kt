package com.citation.core.doc

/**
 * HTML into **canonical flowing text plus structure**, in one instrumented pass.
 *
 * ### Why this exists, and why it is written this way
 *
 * Citation's reader was format-blind but also *structure-blind*: chapters were reduced to a flat
 * string, so italics, headings, verse, tables and illustrations were all thrown away before the
 * reader ever saw them. Recovering them is not as simple as "render the HTML instead", because the
 * flat string is load-bearing — it is the surface every frozen note snapshot and every
 * [com.citation.core.anchor.TextAnchor] offset was captured against. Change one character of it and
 * a note taken last year lands on the wrong words.
 *
 * So this parser is deliberately the **same reduction, instrumented**. It performs the original
 * strip-tags / decode-entities / collapse-whitespace pipeline stage by stage, but carries an offset
 * map through every stage, so it can say where each tag *ended up* in the finished text. Structure
 * is then read off those positions as ranges. The text output is identical by construction, not by
 * coincidence — and `HtmlReductionParityTest` pins that against the original regex implementation
 * over a corpus including a real EPUB.
 *
 * The stages, in the order the original applied them (order is observable — entities decode *after*
 * tags are stripped, so `&am<b>p;` really does become `&`):
 *
 *  1. scan: script/style bodies to `" "`, `<br>` to `"\n"`, block closes to `"\n\n"`, other tags to `""`
 *  2. decode entities
 *  3. per line: collapse inline whitespace runs to one space, then trim the line
 *  4. cap runs of 3+ newlines at 2
 *  5. trim the whole document
 */
object HtmlDocument {

    /**
     * A parsed chapter document.
     *
     * @property text the canonical flowing text — byte-identical to the original reduction.
     * @property blocks structure as ranges into [text]; ordered, non-overlapping apart from
     *   zero-width markers.
     * @property anchors `id` attribute to offset in [text], so a link to `#fn1` (a footnote, a TOC
     *   entry pointing mid-chapter) resolves to a place rather than just a file.
     * @property heading the first heading found, for naming the chapter.
     */
    data class Parsed(
        val text: String,
        val blocks: List<DocumentBlock>,
        val anchors: Map<String, Int>,
        val heading: String?
    )

    /** Reduce [html] to canonical text only. The structured [parse] does strictly more work. */
    fun toText(html: String): String {
        var s = scan(html).text
        s = Entities.decode(s)
        s = s.lines().joinToString("\n") { collapseAndTrim(it) }
        s = capBlankRuns(s)
        return s.trim()
    }

    /** Reduce [html] to canonical text **and** the structure layered over it. */
    fun parse(html: String): Parsed {
        val scanned = scan(html)

        // Carry every tag's position through each stage of the reduction.
        val positions = IntArray(scanned.text.length + 1) { it }
        var s = scanned.text
        for (stage in STAGES) {
            val staged = stage(s)
            for (i in positions.indices) positions[i] = staged.map[positions[i]]
            s = staged.text
        }

        val at = IntArray(scanned.tags.size) { positions[scanned.tags[it].at] }
        val built = Structure.build(s, scanned.tags, at)
        return Parsed(text = s, blocks = built.blocks, anchors = built.anchors, heading = built.heading)
    }

    private val STAGES: List<(String) -> Stage> = listOf(
        ::decodeStage, ::lineStage, ::blankStage, ::trimStage
    )

    // --- Stage 1: scan ------------------------------------------------------------------------

    /** The scanned document: tags replaced by their text equivalents, with each tag's position kept. */
    internal class Scanned(val text: String, val tags: List<Tag>)

    /**
     * One markup tag and where its content boundary landed in the scanned text. `at` is recorded
     * *before* the tag's replacement text, so an opening tag sits where its content begins and a
     * closing tag sits where its content ended.
     */
    internal class Tag(val at: Int, val name: String, val closing: Boolean, val raw: String)

    private fun scan(html: String): Scanned {
        val out = StringBuilder(html.length)
        val tags = ArrayList<Tag>()
        var i = 0
        while (i < html.length) {
            val c = html[i]
            if (c != '<') {
                out.append(c)
                i++
                continue
            }
            // The original tag pattern was `<[^>]+>`: a lone `<`, or an empty `<>`, is literal text.
            val gt = html.indexOf('>', i + 1)
            if (gt < 0 || gt == i + 1) {
                out.append(c)
                i++
                continue
            }
            val raw = html.substring(i, gt + 1)
            val name = tagName(raw)

            // script/style swallow their body — but only when the matching close exists; an
            // unclosed one is just another tag, exactly as the original regex treated it.
            if (!raw.startsWith("</") && (name == "script" || name == "style")) {
                val close = "</" + name + ">"
                val k = html.indexOf(close, gt + 1, ignoreCase = true)
                if (k >= 0) {
                    out.append(' ')
                    i = k + close.length
                    continue
                }
            }

            tags.add(Tag(out.length, name, raw.startsWith("</"), raw))
            when {
                LINE_BREAK.matches(raw) -> out.append('\n')
                BLOCK_BREAK.matches(raw) -> out.append("\n\n")
            }
            i = gt + 1
        }
        return Scanned(out.toString(), tags)
    }

    private val LINE_BREAK = Regex("(?i)^<br\\s*/?>$")
    private val BLOCK_BREAK = Regex("(?i)^</(p|div|section|article|h[1-6]|li|blockquote|tr)>$")

    private fun tagName(raw: String): String {
        var i = if (raw.startsWith("</")) 2 else 1
        val start = i
        while (i < raw.length) {
            val c = raw[i]
            if (c.isLetterOrDigit() || c == ':' || c == '-' || c == '_') i++ else break
        }
        return raw.substring(start, i).lowercase()
    }

    // --- Stages 2-5: offset-tracking rewrites -------------------------------------------------

    /**
     * A rewrite of the text plus `map[i]` — where input offset `i` landed in the output. Sized
     * `length + 1` so the end-of-text position maps too.
     */
    private class Stage(val text: String, val map: IntArray)

    private fun decodeStage(s: String): Stage {
        val out = StringBuilder(s.length)
        val map = IntArray(s.length + 1)
        var i = 0
        while (i < s.length) {
            if (s[i] == '&') {
                val hit = Entities.decodeAt(s, i)
                if (hit != null) {
                    val endExclusive = hit.first
                    val target = out.length
                    for (j in i until endExclusive) map[j] = target
                    out.append(hit.second)
                    i = endExclusive
                    continue
                }
            }
            map[i] = out.length
            out.append(s[i])
            i++
        }
        map[s.length] = out.length
        return Stage(out.toString(), map)
    }

    /**
     * Per line: collapse inline-whitespace runs to a single space, then trim. Lines are split on
     * CRLF, LF or CR (what `String.lines()` does) and rejoined with LF.
     */
    private fun lineStage(s: String): Stage {
        val out = StringBuilder(s.length)
        val map = IntArray(s.length + 1)
        var lineStart = 0
        while (true) {
            var j = lineStart
            var sepLen = 0
            while (j < s.length) {
                val c = s[j]
                if (c == '\r') { sepLen = if (j + 1 < s.length && s[j + 1] == '\n') 2 else 1; break }
                if (c == '\n') { sepLen = 1; break }
                j++
            }
            emitLine(s, lineStart, j, out, map)
            if (j >= s.length) break
            for (k in j until j + sepLen) map[k] = out.length
            out.append('\n')
            lineStart = j + sepLen
        }
        map[s.length] = out.length
        return Stage(out.toString(), map)
    }

    /** Collapse + trim one line `[from, to)`, filling [map] for every source offset it covers. */
    private fun emitLine(s: String, from: Int, to: Int, out: StringBuilder, map: IntArray) {
        // Collapse first: each token is one output char plus the source span that produced it.
        val chars = StringBuilder()
        val spanStart = ArrayList<Int>()
        val spanEnd = ArrayList<Int>()
        var k = from
        while (k < to) {
            if (isInlineWs(s[k])) {
                val runStart = k
                while (k < to && isInlineWs(s[k])) k++
                chars.append(' ')
                spanStart.add(runStart)
                spanEnd.add(k)
            } else {
                chars.append(s[k])
                spanStart.add(k)
                spanEnd.add(k + 1)
                k++
            }
        }
        // Then trim: drop whitespace tokens from both ends, as String.trim() would.
        var lo = 0
        var hi = chars.length
        while (lo < hi && chars[lo].isWhitespace()) lo++
        while (hi > lo && chars[hi - 1].isWhitespace()) hi--

        val lineOutStart = out.length
        for (t in 0 until lo) {
            for (p in spanStart[t] until spanEnd[t]) map[p] = lineOutStart
        }
        for (t in lo until hi) {
            val target = out.length
            for (p in spanStart[t] until spanEnd[t]) map[p] = target
            out.append(chars[t])
        }
        val lineOutEnd = out.length
        for (t in hi until chars.length) {
            for (p in spanStart[t] until spanEnd[t]) map[p] = lineOutEnd
        }
    }

    /** The characters the original inline-whitespace class covered: space, tab, VT, FF, CR. */
    private fun isInlineWs(c: Char): Boolean =
        c == ' ' || c == '\t' || c == '\r' || c.code == 0x0B || c.code == 0x0C

    /** Cap runs of three or more newlines at two — one blank line is the most a break ever means. */
    private fun blankStage(s: String): Stage {
        val out = StringBuilder(s.length)
        val map = IntArray(s.length + 1)
        var i = 0
        while (i < s.length) {
            if (s[i] == '\n') {
                var j = i
                while (j < s.length && s[j] == '\n') j++
                val run = j - i
                val target = out.length
                if (run >= 3) {
                    for (t in 0 until run) map[i + t] = target + minOf(t, 2)
                    out.append("\n\n")
                } else {
                    for (t in 0 until run) map[i + t] = target + t
                    repeat(run) { out.append('\n') }
                }
                i = j
                continue
            }
            map[i] = out.length
            out.append(s[i])
            i++
        }
        map[s.length] = out.length
        return Stage(out.toString(), map)
    }

    private fun trimStage(s: String): Stage {
        var lo = 0
        var hi = s.length
        while (lo < hi && s[lo].isWhitespace()) lo++
        while (hi > lo && s[hi - 1].isWhitespace()) hi--
        val out = s.substring(lo, hi)
        val map = IntArray(s.length + 1) { (it - lo).coerceIn(0, out.length) }
        return Stage(out, map)
    }

    // --- Shared helpers used by the text-only path --------------------------------------------

    private fun collapseAndTrim(line: String): String {
        val sb = StringBuilder(line.length)
        var i = 0
        while (i < line.length) {
            if (isInlineWs(line[i])) {
                while (i < line.length && isInlineWs(line[i])) i++
                sb.append(' ')
            } else {
                sb.append(line[i])
                i++
            }
        }
        return sb.toString().trim()
    }

    private fun capBlankRuns(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            if (s[i] == '\n') {
                var j = i
                while (j < s.length && s[j] == '\n') j++
                repeat(minOf(j - i, 2)) { sb.append('\n') }
                i = j
            } else {
                sb.append(s[i])
                i++
            }
        }
        return sb.toString()
    }
}
