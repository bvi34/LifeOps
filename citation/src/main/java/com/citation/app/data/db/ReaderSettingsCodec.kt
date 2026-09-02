package com.citation.app.data.db

import com.citation.core.note.HighlightColor
import com.citation.core.reader.ParagraphSpacing
import com.citation.core.reader.ReaderSettings
import com.citation.core.reader.ReaderTheme
import com.citation.core.reader.ReaderTypeface
import com.citation.core.reader.ScreenOrientation
import org.json.JSONObject

/**
 * Serializes [ReaderSettings] to the opaque `settingsJson` column.
 *
 * Written field by field with a default for every read, which is the point: settings gain fields
 * over time, and a row written by an older build must keep working rather than resetting a reader's
 * whole setup because one key was missing. A value that cannot be understood — an enum renamed, a
 * number that is no longer a number — falls back to the default for that field alone, so one bad
 * key costs one setting rather than all of them.
 */
object ReaderSettingsCodec {

    fun encode(settings: ReaderSettings): String = JSONObject().apply {
        put("fontSize", settings.fontSize.toDouble())
        put("lineSpacing", settings.lineSpacing.toDouble())
        put("marginDp", settings.marginDp.toDouble())
        put("letterSpacing", settings.letterSpacing.toDouble())
        put("typeface", settings.typeface.name)
        settings.customFontPath?.let { put("customFontPath", it) }
        put("justify", settings.justify)
        put("hyphenate", settings.hyphenate)
        put("paragraphs", settings.paragraphs.name)
        put("theme", settings.theme.name)
        settings.customBackground?.let { put("customBackground", it) }
        settings.customText?.let { put("customText", it) }
        settings.customHeading?.let { put("customHeading", it) }
        settings.customLink?.let { put("customLink", it) }
        put("trueBlack", settings.trueBlack)
        put("warmth", settings.warmth.toDouble())
        put("styleReadInPlace", settings.styleReadInPlace)
        put("highlightColor", settings.highlightColor.name)
        put("brightness", settings.brightness.toDouble())
        put("paged", settings.paged)
        put("keepAwake", settings.keepAwake)
        put("volumeKeyTurns", settings.volumeKeyTurns)
        put("volumeKeysReversed", settings.volumeKeysReversed)
        put("orientation", settings.orientation.name)
        put("immersive", settings.immersive)
    }.toString()

    /** Decode a stored row. Anything unreadable degrades to the defaults rather than throwing. */
    fun decode(json: String?): ReaderSettings {
        if (json.isNullOrBlank()) return ReaderSettings()
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return ReaderSettings()
        val defaults = ReaderSettings()
        return ReaderSettings(
            fontSize = obj.float("fontSize", defaults.fontSize),
            lineSpacing = obj.float("lineSpacing", defaults.lineSpacing),
            marginDp = obj.float("marginDp", defaults.marginDp),
            letterSpacing = obj.float("letterSpacing", defaults.letterSpacing),
            typeface = obj.enum("typeface", defaults.typeface) { ReaderTypeface.valueOf(it) },
            customFontPath = obj.optString("customFontPath").takeIf { it.isNotBlank() },
            justify = obj.optBoolean("justify", defaults.justify),
            hyphenate = obj.optBoolean("hyphenate", defaults.hyphenate),
            paragraphs = obj.enum("paragraphs", defaults.paragraphs) { ParagraphSpacing.valueOf(it) },
            theme = obj.enum("theme", defaults.theme) { ReaderTheme.valueOf(it) },
            customBackground = obj.colour("customBackground"),
            customText = obj.colour("customText"),
            customHeading = obj.colour("customHeading"),
            customLink = obj.colour("customLink"),
            trueBlack = obj.optBoolean("trueBlack", defaults.trueBlack),
            warmth = obj.float("warmth", defaults.warmth),
            styleReadInPlace = obj.optBoolean("styleReadInPlace", defaults.styleReadInPlace),
            highlightColor = obj.enum("highlightColor", defaults.highlightColor) { HighlightColor.valueOf(it) },
            brightness = obj.float("brightness", defaults.brightness),
            paged = obj.optBoolean("paged", defaults.paged),
            keepAwake = obj.optBoolean("keepAwake", defaults.keepAwake),
            volumeKeyTurns = obj.optBoolean("volumeKeyTurns", defaults.volumeKeyTurns),
            volumeKeysReversed = obj.optBoolean("volumeKeysReversed", defaults.volumeKeysReversed),
            orientation = obj.enum("orientation", defaults.orientation) { ScreenOrientation.valueOf(it) },
            immersive = obj.optBoolean("immersive", defaults.immersive)
        ).sanitized()
    }

    /**
     * A stored ARGB colour, or `null` when the reader never picked one.
     *
     * Absent has to stay distinguishable from black: `optInt` would hand back `0` for a key that was
     * never written, which is a perfectly valid colour and would leave a reader who has never opened
     * the custom theme with a transparent page. Read as a long so a value written out as one — an
     * opaque colour is negative as a signed `Int` — still round-trips.
     */
    private fun JSONObject.colour(name: String): Int? {
        if (!has(name) || isNull(name)) return null
        val raw = optLong(name, Long.MIN_VALUE)
        return if (raw == Long.MIN_VALUE) null else raw.toInt()
    }

    private fun JSONObject.float(name: String, fallback: Float): Float =
        if (has(name)) optDouble(name, fallback.toDouble()).toFloat() else fallback

    private inline fun <T> JSONObject.enum(name: String, fallback: T, parse: (String) -> T): T {
        val raw = optString(name).takeIf { it.isNotBlank() } ?: return fallback
        return runCatching { parse(raw) }.getOrDefault(fallback)
    }
}
