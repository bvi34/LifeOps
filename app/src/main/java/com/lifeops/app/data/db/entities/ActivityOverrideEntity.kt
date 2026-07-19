package com.lifeops.app.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A logged manual override of an activity's default (Phase 5 learning). Standalone table — no FK to
 * activity_templates, so a deleted activity's history lingers harmlessly and is simply ignored by
 * the learning engine (which only suggests for templates that still exist). Indexed by activityId.
 */
@Entity(
    tableName = "activity_overrides",
    indices = [Index("activityId")]
)
data class ActivityOverrideEntity(
    @PrimaryKey val id: String,
    val activityId: String,
    val field: String,
    val templateValue: Int?,
    val userValue: Int?,
    val createdAt: String
)
