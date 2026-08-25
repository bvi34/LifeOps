package com.citation.core.doc

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * The load-bearing test of the structured reader.
 *
 * Structure was added by *instrumenting* the original text reduction rather than replacing it,
 * because the reduced text is the surface every frozen note snapshot and every stored anchor offset
 * was captured against. If the new pipeline shifted a single character, notes taken before this
 * change would resolve onto the wrong words — silently.
 *
 * So this test keeps the original regex implementation verbatim as an **oracle** and asserts the new
 * pipeline is byte-identical to it, over hand-picked adversarial markup *and* every content document
 * in a real EPUB.
 */
class HtmlReductionParityTest {

    // --- The original implementation, kept verbatim as the oracle -------------------------------

    private object Legacy {
        private val SCRIPT_STYLE = Regex("(?is)<(script|style)\\b[^>]*>.*?</\\1>")
        private val BLOCK_BREAK = Regex("(?i)</(p|div|section|article|h[1-6]|li|blockquote|tr)>")
        private val LINE_BREAK = Regex("(?i)<br\\s*/?>")
        private val TAG = Regex("(?s)<[^>]+>")
        private val MULTI_BLANK = Regex("\\n{3,}")
        private val INLINE_WS = Regex("[ \\t\\x0B\\f\\r]+")

        fun toText(html: String): String {
            var s = html
            s = SCRIPT_STYLE.replace(s, " ")
            s = LINE_BREAK.replace(s, "\n")
            s = BLOCK_BREAK.replace(s, "\n\n")
            s = TAG.replace(s, "")
            s = decodeEntities(s)
            s = s.lines().joinToString("\n") { INLINE_WS.replace(it, " ").trim() }
            s = MULTI_BLANK.replace(s, "\n\n")
            return s.trim()
        }

        private fun decodeEntities(s: String): String {
            if (!s.contains('&')) return s
            val sb = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '&') {
                    val semi = s.indexOf(';', i + 1)
                    if (semi in (i + 1)..(i + 12)) {
                        val decoded = decodeEntity(s.substring(i + 1, semi))
                        if (decoded != null) {
                            sb.append(decoded)
                            i = semi + 1
                            continue
                        }
                    }
                }
                sb.append(c)
                i++
            }
            return sb.toString()
        }

        private fun decodeEntity(entity: String): String? = when {
            entity.startsWith("#x") || entity.startsWith("#X") ->
                entity.drop(2).toIntOrNull(16)?.let { codePoint(it) }
            entity.startsWith("#") ->
                entity.drop(1).toIntOrNull()?.let { codePoint(it) }
            else -> NAMED[entity]
        }

        private fun codePoint(cp: Int): String? =
            if (cp in 1..0x10FFFF) String(Character.toChars(cp)) else null

        private val NAMED = mapOf(
            "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
            "nbsp" to " ", "mdash" to "—", "ndash" to "–", "middot" to "·",
            "hellip" to "…", "rsquo" to "’", "lsquo" to "‘",
            "rdquo" to "”", "ldquo" to "“", "copy" to "©", "trade" to "™"
        )
    }

    private fun assertParity(html: String) {
        assertEquals("text-only path", Legacy.toText(html), HtmlDocument.toText(html))
        assertEquals("structured path", Legacy.toText(html), HtmlDocument.parse(html).text)
    }

    // --- Adversarial cases ----------------------------------------------------------------------

    private val cases = listOf(
        "",
        "plain text with no markup at all",
        "<p>One.</p><p>Two.</p>",
        "<h1>Title</h1><p>Body <em>emphasis</em> and <strong>bold</strong>.</p>",
        "<p>line one<br>line two<br/>line three<br />line four</p>",
        "<p>a</p>\n\n\n\n<p>b</p>",
        "  <p>  padded   with    runs \t\t of   space  </p>  ",
        "<script>var a = '<p>not text</p>';</script><p>real</p>",
        "<style>p { color: red }</style><p>styled</p>",
        "<script>unclosed and <p>swallowed?</p>",
        "<script>a<script>b</script>c</script><p>d</p>",
        "<p>entities: &amp; &lt; &gt; &quot; &nbsp; &mdash; &hellip; &#65; &#x42;</p>",
        "<p>&am<b>p;</b> split entity</p>",
        "<p>unterminated &amper and &#zzz;</p>",
        "<p>a < b and 5 > 3</p>",
        "<>",
        "<<a>nested bracket",
        "<p>trailing tag with no close",
        "<blockquote><p>Quoted.</p></blockquote><p>After.</p>",
        "<ul><li>one</li><li>two</li></ul>",
        "<ol><li>first</li><li>second</li></ol>",
        "<table><tr><td>a</td><td>b</td></tr><tr><td>c</td><td>d</td></tr></table>",
        "<pre>  keep   spacing?  </pre>",
        "<div>div content</div><section>section</section><article>article</article>",
        "<p>CRLF\r\nand CR\rand LF\nmixed</p>",
        "<p>tab\tseparated\tvalues</p>",
        "<P>UPPERCASE TAGS</P><BR><H2>Heading</H2>",
        "<img src=\"cover.jpg\" alt=\"A cover\"/><p>after image</p>",
        "<p>text<img src='inline.png'>more text</p>",
        "<hr/><p>after rule</p>",
        "<p class=\"verse\">line<br>line</p>",
        "<sup><a href=\"#fn1\" epub:type=\"noteref\">1</a></sup>",
        "<p> non-breaking literal</p>",
        "<p></p><p></p><p>only one has text</p>",
        "<span style=\"font-variant: small-caps\">caps</span>",
        "<p>deeply <em>nested <strong>inline <code>markup</code></strong></em> here</p>",
        "<div><div><div><p>deep</p></div></div></div>",
        "<p unclosed attr=\"x>weird</p>",
        "<a href=\"http://example.com/?a=1&amp;b=2\">link</a>"
    )

    @Test
    fun `structured reduction matches the original byte for byte`() {
        cases.forEach { assertParity(it) }
    }

    @Test
    fun `structured reduction matches the original on a real epub`() {
        val bytes = javaClass.classLoader.getResourceAsStream("ao3_sample.epub")!!.readBytes()
        val documents = contentDocuments(bytes)
        assert(documents.size >= 3) { "expected several content documents, got ${documents.size}" }
        documents.forEach { assertParity(it) }
    }

    @Test
    fun `every chapter of a real epub keeps its exact length under the structured parse`() {
        val bytes = javaClass.classLoader.getResourceAsStream("ao3_sample.epub")!!.readBytes()
        contentDocuments(bytes).forEach { html ->
            val parsed = HtmlDocument.parse(html)
            assertEquals(Legacy.toText(html).length, parsed.text.length)
            // Every block must address real text, in order, without running past the end.
            var previous = 0
            parsed.blocks.forEach { block ->
                assert(block.start >= previous) { "blocks out of order at ${block.start}" }
                assert(block.end <= parsed.text.length) { "block past end: ${block.end}" }
                assert(block.end >= block.start) { "inverted block at ${block.start}" }
                previous = block.start
            }
        }
    }

    private fun contentDocuments(epub: ByteArray): List<String> {
        val out = ArrayList<String>()
        ZipInputStream(ByteArrayInputStream(epub)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name.lowercase()
                if (name.endsWith(".xhtml") || name.endsWith(".html") || name.endsWith(".htm")) {
                    out.add(zip.readBytes().toString(Charsets.UTF_8))
                }
            }
        }
        return out
    }
}
