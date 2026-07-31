package com.lifeops.app.data.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "cost_resources")
data class CostResourceEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(defaultValue = "'monthly'")
    val resetCycle: String = "monthly",
    val capacity: Int? = null,
    @ColumnInfo(defaultValue = "1")
    val isActive: Boolean = true,
    @ColumnInfo(defaultValue = "0")
    val sortIndex: Int = 0,
    val createdAt: String
)
