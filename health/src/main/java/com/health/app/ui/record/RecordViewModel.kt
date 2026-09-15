package com.health.app.ui.record

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.health.app.HealthFileProvider
import com.health.app.data.model.Allergy
import com.health.app.data.model.Condition
import com.health.app.data.model.Document
import com.health.app.data.model.Immunization
import com.health.app.data.model.Profile
import com.health.app.data.model.Provider
import com.health.app.data.model.Reading
import com.health.app.data.model.ReadingType
import com.health.app.data.model.StandingRecord
import com.health.app.data.repository.HealthRepository
import com.health.app.data.store.DocumentStore
import com.health.app.logic.AllergyKind
import com.health.app.logic.AllergySeverity
import com.health.app.logic.ConditionStatus
import com.health.app.logic.DocumentKind
import com.health.app.logic.TempUnit
import com.health.app.logic.WeightUnit
import com.health.app.logic.Documents
import com.health.app.logic.VaccineSeries
import com.health.app.logic.VaccineSource
import com.health.app.ui.common.UndoOffer
import com.health.app.ui.common.UndoOffers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 * A file the picker has handed over but that nothing has been filed against yet.
 *
 * The two-step exists because the picker knows a file name and a size and nothing else that matters:
 * what the document *is*, what date it carries, and who it is about are all things only a person can
 * say. So the bytes are copied straight away — a URI's permission can lapse the moment the picker
 * closes — and the form is filled in over the top of an attachment that is already safely stored.
 */
data class PendingDocument(
    val stored: DocumentStore.Stored,
    val suggestedTitle: String?
)

/** What the document form collects. */
data class DocumentDraft(
    val title: String = "",
    val kind: DocumentKind = DocumentKind.OTHER,
    val documentDate: String = "",
    /** Null files the document against the household rather than against a person. */
    val profileId: String? = null,
    val note: String = ""
)

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
class RecordViewModel(
    private val repo: HealthRepository,
    private val documentStore: DocumentStore
) : ViewModel() {

    val profiles: StateFlow<List<Profile>> =
        repo.profiles.observeProfiles().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val selected: StateFlow<Profile?> =
        repo.profiles.observeSelectedProfile().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The care team, so a condition can point at the doctor who manages it. Household-scoped. */
    val providers: StateFlow<List<Provider>> =
        repo.careTeam.observeProviders().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val record: StateFlow<StandingRecord> = selected
        .flatMapLatest { profile ->
            if (profile == null) flowOf(StandingRecord.EMPTY) else repo.standingRecord.observeStandingRecord(profile.id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StandingRecord.EMPTY)

    /** Grouped into series, most recently given first — see `logic/Immunizations`. */
    val vaccineSeries: StateFlow<List<VaccineSeries>> = selected
        .flatMapLatest { profile ->
            if (profile == null) flowOf(emptyList()) else repo.immunizations.observeVaccineSeries(profile.id)
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
            if (profile == null) flowOf(emptyList()) else repo.immunizations.observeImmunizations(profile.id)
        }
        .map { list -> list.associateBy { it.id } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /**
     * The latest of each measurement, so a condition that names one can show it.
     *
     * Kept on this tab rather than read by the card, because "watched with: weight" and "17.4 kg,
     * three days ago" are the same sentence — a condition that says what matters and then can't say
     * what it is has told the household nothing it didn't already know.
     */
    val latestReadings: StateFlow<Map<ReadingType, Reading>> = selected
        .flatMapLatest { profile ->
            if (profile == null) flowOf(emptyMap()) else repo.readings.observeLatestReadings(profile.id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** The units those readings are written in — see `logic/Temperature` and `logic/Weight`. */
    val unit: StateFlow<TempUnit> =
        repo.profiles.observeTemperatureUnit().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TempUnit.CELSIUS)

    val weightUnit: StateFlow<WeightUnit> =
        repo.profiles.observeWeightUnit().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WeightUnit.KILOGRAMS)

    val documents: StateFlow<List<Document>> = selected
        .flatMapLatest { profile ->
            if (profile == null) flowOf(emptyList()) else repo.documents.observeDocuments(profile.id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The paperwork that belongs to the house rather than to anybody in it — shown to everyone. */
    val householdDocuments: StateFlow<List<Document>> =
        repo.documents.observeHouseholdDocuments()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _pendingDocument = MutableStateFlow<PendingDocument?>(null)
    val pendingDocument: StateFlow<PendingDocument?> = _pendingDocument.asStateFlow()

    private val _documentMessage = MutableStateFlow<String?>(null)
    val documentMessage: StateFlow<String?> = _documentMessage.asStateFlow()

    /** Deletes made on this tab, each with the way to put it back — see `ui/common/Undo`. */
    private val undoable = UndoOffers()
    val undoOffers: SharedFlow<UndoOffer> = undoable.offers

    fun dismissDocumentMessage() {
        _documentMessage.value = null
    }

    fun select(profile: Profile) = repo.profiles.selectProfile(profile.id)

    fun addAllergy(profileId: String, draft: AllergyDraft) = viewModelScope.launch {
        repo.standingRecord.addAllergy(
            profileId = profileId,
            substance = draft.substance,
            kind = draft.kind,
            severity = draft.severity,
            reaction = draft.reaction,
            noticedDate = draft.noticedDate,
            note = draft.note
        )
    }

    fun updateAllergy(allergy: Allergy) = viewModelScope.launch { repo.standingRecord.updateAllergy(allergy) }

    /**
     * Delete an allergy, and offer it back.
     *
     * The one row in Health that a medicine is checked against — a household that deletes it by
     * mistake loses the warning, not just the record, and would never be told it had.
     */
    fun deleteAllergy(allergy: Allergy) = viewModelScope.launch {
        undoable.offer("${allergy.substance} allergy deleted", repo.standingRecord.deleteAllergy(allergy.id))
    }

    fun addCondition(profileId: String, draft: ConditionDraft) = viewModelScope.launch {
        repo.standingRecord.addCondition(
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

    fun updateCondition(condition: Condition) = viewModelScope.launch { repo.standingRecord.updateCondition(condition) }

    fun deleteCondition(condition: Condition) = viewModelScope.launch {
        undoable.offer("${condition.name} deleted", repo.standingRecord.deleteCondition(condition.id))
    }

    fun addImmunization(profileId: String, draft: ImmunizationDraft) = viewModelScope.launch {
        repo.immunizations.addImmunization(
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
        viewModelScope.launch { repo.immunizations.updateImmunization(immunization) }

    fun deleteImmunization(immunization: Immunization) = viewModelScope.launch {
        undoable.offer("${immunization.vaccine} dose deleted", repo.immunizations.deleteImmunization(immunization.id))
    }

    fun undo(offer: UndoOffer) = viewModelScope.launch { offer.restore.undo() }

    // --- documents ---------------------------------------------------------------------------------

    /**
     * Copy a picked file into the store **before** asking anything about it.
     *
     * The order matters: the permission granted on a picker's URI can lapse as soon as the picker
     * closes, so a form that asked three questions and then tried to read the file would sometimes
     * find it gone. Copy first, ask second, and a cancelled form leaves one orphaned file rather than
     * a row pointing at nothing.
     */
    fun attach(uri: Uri) = viewModelScope.launch {
        val picked = documentStore.describe(uri)
        val stored = documentStore.save(uri, picked)
        if (stored == null) {
            _documentMessage.value = "That file couldn't be read."
            return@launch
        }
        _pendingDocument.value = PendingDocument(stored, Documents.titleFrom(picked.displayName))
    }

    /** Abandon a pending attachment, taking its already-copied file with it. */
    fun cancelPendingDocument() {
        _pendingDocument.value?.let { documentStore.delete(it.stored.fileName) }
        _pendingDocument.value = null
    }

    fun filePendingDocument(draft: DocumentDraft) = viewModelScope.launch {
        val pending = _pendingDocument.value ?: return@launch
        repo.documents.addDocument(
            title = draft.title,
            kind = draft.kind,
            fileName = pending.stored.fileName,
            profileId = draft.profileId,
            documentDate = draft.documentDate,
            mimeType = pending.stored.mimeType,
            sizeBytes = pending.stored.sizeBytes,
            note = draft.note
        )
        _pendingDocument.value = null
    }

    fun updateDocument(document: Document) = viewModelScope.launch { repo.documents.updateDocument(document) }

    fun deleteDocument(id: String) = viewModelScope.launch { repo.documents.deleteDocument(id) }

    /**
     * Hand a document to whatever on the device can open it.
     *
     * Through a copy in `cacheDir/exports` rather than by exposing the store — see
     * [DocumentStore.exportCopy] and `HealthFileProvider`. A document leaves only when somebody asks
     * it to.
     */
    fun openDocument(context: Context, document: Document) = viewModelScope.launch {
        val file = documentStore.exportCopy(document.fileName, document.title)
        if (file == null) {
            _documentMessage.value = "That file is no longer on this device."
            return@launch
        }
        val opened = runCatching {
            val uri = FileProvider.getUriForFile(context, HealthFileProvider.authority(context.packageName), file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, document.mimeType ?: "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, document.title).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            true
        }.getOrDefault(false)
        if (!opened) _documentMessage.value = "Nothing on this device offered to open that."
    }

    class Factory(
        private val repo: HealthRepository,
        private val documentStore: DocumentStore
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            RecordViewModel(repo, documentStore) as T
    }
}
