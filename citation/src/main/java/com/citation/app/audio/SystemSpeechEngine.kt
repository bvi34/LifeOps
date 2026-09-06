package com.citation.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.citation.core.speech.Utterance
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

/**
 * The platform's own text-to-speech, wrapped to the [SpeechEngine] contract.
 *
 * It ships with the phone, costs nothing, needs no download, and — alone among the options — tells
 * you which word it is saying while it says it. `onRangeStart` arrived in API 26, which is exactly
 * Citation's minimum, so word-level read-along is available on every device the app runs on.
 *
 * Its weakness is the obvious one: it sounds like a phone. It is here as the engine that always
 * works, so that listening is never gated behind a sixty-megabyte download, and as the one to pick
 * deliberately when a word-by-word highlight matters more than the voice.
 *
 * Two details are load-bearing and easy to get wrong. Ranges arrive as offsets into the string
 * *handed to the engine*, which is not the chapter's text — [Utterance.canonicalRange] is what turns
 * them back into offsets the reader can draw. And the progress listener is per-engine rather than
 * per-call, so callbacks are routed by utterance id to whichever call is in flight; a stale callback
 * from an utterance already cancelled is dropped rather than resuming the wrong continuation.
 */
class SystemSpeechEngine(
    private val context: Context,
    private val language: String? = null
) : SpeechEngine {

    override val id: String = ID

    override val reportsWordBoundaries: Boolean = true

    override val voiceLabel: String?
        get() = tts?.voice?.name

    private var tts: TextToSpeech? = null
    private val ids = AtomicLong(0)

    /** The call currently being spoken. Read from the engine's callback threads. */
    @Volatile
    private var pending: Pending? = null

    override suspend fun prepare(): Boolean {
        tts?.let { return true }
        val engine = suspendCancellableCoroutine<TextToSpeech?> { continuation ->
            var instance: TextToSpeech? = null
            instance = TextToSpeech(context) { status ->
                if (continuation.isActive) {
                    continuation.resume(if (status == TextToSpeech.SUCCESS) instance else null)
                }
            }
            continuation.invokeOnCancellation { instance?.shutdown() }
        } ?: return false

        engine.setAudioAttributes(
            AudioAttributes.Builder()
                // Media, not assistant: an audiobook belongs on the media stream, pauses when the
                // user pauses media, and routes to the car and the headphones like one.
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        engine.setOnUtteranceProgressListener(listener)
        language?.let { tag ->
            val locale = runCatching { Locale.forLanguageTag(tag) }.getOrNull()
            if (locale != null) {
                val result = engine.setLanguage(locale)
                // Missing language data is not fatal: the engine keeps its default voice and the
                // book is read in it, which beats refusing to read at all.
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    engine.setLanguage(Locale.getDefault())
                }
            }
        }
        tts = engine
        return true
    }

    override suspend fun speak(
        utterance: Utterance,
        rate: Float,
        pitch: Float,
        volume: Float,
        onWord: (IntRange) -> Unit
    ): SpeechResult {
        val engine = tts ?: return SpeechResult.Failed("Speech engine unavailable")
        engine.setSpeechRate(rate)
        engine.setPitch(pitch)
        val utteranceId = ids.incrementAndGet().toString()

        return suspendCancellableCoroutine { continuation ->
            val call = Pending(utteranceId, utterance, onWord) { result ->
                if (continuation.isActive) continuation.resume(result)
            }
            pending = call
            continuation.invokeOnCancellation {
                if (pending === call) pending = null
                engine.stop()
            }
            val params = Bundle().apply {
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume.coerceIn(0f, 1f))
            }
            val queued = engine.speak(utterance.spoken, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
            if (queued != TextToSpeech.SUCCESS) call.finish(SpeechResult.Failed("Engine refused the utterance"))
        }
    }

    override fun stop() {
        tts?.stop()
    }

    override fun release() {
        pending = null
        tts?.shutdown()
        tts = null
    }

    /** The voices the platform engine offers, for a picker that lists them beside the neural ones. */
    fun installedVoices(): List<String> =
        runCatching { tts?.voices?.map { it.name }?.sorted().orEmpty() }.getOrDefault(emptyList())

    private val listener = object : UtteranceProgressListener() {

        override fun onStart(utteranceId: String?) = Unit

        override fun onDone(utteranceId: String?) {
            pending?.takeIf { it.id == utteranceId }?.finish(SpeechResult.Spoken)
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            pending?.takeIf { it.id == utteranceId }?.finish(SpeechResult.Stopped)
        }

        @Deprecated("Superseded by onError(String, int); the base class still requires it.")
        override fun onError(utteranceId: String?) {
            pending?.takeIf { it.id == utteranceId }?.finish(SpeechResult.Failed("Speech failed"))
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            pending?.takeIf { it.id == utteranceId }
                ?.finish(SpeechResult.Failed("Speech failed (code $errorCode)"))
        }

        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
            val call = pending?.takeIf { it.id == utteranceId } ?: return
            // Offsets into the string we handed the engine — translated back to the chapter's own.
            call.utterance.canonicalRange(start, end)?.let(call.onWord)
        }
    }

    /**
     * One in-flight utterance. [finish] is guarded so the first outcome wins: an engine that reports
     * both an error and a done for the same id must not resume a continuation twice.
     */
    private class Pending(
        val id: String,
        val utterance: Utterance,
        val onWord: (IntRange) -> Unit,
        private val resume: (SpeechResult) -> Unit
    ) {
        private val settled = AtomicBoolean(false)

        fun finish(result: SpeechResult) {
            if (settled.compareAndSet(false, true)) resume(result)
        }
    }

    companion object {
        const val ID = "system"
    }
}
