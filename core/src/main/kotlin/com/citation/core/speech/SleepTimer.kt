package com.citation.core.speech

/**
 * The sleep timer — the one control that is only ever used by somebody who will not be awake to
 * correct it.
 *
 * That is the whole design constraint, and it explains the two things here that a naive countdown
 * would not have. It **fades** rather than cutting out, because a voice that stops mid-word wakes
 * the person it was putting to sleep. And it can be set to *end of chapter* rather than a duration,
 * because "finish this bit and stop" is what people actually mean and a clock cannot express it.
 *
 * Pure, with an injected clock, following [com.citation.core.reader.ReadingMeter] — the accuracy of
 * something that fires once an hour after you have stopped watching it is exactly what you cannot
 * check by hand.
 */
object SleepTimer {

    /** Arm [mode] as of [now]; [SleepMode.OFF] and [SleepMode.END_OF_CHAPTER] have no deadline. */
    fun arm(mode: SleepMode, now: Long = System.currentTimeMillis()): SleepTimerState =
        SleepTimerState(mode, mode.durationMillis?.let { now + it })

    /** Milliseconds left, or `null` when nothing is counting down. */
    fun remaining(state: SleepTimerState, now: Long = System.currentTimeMillis()): Long? =
        state.expiresAt?.let { (it - now).coerceAtLeast(0) }

    /** Whether the countdown has run out. An unarmed or end-of-chapter timer never has. */
    fun expired(state: SleepTimerState, now: Long = System.currentTimeMillis()): Boolean =
        state.expiresAt != null && now >= state.expiresAt

    /**
     * Push the deadline out by [millis] — the "still awake" gesture.
     *
     * Extending an *expired* timer restarts it from now rather than from the moment it lapsed, so a
     * reader who reaches for the button a minute late gets the whole extension they asked for. An
     * unarmed timer stays unarmed: there is nothing to extend.
     */
    fun extend(
        state: SleepTimerState,
        millis: Long,
        now: Long = System.currentTimeMillis()
    ): SleepTimerState {
        val expiresAt = state.expiresAt ?: return state
        return state.copy(expiresAt = maxOf(expiresAt, now) + millis)
    }

    /** Cancel it. */
    fun cancel(): SleepTimerState = SleepTimerState()

    /** Whether the voice should stop when the current chapter ends. */
    fun stopsAtChapterEnd(state: SleepTimerState): Boolean = state.mode == SleepMode.END_OF_CHAPTER

    /**
     * The volume to speak at right now, `0f`..`1f`.
     *
     * Full volume until the last [fadeMillis], then a linear taper to silence. Linear in amplitude
     * is deliberately *not* linear in perceived loudness — it sounds like someone walking away with
     * the book, which is the effect wanted.
     */
    fun volumeScale(
        state: SleepTimerState,
        now: Long = System.currentTimeMillis(),
        fadeMillis: Long = DEFAULT_FADE_MILLIS
    ): Float {
        val left = remaining(state, now) ?: return 1f
        if (fadeMillis <= 0L || left >= fadeMillis) return 1f
        return (left.toFloat() / fadeMillis).coerceIn(0f, 1f)
    }

    /** "25 min", "40 sec", or `null` — the label a sheet puts under an armed timer. */
    fun label(remainingMillis: Long?): String? {
        val millis = remainingMillis ?: return null
        val seconds = millis / 1000
        return when {
            seconds >= 90 -> "${(seconds + 30) / 60} min"
            seconds > 0 -> "$seconds sec"
            else -> null
        }
    }

    /** Twenty seconds of fade: long enough to be gentle, short enough not to lose a paragraph. */
    const val DEFAULT_FADE_MILLIS = 20_000L
}

/**
 * An armed (or unarmed) sleep timer.
 *
 * @property expiresAt wall-clock deadline, or `null` for [SleepMode.OFF] and
 *   [SleepMode.END_OF_CHAPTER] — neither of which counts down.
 */
data class SleepTimerState(
    val mode: SleepMode = SleepMode.OFF,
    val expiresAt: Long? = null
) {
    val isArmed: Boolean get() = mode != SleepMode.OFF
}

/** How long the voice keeps going. */
enum class SleepMode(val durationMillis: Long?) {
    OFF(null),
    MINUTES_5(5 * 60_000L),
    MINUTES_15(15 * 60_000L),
    MINUTES_30(30 * 60_000L),
    MINUTES_45(45 * 60_000L),
    MINUTES_60(60 * 60_000L),

    /** Finish the chapter being spoken, then stop, however long that takes. */
    END_OF_CHAPTER(null);

    /** "30 min" / "End of chapter" / "Off". */
    val label: String
        get() = when (this) {
            OFF -> "Off"
            END_OF_CHAPTER -> "End of chapter"
            else -> "${(durationMillis ?: 0L) / 60_000L} min"
        }
}
