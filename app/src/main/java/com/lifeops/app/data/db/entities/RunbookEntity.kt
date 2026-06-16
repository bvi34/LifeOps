package com.lifeops.app.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "runbooks")
data class RunbookEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: String
)
