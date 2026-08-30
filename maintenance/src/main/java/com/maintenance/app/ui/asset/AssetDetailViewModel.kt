package com.maintenance.app.ui.asset

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.maintenance.app.data.model.Asset
import com.maintenance.app.data.model.AssetDetail
import com.maintenance.app.data.repository.MaintenanceRepository
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
    private val assetId: String
) : ViewModel() {

    val detail: StateFlow<AssetDetail?> =
        repo.observeAssetDetail(assetId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // --- the asset itself ---

    fun save(asset: Asset, attributes: Map<String, String>) =
        viewModelScope.launch { repo.updateAsset(asset, attributes) }

    fun setArchived(archived: Boolean) = viewModelScope.launch { repo.setArchived(assetId, archived) }

    fun delete(onDeleted: () -> Unit) = viewModelScope.launch {
        repo.deleteAsset(assetId)
        onDeleted()
    }

    // --- upkeep ---

    fun addPlan(title: String, everyDays: Int?, everyMeter: Long?, notes: String?) =
        viewModelScope.launch { repo.addPlan(assetId, title, everyDays, everyMeter, notes) }

    fun updatePlan(plan: UpkeepPlan) = viewModelScope.launch { repo.updatePlan(plan) }

    fun setPlanActive(planId: String, active: Boolean) =
        viewModelScope.launch { repo.setPlanActive(planId, active) }

    fun deletePlan(planId: String) = viewModelScope.launch { repo.deletePlan(planId) }

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
    }

    fun deleteRecord(recordId: String) = viewModelScope.launch { repo.deleteRecord(recordId) }

    // --- meter ---

    fun addReading(value: Long, readAt: Long) = viewModelScope.launch { repo.addReading(assetId, value, readAt) }

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
        private val assetId: String
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AssetDetailViewModel(repo, assetId) as T
    }
}
