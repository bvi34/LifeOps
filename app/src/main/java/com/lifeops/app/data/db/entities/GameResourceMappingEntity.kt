package com.lifeops.app.data.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "game_resource_mappings",
    foreignKeys = [
        ForeignKey(
            entity = GameResourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["gameResourceId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = AspectEntity::class,
            parentColumns = ["id"],
            childColumns = ["aspectId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("gameResourceId"), Index("aspectId")]
)
data class GameResourceMappingEntity(
    @PrimaryKey val id: String,
    val gameResourceId: String,
    val aspectId: String,
    val weight: Float = 1.0f
)
