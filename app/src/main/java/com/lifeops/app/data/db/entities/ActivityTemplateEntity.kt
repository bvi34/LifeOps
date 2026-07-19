package com.lifeops.app.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A reusable saved activity with default weather requirements (Phase 4). Standalone table — no FKs
 * — so it's purely additive. Built-ins are seeded once on first launch ([isBuiltIn] = true) but are
 * ordinary editable/deletable rows; custom activities are the same shape with [isBuiltIn] = false.
 */
@Entity(tableName = "activity_templates")
data class ActivityTemplateEntity(
    @PrimaryKey val id: String,
    val name: String,
    val outdoorPreferred: Boolean,
    val durationMinutes: Int?,
    val maxTempF: Int?,
    val minTempF: Int?,
    val avoidRain: Boolean,
    val maxWindMph: Int?,
    val isBuiltIn: Boolean,
    val sortOrder: Int,
    val createdAt: String
)
