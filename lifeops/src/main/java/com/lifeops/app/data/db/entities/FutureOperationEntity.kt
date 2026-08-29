package com.lifeops.app.data.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "future_operations")
data class FutureOperationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val content: String,
    val createdAt: String,
    val updatedAt: String,
    @ColumnInfo(defaultValue = "'active'")
    val status: String = "active"
)
