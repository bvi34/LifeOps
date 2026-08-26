package com.health.app.ui.meds

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.health.app.data.model.CabinetEntry
import com.health.app.data.model.CabinetItem
import com.health.app.data.model.Dose
import com.health.app.data.model.Medication
import com.health.app.data.model.MedicationStatus
import com.health.app.data.model.Profile
import com.health.app.data.net.DrugLookupClient
import com.health.app.data.repository.HealthRepository
import com.health.app.logic.DrugCandidate
import com.health.app.logic.DrugMonograph
import com.health.app.logic.ReminderMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalTime

/**
 * What a drug search is doing right now.
 *
 * [error] and [results] are not exclusive with [searching] by accident: a failed lookup keeps the
 * previous results on screen underneath the message, because the usual failure is a dropped
 * connection on the *second* search and throwing away what the first one found helps nobody.
 */
data class DrugSearchState(
    val query: String = "",
    val searching: Boolean = false,
    val results: List<DrugCandidate> = emptyList(),
    val error: String? = null,
    /** The product whose facts are being fetched, so the row can show its own spinner. */
    val fetchingRxcui: String? = null,
    /** What came back — the monograph the form is prefilled from. */
    val picked: DrugMonograph? = null
)

/**
 * The Meds tab's state: the cabinet (household), the selected person's medicines and doses, and the
 * drug lookup that fills both in.
 *
 * The lookup is the only part of Health that can fail for reasons outside the device, so it is the
 * only part with an error in its state. Everything else is a database flow that either has rows or
 * doesn't.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MedsViewModel(
    private val repo: HealthRepository,
    private val lookup: DrugLookupClient
) : ViewModel() {

    val profiles: StateFlow<List<Profile>> =
        repo.observeProfiles().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val selected: StateFlow<Profile?> =
        repo.observeSelectedProfile().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The household's stock — not scoped to the selected person, because a bottle isn't. */
    val cabinet: StateFlow<List<CabinetEntry>> =
        repo.observeCabinet().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val statuses: StateFlow<List<MedicationStatus>> = selected
        .flatMapLatest { profile ->
            if (profile == null) flowOf(emptyList()) else repo.observeMedicationStatuses(profile.id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val doses: StateFlow<List<Dose>> = selected
        .flatMapLatest { profile -> if (profile == null) flowOf(emptyList()) else repo.observeDoses(profile.id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _search = MutableStateFlow(DrugSearchState())
    val search: StateFlow<DrugSearchState> = _search.asStateFlow()

    private var searchJob: Job? = null

    fun select(profile: Profile) = repo.selectProfile(profile.id)

    // --- the drug lookup --------------------------------------------------------------------------

    /**
     * Search the drug references for what has been typed.
     *
     * Debounced, and the previous search is cancelled rather than raced: two in-flight lookups can
     * land out of order, and a list that flickers back to the results for "ibup" after showing the
     * ones for "ibuprofen" is worse than a list that takes another moment.
     *
     * A query shorter than the client's floor clears the results rather than searching — one letter
     * matches half the pharmacopoeia.
     */
    fun searchDrugs(query: String) {
        _search.value = _search.value.copy(query = query, error = null)
        searchJob?.cancel()
        if (query.trim().length < DrugLookupClient.MIN_QUERY_LENGTH) {
            _search.value = _search.value.copy(results = emptyList(), searching = false)
            return
        }
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            _search.value = _search.value.copy(searching = true)
            val outcome = runCatching { lookup.search(query) }.rethrowCancellation()
            _search.value = outcome.fold(
                onSuccess = { results ->
                    _search.value.copy(
                        searching = false,
                        results = results,
                        error = if (results.isEmpty()) "Nothing matched \"${query.trim()}\"." else null
                    )
                },
                onFailure = { failure ->
                    _search.value.copy(searching = false, error = failure.readableMessage())
                }
            )
        }
    }

    /**
     * Fetch and cache everything the references know about one hit, and hand it to the form.
     *
     * Cached first: a product already looked up shows instantly and without a request, which is the
     * common case in a household that buys the same four things. [refresh] forces the network path
     * for the "this label looks out of date" button.
     */
    fun pickCandidate(candidate: DrugCandidate, refresh: Boolean = false) {
        viewModelScope.launch {
            _search.value = _search.value.copy(fetchingRxcui = candidate.rxcui, error = null)
            val cached = if (refresh) null else repo.getMonograph(candidate.rxcui)
            if (cached != null) {
                _search.value = _search.value.copy(fetchingRxcui = null, picked = cached)
                return@launch
            }
            runCatching { lookup.fetchMonograph(candidate.rxcui, System.currentTimeMillis()) }
                .rethrowCancellation()
                .fold(
                    onSuccess = { monograph ->
                        repo.saveMonograph(monograph)
                        _search.value = _search.value.copy(fetchingRxcui = null, picked = monograph)
                    },
                    onFailure = { failure ->
                        _search.value = _search.value.copy(
                            fetchingRxcui = null,
                            error = failure.readableMessage()
                        )
                    }
                )
        }
    }

    /** Look a cabinet item's label up again — labels get revised, and caches go stale. */
    fun refreshMonograph(rxcui: String) {
        viewModelScope.launch {
            _search.value = _search.value.copy(fetchingRxcui = rxcui, error = null)
            runCatching { lookup.fetchMonograph(rxcui, System.currentTimeMillis()) }
                .rethrowCancellation()
                .fold(
                    onSuccess = { monograph ->
                        repo.saveMonograph(monograph)
                        _search.value = _search.value.copy(fetchingRxcui = null)
                    },
                    onFailure = { failure ->
                        _search.value = _search.value.copy(
                            fetchingRxcui = null,
                            error = failure.readableMessage()
                        )
                    }
                )
        }
    }

    /** Drop the picked product without leaving the form — "no, not that one". */
    fun clearPicked() {
        _search.value = _search.value.copy(picked = null)
    }

    /** Reset the whole lookup, on closing the dialog. */
    fun clearSearch() {
        searchJob?.cancel()
        _search.value = DrugSearchState()
    }

    // --- the cabinet ------------------------------------------------------------------------------

    /**
     * Add something to the cabinet and, optionally, to the selected person's medicines in the same
     * gesture — which is what actually happens when a bottle comes home from the shop.
     *
     * The two halves are independent on purpose. A household can stock paracetamol without deciding
     * whose it is, and a person can be on a medicine the household isn't counting the stock of.
     */
    fun addToCabinet(
        name: String,
        rxcui: String?,
        brandName: String?,
        strength: String?,
        form: String?,
        quantity: Double?,
        quantityUnit: String,
        expiryDate: String?,
        location: String?,
        lowStockThreshold: Double?,
        alsoForProfile: MedicationDraft?
    ) = viewModelScope.launch {
        val itemId = repo.addCabinetItem(
            name = name,
            rxcui = rxcui,
            brandName = brandName,
            strength = strength,
            form = form,
            quantity = quantity,
            quantityUnit = quantityUnit,
            expiryDate = expiryDate,
            location = location,
            lowStockThreshold = lowStockThreshold
        )
        alsoForProfile?.let { draft -> saveMedication(draft, rxcui = rxcui, cabinetItemId = itemId) }
    }

    fun updateCabinetItem(item: CabinetItem) = viewModelScope.launch { repo.updateCabinetItem(item) }

    fun restock(itemId: String, quantity: Double?, expiryDate: String?) =
        viewModelScope.launch { repo.restockCabinetItem(itemId, quantity, expiryDate) }

    fun deleteCabinetItem(itemId: String) = viewModelScope.launch { repo.deleteCabinetItem(itemId) }

    fun linkToCabinet(medicationId: String, cabinetItemId: String?) =
        viewModelScope.launch { repo.linkMedicationToCabinet(medicationId, cabinetItemId) }

    // --- one person's medicines -------------------------------------------------------------------

    /** Add a medicine for the selected person, with or without a product behind it. */
    fun addMedication(draft: MedicationDraft, rxcui: String? = null, cabinetItemId: String? = null) =
        viewModelScope.launch { saveMedication(draft, rxcui, cabinetItemId) }

    private suspend fun saveMedication(draft: MedicationDraft, rxcui: String?, cabinetItemId: String?) {
        val profile = selected.value ?: return
        repo.addMedication(
            profileId = profile.id,
            name = draft.name,
            strength = draft.strength,
            form = draft.form,
            doseAmount = draft.doseAmount,
            doseUnit = draft.doseUnit,
            minIntervalHours = draft.minIntervalHours,
            maxDosesPer24h = draft.maxDosesPer24h,
            maxAmountPer24h = draft.maxAmountPer24h,
            note = draft.note,
            rxcui = rxcui,
            cabinetItemId = cabinetItemId,
            reminderMode = draft.reminderMode,
            reminderTimes = draft.reminderTimes
        )
    }

    fun give(medication: Medication) = viewModelScope.launch { repo.logDoseOf(medication) }

    fun logDose(medication: Medication?, name: String, amount: Double, unit: String, note: String?) =
        viewModelScope.launch {
            val profile = selected.value ?: return@launch
            repo.logDose(profile.id, medication?.id, name, amount, unit, note = note)
        }

    fun setActive(medication: Medication, active: Boolean) = viewModelScope.launch {
        repo.updateMedication(medication.copy(active = active))
    }

    fun setReminder(medication: Medication, mode: ReminderMode, times: List<LocalTime>) =
        viewModelScope.launch { repo.setMedicationReminder(medication.id, mode, times) }

    fun deleteMedication(medication: Medication) = viewModelScope.launch { repo.deleteMedication(medication.id) }

    fun deleteDose(dose: Dose) = viewModelScope.launch { repo.deleteDose(dose.id) }

    /**
     * Let a cancellation stay a cancellation.
     *
     * `runCatching` catches [CancellationException] along with everything else, so without this a
     * search superseded by the next keystroke would report "Job was cancelled" as though the drug
     * reference had failed — and would write that over the state the newer search is building.
     */
    private fun <T> Result<T>.rethrowCancellation(): Result<T> = also {
        (exceptionOrNull() as? CancellationException)?.let { throw it }
    }

    private fun Throwable.readableMessage(): String =
        message?.takeIf { it.isNotBlank() } ?: "The drug reference couldn't be reached."

    class Factory(
        private val repo: HealthRepository,
        private val lookup: DrugLookupClient
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MedsViewModel(repo, lookup) as T
    }

    companion object {
        /** Long enough that typing a drug name doesn't fire eight requests, short enough to feel live. */
        private const val SEARCH_DEBOUNCE_MS = 350L
    }
}

/**
 * A medicine as the add form has it, before it becomes a row.
 *
 * Its own type rather than nine parameters because it travels from a dialog to the view model to the
 * repository, and the two places that fill it in — "add a medicine" and "add a bottle and say who
 * takes it" — would otherwise each need their own argument list saying the same thing.
 */
data class MedicationDraft(
    val name: String,
    val strength: String? = null,
    val form: String? = null,
    val doseAmount: Double? = null,
    val doseUnit: String = "",
    val minIntervalHours: Double? = null,
    val maxDosesPer24h: Int? = null,
    val maxAmountPer24h: Double? = null,
    val note: String? = null,
    val reminderMode: ReminderMode = ReminderMode.OFF,
    val reminderTimes: List<LocalTime> = emptyList()
)
