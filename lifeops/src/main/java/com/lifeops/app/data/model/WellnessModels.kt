package com.lifeops.app.data.model



/**
 * How the week actually went for the person living it: the check-ins, the trends read out of
 * them, and the phone activity that is one more input.
 */

/** Whether a wellness data point is a daytime check-in or a morning sleep report. */
enum class WellnessKind(val value: String) {
    CHECKIN("CHECKIN"),
    SLEEP("SLEEP");
    companion object { fun from(value: String?) = entries.firstOrNull { it.value == value } ?: CHECKIN }
}

/**
 * How a check-in compares with the reading before it — the relative answer that replaced
 * re-scoring energy and sensory load from scratch at every prompt. [step] is how far it moves the
 * previous energy reading on the 1–10 scale when no exact rating was given
 * (WellnessRepository.logCheckin).
 */
enum class WellnessTrend(val value: String, val step: Int, val label: String) {
    BETTER("BETTER", 1, "Better"),
    SAME("SAME", 0, "Same"),
    WORSE("WORSE", -1, "Worse");

    /**
     * Where this answer lands on the 1–10 energy scale, measured against [previousEnergy]: one step
     * up, level, or one step down, clamped to the scale. With nothing to measure against — a fresh
     * install, or the first check-in ever — [NEUTRAL_ENERGY] stands in for the middle of the scale.
     */
    fun energyFrom(previousEnergy: Int?): Int =
        ((previousEnergy ?: NEUTRAL_ENERGY) + step).coerceIn(1, 10)

    companion object {
        /** Middle of the 1–10 scale: the anchor when there is no previous reading at all. */
        const val NEUTRAL_ENERGY = 5

        fun from(value: String?) = entries.firstOrNull { it.value == value }
    }
}

/**
 * How sensory load compares with the reading before it — the same relative answer [WellnessTrend]
 * gives for the overall state, asked separately because the two genuinely come apart: a good day
 * can still be a loud one. [step] moves the previous sensory reading on the 1–10 scale, and it runs
 * the *opposite* way to [WellnessTrend.step] because that scale climbs into overload (1 calm → 10
 * overloaded) — "better" means less loaded, so it steps down.
 */
enum class SensoryTrend(val value: String, val step: Int, val label: String) {
    BETTER("BETTER", -1, "Better"),
    NEUTRAL("NEUTRAL", 0, "Neutral"),
    WORSE("WORSE", 1, "Worse");

    /**
     * Where this answer lands on the 1–10 sensory scale, measured against [previousSensory]: one
     * step calmer, level, or one step more overloaded, clamped to the scale. With nothing to
     * measure against, [NEUTRAL_SENSORY] stands in for the middle.
     */
    fun sensoryFrom(previousSensory: Int?): Int =
        ((previousSensory ?: NEUTRAL_SENSORY) + step).coerceIn(1, 10)

    companion object {
        /** Middle of the 1–10 scale: the anchor when there is no previous reading at all. */
        const val NEUTRAL_SENSORY = 5

        fun from(value: String?) = entries.firstOrNull { it.value == value }
    }
}

/**
 * Desire to do things at the moment of a check-in. [score] maps it to -1..+1 so runs of check-ins
 * average into a single "leaning yes / mixed / leaning no" number for the reports.
 */
enum class Initiative(val value: String, val score: Int, val label: String) {
    YES("YES", 1, "Yes"),
    NEUTRAL("NEUTRAL", 0, "Neutral"),
    NO("NO", -1, "No");
    companion object { fun from(value: String?) = entries.firstOrNull { it.value == value } }
}

/**
 * Domain view of a WellnessCheckinEntity. See the entity KDoc for how the two [kind]s share one
 * shape. For CHECKIN rows: [trend], [sensoryTrend] and [initiative] are what the user actually
 * answered, and [energy]/[sensory] are derived from the two trends ([energyDerived] /
 * [sensoryDerived] true) unless they opened the optional exact ratings. For SLEEP rows only: [sleepMinutes] is the reconstructed total sleep, and the
 * [sleepBedtime]/[sleepWakeTime]/[sleepInterruptions]/[longestSleepMinutes] fields hold the rest of
 * the overnight reconstruction (see [com.lifeops.app.util.SleepInferenceService]) when the raw
 * phone-activity events supported one; they stay null on hand-entered or estimate-only reports.
 */
data class WellnessCheckin(
    val id: String,
    val kind: WellnessKind,
    val recordedAt: String,
    val weekKey: Int,
    val dayKey: String,
    val energy: Int? = null,
    val sensory: Int? = null,
    val trend: WellnessTrend? = null,
    val sensoryTrend: SensoryTrend? = null,
    val initiative: Initiative? = null,
    val energyDerived: Boolean = false,
    val sensoryDerived: Boolean = false,
    val tired: Int? = null,
    val sleepMinutes: Int? = null,
    val note: String? = null,
    val sleepBedtime: String? = null,
    val sleepWakeTime: String? = null,
    val sleepInterruptions: Int? = null,
    val longestSleepMinutes: Int? = null
)

/** The raw device signals sleep reconstruction is built from. Stored as [value] in phone_activity_events.type. */
enum class PhoneActivityType(val value: String) {
    SCREEN_ON("SCREEN_ON"),
    SCREEN_OFF("SCREEN_OFF"),
    CHARGING_START("CHARGING_START"),
    CHARGING_STOP("CHARGING_STOP");

    companion object {
        fun from(value: String?): PhoneActivityType? = entries.firstOrNull { it.value == value }
    }
}

/** Domain view of a PhoneActivityEventEntity: one timestamped device signal. */
data class PhoneActivityEvent(
    val id: String,
    val type: PhoneActivityType,
    val occurredAt: Long,
    val dayKey: String
)
