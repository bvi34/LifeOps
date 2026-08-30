package com.maintenance.app.ui.asset

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.maintenance.app.data.model.Asset
import com.maintenance.app.data.model.AssetDetail
import com.maintenance.app.data.repository.MaintenanceRepository
import com.maintenance.app.data.repository.UpkeepPublisher
import com.maintenance.app.data.net.VehicleLookupClient
import com.maintenance.app.logic.SchedulePack
import com.maintenance.app.logic.SchedulePacks
import com.maintenance.app.logic.SchedulePlans
import com.maintenance.app.logic.VehicleFacts
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import com.maintenance.app.logic.CoverageKind
import com.maintenance.app.logic.PremiumPeriod
import com.maintenance.app.logic.UpkeepPlan
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * One asset's page.
 *
 * Every write here is a one-liner onto the repository, which is where the rules live — advancing a
 * plan's clock, filing a service's mileage as a reading, clearing an attribute rather than storing
 * a blank. This class exists to hold the asset id and to keep the screen from touching the
 * repository on the main thread; it deliberately holds no state of its own, because everything the
 * screen draws is derived and arrives on [detail].
 */
class AssetDetailViewModel(
    private val repo: MaintenanceRepository,
    private val publisher: UpkeepPublisher,
    private val assetId: String,
    private val lookups: VehicleLookupClient = VehicleLookupClient()
) : ViewModel() {

    val detail: StateFlow<AssetDetail?> =
        repo.observeAssetDetail(assetId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // --- the asset itself ---

    fun save(asset: Asset, attributes: Map<String, String>) =
        viewModelScope.launch { repo.updateAsset(asset, attributes) }

    fun setArchived(archived: Boolean) = viewModelScope.launch {
        repo.setArchived(assetId, archived)
        // An asset you no longer own has nothing owed on it: the round takes its tasks off the week.
        publisher.round()
    }

    fun delete(onDeleted: () -> Unit) = viewModelScope.launch {
        // The cascade cannot reach into LifeOps, so the tasks come off the week here, before the
        // rows that name them are gone.
        publisher.retire(repo.deleteAsset(assetId))
        onDeleted()
    }

    // --- upkeep ---

    // Every write that can change *when* something is due ends with a round, because the whole
    // promise of the seam is that the week and the schedule agree. The round is idempotent, so
    // calling it after each edit is cheaper than reasoning about which edits could matter.

    fun addPlan(title: String, everyDays: Int?, everyMeter: Long?, notes: String?, publishToLifeOps: Boolean) =
        viewModelScope.launch {
            repo.addPlan(assetId, title, everyDays, everyMeter, notes, publishToLifeOps)
            publisher.round()
        }

    fun updatePlan(plan: UpkeepPlan) = viewModelScope.launch {
        repo.updatePlan(plan)
        publisher.round()
    }

    fun setPlanActive(planId: String, active: Boolean) = viewModelScope.launch {
        repo.setPlanActive(planId, active)
        publisher.round()
    }

    fun deletePlan(planId: String) = viewModelScope.launch {
        repo.deletePlan(planId)?.let { publisher.retire(listOf(it)) }
    }

    fun logService(
        planId: String?,
        title: String,
        vendor: String?,
        performedAt: Long,
        costCents: Long,
        meterValue: Long?,
        notes: String?
    ) = viewModelScope.launch {
        repo.logService(assetId, planId, title, vendor, performedAt, costCents, meterValue, notes)
        // The clock moved: this occurrence's task comes off the week and the next one goes on.
        publisher.round()
    }

    fun deleteRecord(recordId: String) = viewModelScope.launch { repo.deleteRecord(recordId) }

    // --- meter ---

    fun addReading(value: Long, readAt: Long) = viewModelScope.launch {
        // The reading is what satisfies a "read the odometer" prompt, so the tasks those prompts put
        // on the week tick themselves off — you have already done the thing they were asking for.
        publisher.completeTasks(repo.addReading(assetId, value, readAt))
        // A reading also changes the usage rate, and the rate is what dates a mileage interval — so
        // a task's date on the week can move because of a number typed at a petrol pump.
        publisher.round()
    }

    // ------------------------------------------------------------------ the VIN, and what it opens

    /**
     * What the two lookups are doing and what they last said.
     *
     * Held here rather than in the database because none of it is a fact about the asset — it is the
     * state of a button somebody pressed. What the lookups *find* gets written down (the decoded
     * make and model, the recalls); the asking does not.
     */
    data class LookupState(
        val busy: Boolean = false,
        val facts: VehicleFacts? = null,
        val packs: List<SchedulePack> = emptyList(),
        val message: String? = null,
        val applied: SchedulePlans.Application? = null
    )

    private val _lookup = MutableStateFlow(LookupState())
    val lookup: StateFlow<LookupState> = _lookup.asStateFlow()

    fun dismissLookup() = _lookup.update { LookupState() }

    /**
     * Decode the VIN on this asset.
     *
     * Only the model-describing half of it is sent; see `data/net/VehicleLookupClient`. What comes
     * back is *offered*, never applied on its own — the screen shows the facts and the schedules
     * they match, and you choose.
     */
    fun decodeVin(vin: String) = viewModelScope.launch {
        _lookup.update { LookupState(busy = true) }
        runCatching { lookups.decode(vin) }
            .onSuccess { facts ->
                _lookup.value = LookupState(facts = facts, packs = SchedulePacks.forVehicle(facts))
            }
            .onFailure { error ->
                _lookup.value = LookupState(message = error.message ?: "The decode didn't work.")
            }
    }

    /** Take the decoded make, model, year and trim — filling only what is still blank. */
    fun useFacts(facts: VehicleFacts) = viewModelScope.launch {
        repo.applyVehicleFacts(assetId, facts)
    }

    /** Add a schedule's items as plans, then put whatever is due onto the week. */
    fun applyPack(pack: SchedulePack) = viewModelScope.launch {
        val application = repo.applyPack(assetId, pack)
        _lookup.update { it.copy(applied = application) }
        publisher.round()
    }

    /**
     * Ask NHTSA what is recalled on this model.
     *
     * Make, model and year — no VIN. Nothing is asked unless those three are known, which is why the
     * screen offers the decode first.
     */
    fun checkRecalls(make: String, model: String, year: Int) = viewModelScope.launch {
        _lookup.update { it.copy(busy = true, message = null) }
        runCatching { lookups.recalls(make, model, year) }
            .onSuccess { recalls ->
                repo.saveRecalls(assetId, recalls)
                _lookup.update {
                    it.copy(
                        busy = false,
                        message = if (recalls.isEmpty()) "No open recalls for this model." else null
                    )
                }
            }
            .onFailure { error ->
                _lookup.update { it.copy(busy = false, message = error.message ?: "The recall check didn't work.") }
            }
    }

    fun setRecallAcknowledged(campaign: String, acknowledged: Boolean) = viewModelScope.launch {
        repo.setRecallAcknowledged(assetId, campaign, acknowledged)
    }

    // --- money ---

    fun saveLoan(
        loanId: String?,
        label: String,
        lender: String?,
        accountRef: String?,
        principalCents: Long,
        annualRateBps: Int,
        termMonths: Int,
        paymentCents: Long?,
        escrowCents: Long,
        startEpochDay: Long?,
        notes: String?
    ) = viewModelScope.launch {
        repo.upsertLoan(
            loanId, assetId, label, lender, accountRef, principalCents,
            annualRateBps, termMonths, paymentCents, escrowCents, startEpochDay, notes
        )
    }

    fun deleteLoan(loanId: String) = viewModelScope.launch { repo.deleteLoan(loanId) }

    fun saveCoverage(
        coverageId: String?,
        kind: CoverageKind,
        provider: String,
        policyNumber: String?,
        premiumCents: Long,
        period: PremiumPeriod,
        startsAt: Long?,
        expiresAt: Long?,
        notes: String?
    ) = viewModelScope.launch {
        repo.upsertCoverage(coverageId, assetId, kind, provider, policyNumber, premiumCents, period, startsAt, expiresAt, notes)
    }

    fun deleteCoverage(coverageId: String) = viewModelScope.launch { repo.deleteCoverage(coverageId) }

    class Factory(
        private val repo: MaintenanceRepository,
        private val publisher: UpkeepPublisher,
        private val assetId: String
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AssetDetailViewModel(repo, publisher, assetId) as T
    }
}
