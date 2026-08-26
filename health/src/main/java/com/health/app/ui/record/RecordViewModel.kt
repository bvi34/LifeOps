package com.health.app.ui.record

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.health.app.data.model.Allergy
import com.health.app.data.model.Condition
import com.health.app.data.model.Immunization
import com.health.app.data.model.Profile
import com.health.app.data.model.Provider
import com.health.app.data.model.ReadingType
import com.health.app.data.model.StandingRecord
import com.health.app.data.repository.HealthRepository
import com.health.app.logic.AllergyKind
import com.health.app.logic.AllergySeverity
import com.health.app.logic.ConditionStatus
import com.health.app.logic.VaccineSeries
import com.health.app.logic.VaccineSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the allergy form collects. A draft rather than a model: nothing has an id until it is saved. */
data class AllergyDraft(
    val substance: String = "",
    val kind: AllergyKind = AllergyKind.DRUG,
    val severity: AllergySeverity = AllergySeverity.UNKNOWN,
    val reaction: String = "",
    val noticedDate: String = "",
    val note: String = ""
) {
    val isValid: Boolean get() = substance.isNotBlank()
}

/** What the condition form collects. */
data class ConditionDraft(
    val name: String = "",
    val status: ConditionStatus = ConditionStatus.ACTIVE,
    val onsetDate: String = "",
    val resolvedDate: String = "",
    val providerId: String? = null,
    val monitorReadingType: ReadingType? = null,
    val note: String = ""
) {
    val isValid: Boolean get() = name.isNotBlank()
}

/** What the vaccination form collects. */
data class ImmunizationDraft(
    val vaccine: String = "",
    val givenDate: String = "",
    val doseNumber: String = "",
    val source: VaccineSource = VaccineSource.TRANSCRIBED,
    val providerId: String? = null,
    val lotNumber: String = "",
    val site: String = "",
    val note: String = ""
) {
    val isValid: Boolean get() = vaccine.isNotBlank()

    /** A dose number that isn't a number is no dose number. Health does not invent one. */
    val dose: Int? get() = doseNumber.trim().toIntOrNull()
}

/**
 * The Record tab's state: one person's standing facts, plus the household's care team so a condition
 * can name the clinician who manages it.
 *
 * Nothing here can fail for a reason outside the device — there is no network in this tab and there
 * never will be, because everything it holds is the most identifying data in the app. So unlike the
 * Care tab's state there is no message channel: these are database flows that either have rows or
 * don't.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecordViewModel(private val repo: HealthRepository) : ViewModel() {

    val profiles: StateFlow<List<Profile>> =
        repo.observeProfiles().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val selected: StateFlow<Profile?> =
        repo.observeSelectedProfile().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The care team, so a condition can point at the doctor who manages it. Household-scoped. */
    val providers: StateFlow<List<Provider>> =
        repo.observeProviders().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val record: StateFlow<StandingRecord> = selected
        .flatMapLatest { profile ->
            if (profile == null) flowOf(StandingRecord.EMPTY) else repo.observeStandingRecord(profile.id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StandingRecord.EMPTY)

    /** Grouped into series, most recently given first — see `logic/Immunizations`. */
    val vaccineSeries: StateFlow<List<VaccineSeries>> = selected
        .flatMapLatest { profile ->
            if (profile == null) flowOf(emptyList()) else repo.observeVaccineSeries(profile.id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * The same rows ungrouped, keyed by id.
     *
     * A [VaccineSeries] is what the screen *reads*; this is what it *edits*. Tapping a dose in a
     * series has to reach the whole row — the lot number, the site, who gave it — and a series
     * deliberately carries only the fields that decide how a record reads back.
     */
    val immunizationsById: StateFlow<Map<String, Immunization>> = selected
        .flatMapLatest { profile ->
            if (profile == null) flowOf(emptyList()) else repo.observeImmunizations(profile.id)
        }
        .map { list -> list.associateBy { it.id } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun select(profile: Profile) = repo.selectProfile(profile.id)

    fun addAllergy(profileId: String, draft: AllergyDraft) = viewModelScope.launch {
        repo.addAllergy(
            profileId = profileId,
            substance = draft.substance,
            kind = draft.kind,
            severity = draft.severity,
            reaction = draft.reaction,
            noticedDate = draft.noticedDate,
            note = draft.note
        )
    }

    fun updateAllergy(allergy: Allergy) = viewModelScope.launch { repo.updateAllergy(allergy) }

    fun deleteAllergy(id: String) = viewModelScope.launch { repo.deleteAllergy(id) }

    fun addCondition(profileId: String, draft: ConditionDraft) = viewModelScope.launch {
        repo.addCondition(
            profileId = profileId,
            name = draft.name,
            status = draft.status,
            onsetDate = draft.onsetDate,
            resolvedDate = draft.resolvedDate,
            providerId = draft.providerId,
            monitorReadingType = draft.monitorReadingType,
            note = draft.note
        )
    }

    fun updateCondition(condition: Condition) = viewModelScope.launch { repo.updateCondition(condition) }

    fun deleteCondition(id: String) = viewModelScope.launch { repo.deleteCondition(id) }

    fun addImmunization(profileId: String, draft: ImmunizationDraft) = viewModelScope.launch {
        repo.addImmunization(
            profileId = profileId,
            vaccine = draft.vaccine,
            givenDate = draft.givenDate,
            doseNumber = draft.dose,
            source = draft.source,
            providerId = draft.providerId,
            lotNumber = draft.lotNumber,
            site = draft.site,
            note = draft.note
        )
    }

    fun updateImmunization(immunization: Immunization) =
        viewModelScope.launch { repo.updateImmunization(immunization) }

    fun deleteImmunization(id: String) = viewModelScope.launch { repo.deleteImmunization(id) }

    class Factory(private val repo: HealthRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = RecordViewModel(repo) as T
    }
}
