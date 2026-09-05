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
import com.maintenance.app.data.db.entities.RecallEntity
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

    /** Every asset, for the publishing round — which reads once and folds, rather than per plan. */
    @Query("SELECT * FROM assets")
    suspend fun allAssets(): List<AssetEntity>

    @Query("SELECT * FROM meter_readings ORDER BY readAt")
    suspend fun allReadings(): List<MeterReadingEntity>

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

    /** A one-shot read, for a write that has to look before it leaps — see `applyVehicleFacts`. */
    @Query("SELECT * FROM asset_attributes WHERE assetId = :assetId")
    suspend fun attributesOf(assetId: String): List<AssetAttributeEntity>

    /** Every asset's identity fields at once — for a reader that wants the register entire. */
    @Query("SELECT * FROM asset_attributes")
    suspend fun allAttributes(): List<AssetAttributeEntity>

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

    /**
     * Write the LifeOps link on a plan.
     *
     * Its own statement rather than an upsert of the whole row, because the publisher writes it
     * from a background round while the user may be editing the same plan on screen: a full upsert
     * would carry a stale title back over the edit.
     */
    @Query("UPDATE upkeep_plans SET lifeOpsTaskId = :taskId, publishedDueDay = :publishedDueDay WHERE id = :id")
    suspend fun setPlanLink(id: String, taskId: String?, publishedDueDay: Long?)

    /**
     * The LifeOps tasks standing for an asset's plans — read *before* the asset is deleted, so its
     * tasks can be taken off the week rather than stranded there by the cascade.
     */
    @Query("SELECT lifeOpsTaskId FROM upkeep_plans WHERE assetId = :assetId AND lifeOpsTaskId IS NOT NULL")
    suspend fun taskIdsForAsset(assetId: String): List<String>

    /** Every plan, for a publishing round. */
    @Query("SELECT * FROM upkeep_plans ORDER BY sortOrder, title COLLATE NOCASE")
    suspend fun allPlans(): List<UpkeepPlanEntity>

    // --- service records ---

    @Query("SELECT * FROM service_records ORDER BY performedAt DESC, title COLLATE NOCASE")
    fun observeRecords(): Flow<List<ServiceRecordEntity>>

    @Query("SELECT * FROM service_records WHERE assetId = :assetId ORDER BY performedAt DESC, title COLLATE NOCASE")
    fun observeRecordsFor(assetId: String): Flow<List<ServiceRecordEntity>>

    @Upsert
    suspend fun upsertRecord(record: ServiceRecordEntity)

    @Query("DELETE FROM service_records WHERE id = :id")
    suspend fun deleteRecord(id: String)

    @Query("SELECT * FROM service_records ORDER BY performedAt DESC, title COLLATE NOCASE")
    suspend fun allRecords(): List<ServiceRecordEntity>

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

    /** The plans on an asset, for applying a schedule pack against what is already there. */
    @Query("SELECT * FROM upkeep_plans WHERE assetId = :assetId ORDER BY sortOrder, title COLLATE NOCASE")
    suspend fun plansOf(assetId: String): List<UpkeepPlanEntity>

    @Upsert
    suspend fun upsertPlans(plans: List<UpkeepPlanEntity>)

    /**
     * Meter-reading prompts on an asset, active ones only — what a new reading satisfies.
     * Ordinary upkeep is never satisfied by a number; see `logic/PlanKind`.
     */
    // The literal is `PlanKind.METER_READING.key`; SQL cannot ask an enum for it.
    @Query("SELECT * FROM upkeep_plans WHERE assetId = :assetId AND kind = 'meter_reading' AND active = 1")
    suspend fun meterPromptsOf(assetId: String): List<UpkeepPlanEntity>

    /**
     * Recall-check prompts on an asset, active ones only — what asking NHTSA satisfies.
     * The same shape as [meterPromptsOf], for the same reason; see `logic/PlanKind`.
     */
    // The literal is `PlanKind.RECALL_CHECK.key`.
    @Query("SELECT * FROM upkeep_plans WHERE assetId = :assetId AND kind = 'recall_check' AND active = 1")
    suspend fun recallPromptsOf(assetId: String): List<UpkeepPlanEntity>

    // --- recalls ---

    @Query("SELECT * FROM recalls ORDER BY parkIt DESC, parkOutside DESC, reportedOnEpochDay DESC")
    fun observeRecalls(): Flow<List<RecallEntity>>

    @Query("SELECT * FROM recalls WHERE assetId = :assetId ORDER BY parkIt DESC, parkOutside DESC, reportedOnEpochDay DESC")
    fun observeRecallsFor(assetId: String): Flow<List<RecallEntity>>

    @Query("SELECT * FROM recalls WHERE assetId = :assetId AND campaignNumber = :campaign")
    suspend fun getRecall(assetId: String, campaign: String): RecallEntity?

    @Upsert
    suspend fun upsertRecalls(recalls: List<RecallEntity>)

    @Query("UPDATE recalls SET acknowledgedAt = :at WHERE assetId = :assetId AND campaignNumber = :campaign")
    suspend fun setRecallAcknowledged(assetId: String, campaign: String, at: Long?)

    @Query("SELECT * FROM recalls ORDER BY parkIt DESC, parkOutside DESC, reportedOnEpochDay DESC")
    suspend fun allRecalls(): List<RecallEntity>

    // --- loans ---

    @Query("SELECT * FROM loans ORDER BY label COLLATE NOCASE")
    fun observeLoans(): Flow<List<LoanEntity>>

    @Query("SELECT * FROM loans WHERE assetId = :assetId ORDER BY label COLLATE NOCASE")
    fun observeLoansFor(assetId: String): Flow<List<LoanEntity>>

    @Upsert
    suspend fun upsertLoan(loan: LoanEntity)

    @Query("DELETE FROM loans WHERE id = :id")
    suspend fun deleteLoan(id: String)

    @Query("SELECT * FROM loans ORDER BY label COLLATE NOCASE")
    suspend fun allLoans(): List<LoanEntity>

    // --- coverages ---

    @Query("SELECT * FROM coverages ORDER BY expiresAt, provider COLLATE NOCASE")
    fun observeCoverages(): Flow<List<CoverageEntity>>

    @Query("SELECT * FROM coverages WHERE assetId = :assetId ORDER BY expiresAt, provider COLLATE NOCASE")
    fun observeCoveragesFor(assetId: String): Flow<List<CoverageEntity>>

    @Upsert
    suspend fun upsertCoverage(coverage: CoverageEntity)

    @Query("DELETE FROM coverages WHERE id = :id")
    suspend fun deleteCoverage(id: String)

    @Query("SELECT * FROM coverages ORDER BY expiresAt, provider COLLATE NOCASE")
    suspend fun allCoverages(): List<CoverageEntity>
}
