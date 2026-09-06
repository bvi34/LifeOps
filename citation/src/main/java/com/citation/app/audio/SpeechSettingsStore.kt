package com.citation.app.audio

import android.content.Context
import com.citation.core.speech.EnginePreference
import com.citation.core.speech.SleepMode
import com.citation.core.speech.SpeechOptions
import com.citation.core.speech.SpeechSettings
import org.json.JSONObject
import java.io.File

/**
 * Persists [SpeechSettings] as one small JSON file.
 *
 * Not a Room table, unlike the reader's display settings, and the difference is the shape of the
 * data rather than an inconsistency: display settings are *per book* and have to be queried and
 * observed alongside a book row, while these are one global set — a voice, a speed, a few toggles —
 * read once when the narrator wakes up. A file is the honest size of that, and it keeps a feature
 * that is additive to reading out of the schema every book already depends on.
 *
 * Decoding is total, field by field with a default for each, exactly as [
 * com.citation.app.data.db.ReaderSettingsCodec] does it and for the same reason: settings gain
 * fields, and a file written by an older build must keep a reader's voice and speed rather than
 * resetting everything because one key was missing. A file that is missing, empty or unreadable
 * yields the defaults, which is a working narrator.
 */
class SpeechSettingsStore(context: Context) {

    private val file = File(context.filesDir, FILE_NAME)

    /** What was saved, or the defaults — never an error. */
    fun load(): SpeechSettings {
        val json = runCatching { if (file.isFile) JSONObject(file.readText()) else null }.getOrNull()
            ?: return SpeechSettings()
        val defaults = SpeechSettings()
        val content = json.optJSONObject("content")
        return SpeechSettings(
            voiceId = json.optString("voiceId").takeIf { it.isNotBlank() },
            engine = json.enum("engine", defaults.engine) { EnginePreference.valueOf(it) },
            rate = json.optDouble("rate", defaults.rate.toDouble()).toFloat(),
            pitch = json.optDouble("pitch", defaults.pitch.toDouble()).toFloat(),
            autoAdvanceChapter = json.optBoolean("autoAdvanceChapter", defaults.autoAdvanceChapter),
            continueInBackground = json.optBoolean("continueInBackground", defaults.continueInBackground),
            announceChapterTitle = json.optBoolean("announceChapterTitle", defaults.announceChapterTitle),
            bankListeningTowardPace = json.optBoolean("bankListeningTowardPace", defaults.bankListeningTowardPace),
            sleepMode = json.enum("sleepMode", defaults.sleepMode) { SleepMode.valueOf(it) },
            content = content?.let { decodeContent(it) } ?: defaults.content
        ).let { it.withRate(it.rate).withPitch(it.pitch) }
    }

    /** Save, best effort: a settings file that cannot be written must not stop a book being read. */
    fun save(settings: SpeechSettings) {
        runCatching {
            file.writeText(
                JSONObject().apply {
                    settings.voiceId?.let { put("voiceId", it) }
                    put("engine", settings.engine.name)
                    put("rate", settings.rate.toDouble())
                    put("pitch", settings.pitch.toDouble())
                    put("autoAdvanceChapter", settings.autoAdvanceChapter)
                    put("continueInBackground", settings.continueInBackground)
                    put("announceChapterTitle", settings.announceChapterTitle)
                    put("bankListeningTowardPace", settings.bankListeningTowardPace)
                    put("sleepMode", settings.sleepMode.name)
                    put("content", encodeContent(settings.content))
                }.toString()
            )
        }
    }

    private fun encodeContent(options: SpeechOptions) = JSONObject().apply {
        put("maxUtteranceCharacters", options.maxUtteranceCharacters)
        put("speakHeadings", options.speakHeadings)
        put("speakCaptions", options.speakCaptions)
        put("speakImageAlt", options.speakImageAlt)
        put("speakCode", options.speakCode)
        put("speakTables", options.speakTables)
        put("speakFootnoteMarkers", options.speakFootnoteMarkers)
    }

    private fun decodeContent(json: JSONObject): SpeechOptions {
        val defaults = SpeechOptions()
        return SpeechOptions(
            maxUtteranceCharacters = json.optInt("maxUtteranceCharacters", defaults.maxUtteranceCharacters),
            speakHeadings = json.optBoolean("speakHeadings", defaults.speakHeadings),
            speakCaptions = json.optBoolean("speakCaptions", defaults.speakCaptions),
            speakImageAlt = json.optBoolean("speakImageAlt", defaults.speakImageAlt),
            speakCode = json.optBoolean("speakCode", defaults.speakCode),
            speakTables = json.optBoolean("speakTables", defaults.speakTables),
            speakFootnoteMarkers = json.optBoolean("speakFootnoteMarkers", defaults.speakFootnoteMarkers)
        )
    }

    /** An enum by name, falling back to [fallback] when the name is absent or no longer exists. */
    private fun <T> JSONObject.enum(key: String, fallback: T, parse: (String) -> T): T {
        val raw = optString(key).takeIf { it.isNotBlank() } ?: return fallback
        return runCatching { parse(raw) }.getOrDefault(fallback)
    }

    private companion object {
        const val FILE_NAME = "speech_settings.json"
    }
}
