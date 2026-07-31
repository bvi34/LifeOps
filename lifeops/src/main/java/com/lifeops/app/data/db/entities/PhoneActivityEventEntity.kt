package com.lifeops.app.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One raw phone-activity signal, captured the instant it happens by [com.lifeops.app.receiver.PhoneActivityReceiver]
 * (screen on/off, charger connected/disconnected). These are the source material the overnight sleep
 * reconstruction ([com.lifeops.app.util.SleepInferenceService]) reads back — screen-off stretches are
 * candidate sleep, and charging events corroborate bedtime. Deliberately dumb and append-only: every
 * event is persisted immediately with no interpretation, so the inference can be re-run/tuned later.
 *
 * [occurredAt] is epoch millis (the inference works entirely in millis); [dayKey] is the local ISO
 * date of the event, used only to prune old rows. Standalone log table, no FK — same shape as
 * counter_events / game_scores. [type] holds a [com.lifeops.app.data.model.PhoneActivityType] value.
 */
@Entity(
    tableName = "phone_activity_events",
    indices = [
        Index("occurredAt"),
        Index("dayKey")
    ]
)
data class PhoneActivityEventEntity(
    @PrimaryKey val id: String,
    val type: String,
    val occurredAt: Long,
    val dayKey: String
)
