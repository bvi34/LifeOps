package com.lifeops.app.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "game_resources")
data class GameResourceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val currentValue: Int = 0,
    val lifetimeEarned: Int = 0,
    val slotIndex: Int
)
