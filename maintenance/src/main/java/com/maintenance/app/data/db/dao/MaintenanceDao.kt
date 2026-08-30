package com.maintenance.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import com.maintenance.app.data.db.entities.AssetAttributeEntity
import com.maintenance.app.data.db.entities.AssetEntity
import com.maintenance.app.data.db.entities.CoverageEntity
import com.maintenance.app.data.db.entities.LoanEntity
import com.maintenance.app.data.db.entities.MeterReadingEntity
import com.maintenance.app.data.db.entities.ServiceRecordEntity
import com.maintenance.app.data.db.entities.UpkeepPlanEntity
import kotlinx.coroutines.flow.Flow

/**
 * Maintenance's one DAO.
 *
 * One interface rather than seven, because every screen in the app crosses tables. The docket needs
 * plans, readings and coverages against the assets they hang off; an asset's page needs all six of
 * its children at once. Splitting this per table would mean a repository stitching six flows
 * together for every screen anyway — which is exactly what it does, once, below.
 *
 * The `observeAll*` reads deliberately fetch **every** row rather than filtering per asset. A
 * household has a dozen assets and a few hundred rows in total; the docket is a fold over all of
 * them, and asking per asset would be a query per asset per table. The honest simple thing is also
 * the fast one at this size.
 *
 * Ordering is always `(<field>, <name>)` rather than a single column: rows restored from a backup
 * can share a position, and a list that reorders itself between two identical reads is the kind of
 * bug nobody reproduces on purpose.
 */
@Dao
interface MaintenanceDao {

    // --- assets ---

    @Query("SELECT * FROM assets ORDER BY archived, sortOrder, name COLLATE NOCASE")
    fun observeAssets(): Flow<List<AssetEntity>>

    @Query("SELECT * FROM assets WHERE id = :id")
    fun observeAsset(id: String): Flow<AssetEntity?>

    @Query("SELECT * FROM assets WHERE id = :id")
    suspend fun getAsset(id: String): AssetEntity?

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM assets")
    suspend fun nextAssetSortOrder(): Int

    @Upsert
    suspend fun upsertAsset(asset: AssetEntity)

    /** Everything under the asset cascades; only the row itself has to be named. */
    @Query("DELETE FROM assets WHERE id = :id")
    suspend fun deleteAsset(id: String)

    // --- attributes ---

    @Query("SELECT * FROM asset_attributes")
    fun observeAttributes(): Flow<List<AssetAttributeEntity>>

    @Query("SELECT * FROM asset_attributes WHERE assetId = :assetId")
    fun observeAttributesFor(assetId: String): Flow<List<AssetAttributeEntity>>

    @Upsert
    suspend fun upsertAttributes(attributes: List<AssetAttributeEntity>)

    /** Clearing a field deletes the row: an empty string is not a value anybody wants back. */
    @Query("DELETE FROM asset_attributes WHERE assetId = :assetId AND key IN (:keys)")
    suspend fun deleteAttributes(assetId: String, keys: List<String>)

    // --- upkeep plans ---

    @Query("SELECT * FROM upkeep_plans ORDER BY sortOrder, title COLLATE NOCASE")
    fun observePlans(): Flow<List<UpkeepPlanEntity>>

    @Query("SELECT * FROM upkeep_plans WHERE assetId = :assetId ORDER BY sortOrder, title COLLATE NOCASE")
    fun observePlansFor(assetId: String): Flow<List<UpkeepPlanEntity>>

    @Query("SELECT * FROM upkeep_plans WHERE id = :id")
    suspend fun getPlan(id: String): UpkeepPlanEntity?

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM upkeep_plans WHERE assetId = :assetId")
    suspend fun nextPlanSortOrder(assetId: String): Int

    @Upsert
    suspend fun upsertPlan(plan: UpkeepPlanEntity)

    @Query("DELETE FROM upkeep_plans WHERE id = :id")
    suspend fun deletePlan(id: String)

    // --- service records ---

    @Query("SELECT * FROM service_records ORDER BY performedAt DESC, title COLLATE NOCASE")
    fun observeRecords(): Flow<List<ServiceRecordEntity>>

    @Query("SELECT * FROM service_records WHERE assetId = :assetId ORDER BY performedAt DESC, title COLLATE NOCASE")
    fun observeRecordsFor(assetId: String): Flow<List<ServiceRecordEntity>>

    @Upsert
    suspend fun upsertRecord(record: ServiceRecordEntity)

    @Query("DELETE FROM service_records WHERE id = :id")
    suspend fun deleteRecord(id: String)

    // --- meter readings ---

    @Query("SELECT * FROM meter_readings ORDER BY readAt")
    fun observeReadings(): Flow<List<MeterReadingEntity>>

    @Query("SELECT * FROM meter_readings WHERE assetId = :assetId ORDER BY readAt")
    fun observeReadingsFor(assetId: String): Flow<List<MeterReadingEntity>>

    @Query("SELECT * FROM meter_readings WHERE assetId = :assetId ORDER BY readAt")
    suspend fun readingsOf(assetId: String): List<MeterReadingEntity>

    @Insert
    suspend fun insertReading(reading: MeterReadingEntity)

    @Query("DELETE FROM meter_readings WHERE id = :id")
    suspend fun deleteReading(id: String)

    // --- loans ---

    @Query("SELECT * FROM loans ORDER BY label COLLATE NOCASE")
    fun observeLoans(): Flow<List<LoanEntity>>

    @Query("SELECT * FROM loans WHERE assetId = :assetId ORDER BY label COLLATE NOCASE")
    fun observeLoansFor(assetId: String): Flow<List<LoanEntity>>

    @Upsert
    suspend fun upsertLoan(loan: LoanEntity)

    @Query("DELETE FROM loans WHERE id = :id")
    suspend fun deleteLoan(id: String)

    // --- coverages ---

    @Query("SELECT * FROM coverages ORDER BY expiresAt, provider COLLATE NOCASE")
    fun observeCoverages(): Flow<List<CoverageEntity>>

    @Query("SELECT * FROM coverages WHERE assetId = :assetId ORDER BY expiresAt, provider COLLATE NOCASE")
    fun observeCoveragesFor(assetId: String): Flow<List<CoverageEntity>>

    @Upsert
    suspend fun upsertCoverage(coverage: CoverageEntity)

    @Query("DELETE FROM coverages WHERE id = :id")
    suspend fun deleteCoverage(id: String)
}
