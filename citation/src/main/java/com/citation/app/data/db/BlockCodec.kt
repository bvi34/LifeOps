package com.citation.app.data.db

import com.citation.core.doc.BlockKind
import com.citation.core.doc.DocumentBlock
import com.citation.core.doc.InlineSpan
import com.citation.core.doc.InlineStyle
import com.citation.core.model.TableOfContents
import com.citation.core.model.TocEntry
import org.json.JSONArray
import org.json.JSONObject

/**
 * Serializes a chapter's structure and a book's contents list to the opaque `blocksJson`,
 * `anchorsJson` and `tocJson` columns.
 *
 * Hand-rolled via `org.json` for the same reason [AnchorCodec] is: these are sealed hierarchies
 * that need an explicit type tag to round-trip, and a reflective encoder would quietly change shape
 * the next time a field is added. Compactness matters more here than in the anchor columns — a
 * dense reference book can hold thousands of blocks — so keys are short and defaults are omitted.
 *
 * Structure is **derived data**: every field is recoverable by re-parsing the stored source file.
 * So decoding is total — a column that is absent, blank or unreadable yields no structure rather
 * than an error, and the reader falls back to setting the chapter as plain paragraphs, which is
 * exactly what it did before structure existed.
 */
object BlockCodec {

    // Short tags: this is the widest column in the database.
    private const val TEXT = "t"
    private const val IMAGE = "i"
    private const val RULE = "r"
    private const val TABLE = "b"

    fun encodeBlocks(blocks: List<DocumentBlock>): String {
        val arr = JSONArray()
        blocks.forEach { block ->
            val obj = JSONObject()
            when (block) {
                is DocumentBlock.Text -> {
                    obj.put("k", TEXT).put("s", block.start).put("e", block.end)
                    if (block.kind != BlockKind.PARAGRAPH) obj.put("d", block.kind.name)
                    if (block.level != 0) obj.put("l", block.level)
                    if (block.ordered) obj.put("o", true)
                    if (block.itemIndex != 0) obj.put("n", block.itemIndex)
                    if (block.spans.isNotEmpty()) obj.put("p", encodeSpans(block.spans))
                }
                is DocumentBlock.Image -> {
                    obj.put("k", IMAGE).put("s", block.start).put("src", block.src)
                    block.alt?.let { obj.put("a", it) }
                }
                is DocumentBlock.Rule -> obj.put("k", RULE).put("s", block.start)
                is DocumentBlock.Table -> {
                    obj.put("k", TABLE).put("s", block.start).put("e", block.end)
                    val rows = JSONArray()
                    block.rows.forEach { row ->
                        val cells = JSONArray()
                        row.cells.forEach { cells.put(JSONArray().put(it.first).put(it.last + 1)) }
                        rows.put(
                            JSONObject().put("s", row.start).put("e", row.end)
                                .put("c", cells)
                                .also { if (row.header) it.put("h", true) }
                        )
                    }
                    obj.put("rows", rows)
                }
            }
            arr.put(obj)
        }
        return arr.toString()
    }

    /** Decode `blocksJson`. Unreadable or absent structure yields none — never an exception. */
    fun decodeBlocks(json: String?): List<DocumentBlock> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i -> block(arr.getJSONObject(i)) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun block(obj: JSONObject): DocumentBlock? = when (obj.optString("k")) {
        TEXT -> DocumentBlock.Text(
            start = obj.getInt("s"),
            end = obj.getInt("e"),
            kind = runCatching { BlockKind.valueOf(obj.optString("d", BlockKind.PARAGRAPH.name)) }
                .getOrDefault(BlockKind.PARAGRAPH),
            level = obj.optInt("l", 0),
            ordered = obj.optBoolean("o", false),
            itemIndex = obj.optInt("n", 0),
            spans = decodeSpans(obj.optJSONArray("p"))
        )
        IMAGE -> DocumentBlock.Image(
            start = obj.getInt("s"),
            src = obj.getString("src"),
            alt = obj.optString("a").takeIf { it.isNotBlank() }
        )
        RULE -> DocumentBlock.Rule(obj.getInt("s"))
        TABLE -> {
            val rows = obj.optJSONArray("rows") ?: JSONArray()
            DocumentBlock.Table(
                start = obj.getInt("s"),
                end = obj.getInt("e"),
                rows = (0 until rows.length()).map { i ->
                    val row = rows.getJSONObject(i)
                    val cells = row.optJSONArray("c") ?: JSONArray()
                    DocumentBlock.Table.Row(
                        start = row.getInt("s"),
                        end = row.getInt("e"),
                        cells = (0 until cells.length()).map { j ->
                            val cell = cells.getJSONArray(j)
                            cell.getInt(0) until cell.getInt(1)
                        },
                        header = row.optBoolean("h", false)
                    )
                }
            )
        }
        else -> null
    }

    private fun encodeSpans(spans: List<InlineSpan>): JSONArray {
        val arr = JSONArray()
        spans.forEach { span ->
            val obj = JSONObject().put("s", span.start).put("e", span.end).put("y", span.style.name)
            span.href?.let { obj.put("h", it) }
            arr.put(obj)
        }
        return arr
    }

    private fun decodeSpans(arr: JSONArray?): List<InlineSpan> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val obj = arr.getJSONObject(i)
            val style = runCatching { InlineStyle.valueOf(obj.getString("y")) }.getOrNull()
                ?: return@mapNotNull null
            InlineSpan(
                start = obj.getInt("s"),
                end = obj.getInt("e"),
                style = style,
                href = obj.optString("h").takeIf { it.isNotBlank() }
            )
        }
    }

    // --- Anchors -----------------------------------------------------------------------------

    /** Element id to text offset, for resolving a footnote or a contents entry to a place. */
    fun encodeAnchors(anchors: Map<String, Int>): String {
        val obj = JSONObject()
        anchors.forEach { (id, offset) -> obj.put(id, offset) }
        return obj.toString()
    }

    fun decodeAnchors(json: String?): Map<String, Int> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            val obj = JSONObject(json)
            obj.keys().asSequence().associateWith { obj.getInt(it) }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    // --- Table of contents -------------------------------------------------------------------

    fun encodeToc(toc: TableOfContents): String = entries(toc.entries).toString()

    private fun entries(list: List<TocEntry>): JSONArray {
        val arr = JSONArray()
        list.forEach { entry ->
            val obj = JSONObject().put("t", entry.title)
            entry.chapterOrdinal?.let { obj.put("c", it) }
            entry.fragment?.let { obj.put("f", it) }
            if (entry.children.isNotEmpty()) obj.put("n", entries(entry.children))
            arr.put(obj)
        }
        return arr
    }

    fun decodeToc(json: String?): TableOfContents {
        if (json.isNullOrBlank()) return TableOfContents.EMPTY
        return try {
            TableOfContents(tocEntries(JSONArray(json)))
        } catch (_: Exception) {
            TableOfContents.EMPTY
        }
    }

    private fun tocEntries(arr: JSONArray): List<TocEntry> =
        (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            TocEntry(
                title = obj.getString("t"),
                chapterOrdinal = if (obj.has("c")) obj.getInt("c") else null,
                fragment = obj.optString("f").takeIf { it.isNotBlank() },
                children = obj.optJSONArray("n")?.let { tocEntries(it) } ?: emptyList()
            )
        }

    // --- Subjects ----------------------------------------------------------------------------

    fun encodeStrings(values: List<String>): String {
        val arr = JSONArray()
        values.forEach { arr.put(it) }
        return arr.toString()
    }

    fun decodeStrings(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
