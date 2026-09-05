package com.citation.core.speech

/**
 * What the narrator does next, decided without an engine, a service, or a device.
 *
 * The awkward parts of listening to a book are all *between* the sentences: what happens at the end
 * of a chapter, what "skip back" means when you are on the first sentence, whether the sleep timer
 * stops you now or lets this chapter finish. Every one of those is a decision about ordinals and
 * indices — no audio involved — so they live here as pure functions and are held to their edges by
 * tests, instead of being scattered through a service that can only be exercised on a phone.
 */
object Narration {

    /** Where pressing play at [charOffset] begins. */
    fun start(plan: SpeechPlan, charOffset: Int): NarrationStep {
        val index = plan.indexAt(charOffset)
        return if (index >= plan.size) NarrationStep.Stop else NarrationStep.Speak(index)
    }

    /**
     * What follows the unit just finished.
     *
     * The sleep timer is consulted *before* the advance, and honoured two ways: an expired
     * countdown stops the voice wherever it is, while [SleepMode.END_OF_CHAPTER] lets the current
     * chapter finish and refuses to start the next — which is the whole point of choosing it.
     */
    fun after(
        plan: SpeechPlan,
        index: Int,
        chapterCount: Int,
        settings: SpeechSettings,
        sleep: SleepTimerState = SleepTimerState(),
        now: Long = System.currentTimeMillis()
    ): NarrationStep {
        if (SleepTimer.expired(sleep, now)) return NarrationStep.Stop
        val next = index + 1
        if (next < plan.size) return NarrationStep.Speak(next)
        if (sleep.mode == SleepMode.END_OF_CHAPTER) return NarrationStep.Stop
        if (!settings.autoAdvanceChapter) return NarrationStep.Stop
        val following = plan.chapterOrdinal + 1
        return if (following < chapterCount) {
            NarrationStep.ChangeChapter(following, fromEnd = false)
        } else {
            NarrationStep.Stop
        }
    }

    /**
     * Where a skip control lands.
     *
     * Running off either end of a chapter continues into the neighbouring one rather than stopping
     * — a listener pressing "back" on the first sentence means the end of the previous chapter, the
     * same as turning a page backwards. Running off the ends of the *book* stops.
     */
    fun skip(
        plan: SpeechPlan,
        index: Int,
        granularity: SkipGranularity,
        forward: Boolean,
        chapterCount: Int
    ): NarrationStep {
        val target = plan.skip(index, granularity, forward)
        return when {
            target in 0 until plan.size -> NarrationStep.Speak(target)
            forward -> {
                val following = plan.chapterOrdinal + 1
                if (following < chapterCount) NarrationStep.ChangeChapter(following, fromEnd = false)
                else NarrationStep.Stop
            }
            else -> {
                val previous = plan.chapterOrdinal - 1
                if (previous >= 0) NarrationStep.ChangeChapter(previous, fromEnd = true)
                else NarrationStep.Speak(0)
            }
        }
    }
}

/** The narrator's next move. */
sealed class NarrationStep {

    /** Say the unit at this index of the current plan. */
    data class Speak(val index: Int) : NarrationStep()

    /**
     * Load another chapter and carry on.
     *
     * @property fromEnd start at its last unit rather than its first — what skipping backwards past
     *   the top of a chapter means.
     */
    data class ChangeChapter(val ordinal: Int, val fromEnd: Boolean) : NarrationStep()

    /** Stop speaking and release the audio focus; the book keeps its position. */
    object Stop : NarrationStep()
}

/**
 * Everything a listening reader can see, in one value.
 *
 * Both ranges are canonical, so a UI drawing them needs to know nothing about speech: they are the
 * same offsets [com.citation.core.reader.ReadingProgress] counts and notes anchor to, which is what
 * lets the reader light up the sentence being spoken and lets "capture this passage" work while the
 * voice is still talking.
 *
 * @property utteranceRange the sentence being spoken — the read-along highlight. Present for every
 *   engine.
 * @property wordRange the word being spoken, when the engine reports one. Absent for engines that
 *   synthesize a whole sentence at once, which is most neural voices — a caller must treat it as an
 *   enrichment and never as the thing it draws.
 */
data class NarrationState(
    val status: NarrationStatus = NarrationStatus.IDLE,
    val bookKey: String? = null,
    val chapterOrdinal: Int = 0,
    val utteranceIndex: Int = -1,
    val utteranceRange: IntRange? = null,
    val wordRange: IntRange? = null,
    val voiceId: String? = null,
    val rate: Float = 1f,
    /** Milliseconds until the sleep timer stops the voice, or `null` when none is armed. */
    val sleepRemainingMillis: Long? = null
) {
    /** Whether the narrator holds audio focus — speaking now, or about to. */
    val isActive: Boolean
        get() = status == NarrationStatus.SPEAKING || status == NarrationStatus.PREPARING

    /** The offset to write back as reading position: where the voice is, not where the eye was. */
    val charOffset: Int? get() = utteranceRange?.first
}

/** The narrator's coarse state, as a notification and a play button understand it. */
enum class NarrationStatus {
    /** Nothing loaded; no audio focus held. */
    IDLE,

    /** A voice or a chapter is loading. The first neural utterance can take a moment. */
    PREPARING,

    SPEAKING,

    /** Paused by the reader, or ducked out by another app that took focus. */
    PAUSED,

    /**
     * The engine failed and the narrator gave up on this run — no voice installed, a model that
     * would not load, an engine that died mid-sentence. The reader keeps its position.
     */
    FAILED
}
