package com.lifeops.app.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "weeks")
data class WeekEntity(
    @PrimaryKey val id: String,
    val startDate: String,
    val endDate: String,
    val isClosed: Boolean = false,
    val closedAt: String? = null,
    /**
     * Set when the week was written off by "Reset to current week" — a skipped week. It always
     * draws as a scar on the Growth Record, with this reason beside it. Null on an ordinary week.
     */
    val skipReason: String? = null,
    /** When the reset marked this week skipped; null on an ordinary week. */
    val skippedAt: String? = null,
    /** The reset was a *true* reset: weekly / repeating tasks were set aside for it. */
    val skipTrueReset: Boolean? = null
)
