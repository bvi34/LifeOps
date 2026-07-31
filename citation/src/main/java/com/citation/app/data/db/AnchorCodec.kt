package com.citation.app.data.db

import com.citation.core.anchor.TextAnchor
import com.citation.core.note.PassageReference
import org.json.JSONArray
import org.json.JSONObject

/**
 * Serializes the sealed [TextAnchor] and note [PassageReference] lists to/from JSON for the opaque
 * `anchorJson` / `referencesJson` Room columns. Hand-rolled (via `org.json`, which is on the Android
 * platform) rather than reflective, because a sealed hierarchy needs an explicit type tag to
 * round-trip — and getting an anchor's shape wrong would silently break jump-to-context.
 */
object AnchorCodec {

    fun encodeAnchor(anchor: TextAnchor): String = anchorToJson(anchor).toString()

    fun decodeAnchor(json: String): TextAnchor = jsonToAnchor(JSONObject(json))

    fun encodeReferences(refs: List<PassageReference>): String {
        val arr = JSONArray()
        refs.forEach { ref ->
            arr.put(
                JSONObject()
                    .put("snapshot", ref.quotedSnapshot)
                    .put("anchor", anchorToJson(ref.anchor))
            )
        }
        return arr.toString()
    }

    fun decodeReferences(json: String): List<PassageReference> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            PassageReference(
                quotedSnapshot = obj.getString("snapshot"),
                anchor = jsonToAnchor(obj.getJSONObject("anchor"))
            )
        }
    }

    /** Serialize a note's tag list to the opaque `tagsJson` column (a plain JSON string array). */
    fun encodeTags(tags: List<String>): String {
        val arr = JSONArray()
        tags.forEach { arr.put(it) }
        return arr.toString()
    }

    /** Decode `tagsJson` back into a tag list; a blank/absent column reads as no tags. */
    fun decodeTags(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        val arr = JSONArray(json)
        return (0 until arr.length()).map { arr.getString(it) }
    }

    private fun anchorToJson(anchor: TextAnchor): JSONObject = when (anchor) {
        is TextAnchor.Flowing -> JSONObject()
            .put("type", "flowing")
            .put("chapterOrdinal", anchor.chapterOrdinal)
            .put("approxStart", anchor.approxStart)
            .put("quote", anchor.quote)
            .put("prefix", anchor.prefix)
            .put("suffix", anchor.suffix)
        is TextAnchor.Pdf -> JSONObject()
            .put("type", "pdf")
            .put("page", anchor.page)
            .put("quote", anchor.quote)
            .put("quads", JSONArray().apply {
                anchor.quads.forEach { q ->
                    put(JSONObject().put("x0", q.x0).put("y0", q.y0).put("x1", q.x1).put("y1", q.y1))
                }
            })
        is TextAnchor.External -> JSONObject()
            .put("type", "external")
            .put("location", anchor.location)
            .put("quote", anchor.quote)
            .put("bookRef", anchor.bookRef)
    }

    private fun jsonToAnchor(obj: JSONObject): TextAnchor = when (obj.getString("type")) {
        "flowing" -> TextAnchor.Flowing(
            chapterOrdinal = obj.getInt("chapterOrdinal"),
            approxStart = obj.getInt("approxStart"),
            quote = obj.getString("quote"),
            prefix = obj.optString("prefix", ""),
            suffix = obj.optString("suffix", "")
        )
        "pdf" -> {
            val quadsArr = obj.getJSONArray("quads")
            val quads = (0 until quadsArr.length()).map { i ->
                val q = quadsArr.getJSONObject(i)
                TextAnchor.Quad(
                    q.getDouble("x0").toFloat(), q.getDouble("y0").toFloat(),
                    q.getDouble("x1").toFloat(), q.getDouble("y1").toFloat()
                )
            }
            TextAnchor.Pdf(obj.getInt("page"), quads, obj.getString("quote"))
        }
        "external" -> TextAnchor.External(
            location = obj.getString("location"),
            quote = obj.getString("quote"),
            bookRef = obj.opt("bookRef")?.takeUnless { it === JSONObject.NULL }?.toString()
        )
        else -> error("Unknown anchor type in $obj")
    }
}
