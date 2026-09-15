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
import com.maintenance.app.logic.ServiceEntry
import com.maintenance.app.logic.Vendors
import com.maintenance.app.logic.SchedulePacks
import com.maintenance.app.logic.SchedulePlans
import com.maintenance.app.logic.VehicleFacts
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import com.maintenance.app.logic.CoverageKind
import com.maintenance.app.logic.PremiumPeriod
import com.maintenance.app.logic.Region
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
        repo.board.observeAssetDetail(assetId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Every service ever logged, on **every** asset — held only so the log dialog can offer back a
     * vendor you have already used. The garage that did the truck is the one you would ring about
     * the mower, so this deliberately reaches past the asset this page is about.
     */
    private val serviceEntries: StateFlow<List<ServiceEntry>> =
        repo.board.observeServiceEntries().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun suggestVendors(typed: String): List<String> = Vendors.suggestions(serviceEntries.value, typed)

    // --- the asset itself ---

    fun save(asset: Asset, attributes: Map<String, String>) =
        viewModelScope.launch { repo.assets.updateAsset(asset, attributes) }

    fun setArchived(archived: Boolean) = viewModelScope.launch {
        repo.assets.setArchived(assetId, archived)
        // An asset you no longer own has nothing owed on it: the round takes its tasks off the week.
        publisher.round()
    }

    fun delete(onDeleted: () -> Unit) = viewModelScope.launch {
        // The cascade cannot reach into LifeOps, so the tasks come off the week here, before the
        // rows that name them are gone.
        publisher.retire(repo.assets.deleteAsset(assetId))
        onDeleted()
    }

    // --- upkeep ---

    // Every write that can change *when* something is due ends with a round, because the whole
    // promise of the seam is that the week and the schedule agree. The round is idempotent, so
    // calling it after each edit is cheaper than reasoning about which edits could matter.

    fun addPlan(title: String, everyDays: Int?, everyMeter: Long?, notes: String?, publishToLifeOps: Boolean) =
        viewModelScope.launch {
            repo.upkeep.addPlan(assetId, title, everyDays, everyMeter, notes, publishToLifeOps)
            publisher.round()
        }

    fun updatePlan(plan: UpkeepPlan) = viewModelScope.launch {
        repo.upkeep.updatePlan(plan)
        publisher.round()
    }

    fun setPlanActive(planId: String, active: Boolean) = viewModelScope.launch {
        repo.upkeep.setPlanActive(planId, active)
        publisher.round()
    }

    fun deletePlan(planId: String) = viewModelScope.launch {
        repo.upkeep.deletePlan(planId)?.let { publisher.retire(listOf(it)) }
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
        repo.week.logService(assetId, planId, title, vendor, performedAt, costCents, meterValue, notes)
        // The clock moved: this occurrence's task comes off the week and the next one goes on.
        publisher.round()
    }

    fun deleteRecord(recordId: String) = viewModelScope.launch { repo.week.deleteRecord(recordId) }

    // --- meter ---

    fun addReading(value: Long, readAt: Long) = viewModelScope.launch {
        // The reading is what satisfies a "read the odometer" prompt, so the tasks those prompts put
        // on the week tick themselves off — you have already done the thing they were asking for.
        publisher.completeTasks(repo.meter.addReading(assetId, value, readAt))
        // A reading also changes the usage rate, and the rate is what dates a mileage interval — so
        // a task's date on the week can move because of a number typed at a petrol pump.
        publisher.round()
    }

    // ------------------------------------------------------------------ the VIN, and what it opens

    /**
     * What the lookups are doing and what they last said.
     *
     * Held here rather than in the database because none of it is a fact about the asset — it is the
     * state of a button somebody pressed. What the lookups *find* gets written down (the decoded
     * make and model, the recalls); the asking does not.
     *
     * [busy], [facts] and [packs] belong to the VIN decode, which is the only one of these that
     * leaves the device. [applied] is shared with the home section, because applying a schedule is
     * the same act whatever chose it and the confirmation reads the same either way.
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

    /**
     * Take everything the decode found — make, model, year, trim, body style, engine, fuel,
     * transmission and drivetrain — filling only the fields that are still blank.
     */
    fun useFacts(facts: VehicleFacts) = viewModelScope.launch {
        repo.week.applyVehicleFacts(assetId, facts)
    }

    /** Add a schedule's items as plans, then put whatever is due onto the week. */
    fun applyPack(pack: SchedulePack) = viewModelScope.launch {
        val application = repo.week.applyPack(assetId, pack)
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
                // The check is what satisfies the prompt that asked for it, so the task it put on
                // the week ticks itself off — the same way a meter reading ticks off the odometer
                // prompt. The round then dates the next check.
                publisher.completeTasks(repo.recalls.saveRecalls(assetId, recalls))
                publisher.round()
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
        repo.recalls.setRecallAcknowledged(assetId, campaign, acknowledged)
    }

    /**
     * Start checking this vehicle's recalls on a cadence.
     *
     * The prompt normally arrives with a schedule pack, which is where a vehicle picks up everything
     * else it should be doing regularly. This is the same plan, offered on its own — for a vehicle
     * that predates the prompt, or one whose owner never applied a pack. It is a button rather than
     * something the app does on your behalf, because a plan is a thing that puts a task on your week
     * and inventing those unasked is how an app stops being trusted.
     */
    fun addRecallPrompt() = viewModelScope.launch {
        val item = SchedulePacks.RECALL_CHECK_ITEM
        repo.upkeep.addPlan(
            assetId = assetId,
            title = item.title,
            everyDays = item.everyDays,
            everyMeter = null,
            notes = item.notes,
            kind = item.kind
        )
        publisher.round()
    }

    // ------------------------------------------------------------------ the address, and what it opens

    /**
     * Take the region the ZIP worked out, and make it the household's own.
     *
     * Until this is pressed the region is a *guess* the screen re-derives every time it draws, and
     * it says so. Pressing it writes the answer into the field, which is what stops it being a
     * guess: nothing re-derives a picked region, and the ZIP is consulted only while the field is
     * empty. It is the same gesture as *Use these details* on a VIN decode, for the same reason —
     * what an app worked out is an offer until somebody accepts it.
     */
    fun useRegion(region: Region) = viewModelScope.launch {
        repo.assets.setAttribute(assetId, ATTR_REGION, region.key)
        // The region decides which climate and hazard schedules fit, but applying one is still a
        // separate press: nothing goes onto anybody's week for having agreed with a guess.
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
        repo.money.upsertLoan(
            loanId, assetId, label, lender, accountRef, principalCents,
            annualRateBps, termMonths, paymentCents, escrowCents, startEpochDay, notes
        )
    }

    fun deleteLoan(loanId: String) = viewModelScope.launch { repo.money.deleteLoan(loanId) }

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
        repo.money.upsertCoverage(coverageId, assetId, kind, provider, policyNumber, premiumCents, period, startsAt, expiresAt, notes)
    }

    fun deleteCoverage(coverageId: String) = viewModelScope.launch { repo.money.deleteCoverage(coverageId) }

    private companion object {
        const val ATTR_REGION = "region"
    }

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
