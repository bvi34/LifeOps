package com.logistics.app.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.logistics.app.data.db.entities.GroceryItemEntity
import com.logistics.app.data.db.entities.ImportBatchEntity
import com.logistics.app.data.db.entities.PantryItemEntity
import com.logistics.app.data.db.entities.PantryTxnEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PantryDao {

    // --- pantry items ---
    @Query("SELECT * FROM pantry_items ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<PantryItemEntity>>

    @Query("SELECT * FROM pantry_items ORDER BY name COLLATE NOCASE")
    suspend fun getAll(): List<PantryItemEntity>

    @Query("SELECT * FROM pantry_items WHERE id = :id")
    suspend fun getById(id: String): PantryItemEntity?

    @Query("SELECT * FROM pantry_items WHERE foodItemId = :foodItemId LIMIT 1")
    suspend fun getByFoodItemId(foodItemId: String): PantryItemEntity?

    /** Case-insensitive exact-name lookup, so re-importing the same product tops up the same row
     *  instead of creating a duplicate. */
    @Query("SELECT * FROM pantry_items WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun getByName(name: String): PantryItemEntity?

    @Upsert
    suspend fun upsert(item: PantryItemEntity)

    @Query("DELETE FROM pantry_items WHERE id = :id")
    suspend fun delete(id: String)

    // --- ledger ---
    @Upsert
    suspend fun upsertTxn(txn: PantryTxnEntity)

    @Query("SELECT * FROM pantry_txns WHERE pantryItemId = :itemId ORDER BY createdAt DESC")
    fun observeTxnsForItem(itemId: String): Flow<List<PantryTxnEntity>>

    @Query("SELECT * FROM pantry_txns WHERE reason = 'consume' ORDER BY createdAt DESC")
    fun observeConsumption(): Flow<List<PantryTxnEntity>>

    @Query("SELECT * FROM pantry_txns ORDER BY createdAt DESC")
    suspend fun getAllTxns(): List<PantryTxnEntity>

    // --- import batches ---
    @Upsert
    suspend fun upsertBatch(batch: ImportBatchEntity)

    @Query("SELECT * FROM import_batches ORDER BY createdAt DESC")
    fun observeBatches(): Flow<List<ImportBatchEntity>>

    @Query("SELECT * FROM import_batches ORDER BY createdAt DESC")
    suspend fun getAllBatches(): List<ImportBatchEntity>

    // --- grocery list ---
    // Unchecked first, then by category/name, so the "still to get" items sit at the top and the
    // ticked-off ones sink to the bottom of each category as you shop.
    @Query("SELECT * FROM grocery_items ORDER BY checked ASC, category COLLATE NOCASE, name COLLATE NOCASE")
    fun observeGrocery(): Flow<List<GroceryItemEntity>>

    @Query("SELECT * FROM grocery_items")
    suspend fun getAllGrocery(): List<GroceryItemEntity>

    @Query("SELECT * FROM grocery_items WHERE checked = 1")
    suspend fun getCheckedGrocery(): List<GroceryItemEntity>

    @Query("SELECT * FROM grocery_items WHERE id = :id")
    suspend fun getGroceryById(id: String): GroceryItemEntity?

    /** Case-insensitive exact-name match so adding the same product twice (or re-running a source)
     *  tops up the existing line instead of duplicating it. */
    @Query("SELECT * FROM grocery_items WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun getGroceryByName(name: String): GroceryItemEntity?

    @Upsert
    suspend fun upsertGrocery(item: GroceryItemEntity)

    @Query("DELETE FROM grocery_items WHERE id = :id")
    suspend fun deleteGrocery(id: String)

    @Query("DELETE FROM grocery_items WHERE checked = 1")
    suspend fun deleteCheckedGrocery()

    @Query("DELETE FROM grocery_items")
    suspend fun clearGrocery()
}
