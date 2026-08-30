package com.lifeops.app.data.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A single wellness data point. Two shapes share one table, distinguished by [kind]:
 *
 * - `CHECKIN` (throughout the day, at ~10:00/15:00/21:00, on app open, or right after a habit is
 *   ticked): a relative [trend] (better/same/worse than the last reading), a relative
 *   [sensoryTrend] (better/neutral/worse sensory load), and [initiative] (yes/neutral/no — the
 *   desire to do things), plus a free-text [note] ("why"). [energy] and [sensory] hold the exact
 *   1–10 numbers when the user opened the optional exact ratings; otherwise each is stepped from
 *   the previous reading by its trend and flagged with [energyDerived] / [sensoryDerived], so the
 *   1–10 series the reports read stays continuous without asking for the same numbers three times
 *   a day.
 * - `SLEEP` (the first app open after 5am): estimated [sleepMinutes] (from screen time — last
 *   phone use → now), how [tired] and how much [energy] on waking, plus the [note].
 *
 * Unused columns for a given kind are simply null (a CHECKIN has no [tired]/[sleepMinutes]; a
 * SLEEP row has no [sensory]/[trend]/[sensoryTrend]/[initiative], and CHECKIN rows written before
 * the relative redesign have no [trend]/[initiative] either, just as those written before the
 * sensory question became relative have no [sensoryTrend]). Standalone log table — no foreign keys, same
 * shape as game_scores and counters. [weekKey] is stamped from [recordedAt] via
 * DateUtil.weekIndexFor so weekly rollups agree with the rest of the app; [dayKey] is the local
 * ISO date used for daily grouping and for the "already logged today?" checks that gate the
 * pop-ups.
 *
 * SLEEP rows may also carry the overnight reconstruction ([SleepInferenceService]): [sleepBedtime]
 * and [sleepWakeTime] are ISO instants, [sleepInterruptions] is the count of ≥60s wake-ups during
 * the night, and [longestSleepMinutes] is the longest uninterrupted stretch. All four stay null
 * when the report was hand-entered or only screen-time-estimated.
 */
@Entity(
    tableName = "wellness_checkins",
    indices = [
        Index("weekKey"),
        Index("recordedAt"),
        Index("dayKey")
    ]
)
data class WellnessCheckinEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val recordedAt: String,
    val weekKey: Int,
    val dayKey: String,
    val energy: Int? = null,
    val sensory: Int? = null,
    /** CHECKIN only: "BETTER" / "SAME" / "WORSE" vs. the previous reading. See WellnessTrend. */
    val trend: String? = null,
    /** CHECKIN only: "BETTER" / "NEUTRAL" / "WORSE" sensory load vs. the previous reading. See SensoryTrend. */
    val sensoryTrend: String? = null,
    /** CHECKIN only: "YES" / "NEUTRAL" / "NO" — desire to do things. See Initiative. */
    val initiative: String? = null,
    // True when [energy] was stepped from the previous reading by [trend] rather than entered by
    // hand. Room needs the Kotlin default and a matching @ColumnInfo(defaultValue) so the additive
    // migration passes schema validation.
    @ColumnInfo(defaultValue = "0")
    val energyDerived: Boolean = false,
    /** The same, for a [sensory] stepped from [sensoryTrend] rather than typed in. */
    @ColumnInfo(defaultValue = "0")
    val sensoryDerived: Boolean = false,
    val tired: Int? = null,
    val sleepMinutes: Int? = null,
    val note: String? = null,
    val sleepBedtime: String? = null,
    val sleepWakeTime: String? = null,
    val sleepInterruptions: Int? = null,
    val longestSleepMinutes: Int? = null
)
