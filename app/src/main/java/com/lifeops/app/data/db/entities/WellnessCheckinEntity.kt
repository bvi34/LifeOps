package com.lifeops.app.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A single wellness data point. Two shapes share one table, distinguished by [kind]:
 *
 * - `CHECKIN` (throughout the day, at ~10:00/15:00/21:00 or on app open): [energy] + [sensory]
 *   load, plus a free-text [note] ("why").
 * - `SLEEP` (the first app open after 5am): estimated [sleepMinutes] (from screen time — last
 *   phone use → now), how [tired] and how much [energy] on waking, plus the [note].
 *
 * Unused columns for a given kind are simply null (a CHECKIN has no [tired]/[sleepMinutes]; a
 * SLEEP row has no [sensory]). Standalone log table — no foreign keys, same shape as game_scores
 * and counters. [weekKey] is stamped from [recordedAt] via DateUtil.weekIndexFor so weekly rollups
 * agree with the rest of the app; [dayKey] is the local ISO date used for daily grouping and for
 * the "already logged today?" checks that gate the pop-ups.
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
    val tired: Int? = null,
    val sleepMinutes: Int? = null,
    val note: String? = null
)
