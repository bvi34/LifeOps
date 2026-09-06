package com.citation.app.audio

import com.citation.core.speech.Utterance

/**
 * The seam between a planned book and a voice.
 *
 * Everything above this line — what to say, in what order, with what pauses, and where the reader is
 * while it happens — lives in `:core` and knows nothing about audio. Everything below it is one
 * engine's business. The interface is deliberately narrow: say this one unit, tell me when you are
 * done, and tell me the words as you pass them if you can. An engine that can do no more than that
 * is enough to read a book aloud.
 *
 * That narrowness is what makes the neural track affordable. The platform engine and a downloaded
 * ONNX voice are the same twenty lines of contract; swapping one for the other changes no planning,
 * no position tracking, no controls, and no UI.
 */
interface SpeechEngine {

    /** Stable identifier, for state and for telling the reader which voice they are hearing. */
    val id: String

    /** A human name for the voice currently loaded — shown in the player. */
    val voiceLabel: String?

    /**
     * Whether [speak] reports word positions as it passes them.
     *
     * The platform engine does; most neural voices synthesize a whole sentence at once and cannot.
     * Callers use it to choose between a word-level and a sentence-level read-along highlight, and
     * must never depend on the callback arriving.
     */
    val reportsWordBoundaries: Boolean

    /**
     * Get ready to speak — bind the platform engine, or load a voice model into memory.
     *
     * Returns `false` for an engine that cannot run here rather than throwing: no voice installed,
     * a model that will not load, a platform engine with no data downloaded. The narrator answers a
     * `false` by trying the next engine, which is how a reader who has installed nothing still hears
     * their book.
     */
    suspend fun prepare(): Boolean

    /**
     * Say one unit, suspending until it is finished, stopped, or has failed.
     *
     * @param volume `0f`..`1f`, applied on top of the system volume — the sleep timer's fade rides
     *   in on this rather than touching the reader's own volume setting.
     * @param onWord canonical ranges as the voice passes them, on an arbitrary thread, only from
     *   engines where [reportsWordBoundaries] is true.
     */
    suspend fun speak(
        utterance: Utterance,
        rate: Float,
        pitch: Float,
        volume: Float,
        onWord: (IntRange) -> Unit
    ): SpeechResult

    /** Cut the current utterance short. A [speak] in flight completes with [SpeechResult.Stopped]. */
    fun stop()

    /** Give back the engine and any memory a voice model is holding. */
    fun release()
}

/** How one utterance ended. */
sealed class SpeechResult {

    /** Spoken through to its end. */
    object Spoken : SpeechResult()

    /** Cut short by [SpeechEngine.stop] — a pause, a skip, or the reader closing the book. */
    object Stopped : SpeechResult()

    /**
     * The engine failed on this unit. Carries the reason for the player to show, because "the book
     * stopped talking" with no explanation is the worst version of this failure.
     */
    data class Failed(val reason: String) : SpeechResult()
}
