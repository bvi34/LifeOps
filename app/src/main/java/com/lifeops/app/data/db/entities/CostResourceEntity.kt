package com.lifeops.app.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "cost_resources")
data class CostResourceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val resetCycle: String = "monthly",
    val capacity: Int? = null,
    val isActive: Boolean = true,
    val sortIndex: Int = 0,
    val createdAt: String
)
