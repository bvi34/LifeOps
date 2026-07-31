package com.lifeops.app.data.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "resource_transactions",
    foreignKeys = [ForeignKey(
        entity = GameResourceEntity::class,
        parentColumns = ["id"],
        childColumns = ["resourceId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("resourceId")]
)
data class ResourceTransactionEntity(
    @PrimaryKey val id: String,
    val resourceId: String,
    val amount: Int,
    val type: String,
    val note: String? = null,
    val createdAt: String
)
