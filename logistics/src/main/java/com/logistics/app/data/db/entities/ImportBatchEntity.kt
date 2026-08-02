package com.logistics.app.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/** One import run's provenance. See [com.logistics.app.data.model.ImportBatch]. */
@Entity(tableName = "import_batches")
data class ImportBatchEntity(
    @PrimaryKey val id: String,
    val source: String,
    val orderNumber: String?,
    val itemCount: Int,
    val label: String,
    val createdAt: String
)
