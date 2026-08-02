package com.logistics.app.data.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The stock ledger. Deleting a pantry item cascades away its transactions (the row is gone; its
 * history goes with it). [recipeId]/[importBatchId] are soft references (LifeOps recipe id; a local
 * import batch id) so they're kept as plain strings, not FKs.
 */
@Entity(
    tableName = "pantry_txns",
    foreignKeys = [
        ForeignKey(
            entity = PantryItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["pantryItemId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("pantryItemId"), Index("reason"), Index("mealName")]
)
data class PantryTxnEntity(
    @PrimaryKey val id: String,
    val pantryItemId: String,
    val delta: Double,
    val unit: String,
    val reason: String,
    val mealName: String?,
    val recipeId: String?,
    val importBatchId: String?,
    val note: String?,
    val createdAt: String
)
