package com.health.app.ui.record

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.health.app.data.model.Allergy
import com.health.app.data.model.Condition
import com.health.app.data.model.Profile
import com.health.app.data.model.Provider
import com.health.app.data.model.ReadingType
import com.health.app.data.model.StandingRecord
import com.health.app.data.repository.HealthRepository
import com.health.app.logic.AllergyKind
import com.health.app.logic.AllergySeverity
import com.health.app.logic.ConditionStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
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

    class Factory(private val repo: HealthRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = RecordViewModel(repo) as T
    }
}
