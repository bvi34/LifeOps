package com.health.app.data.repository

import com.health.app.data.db.dao.HealthDao
import com.health.app.data.db.entities.AllergyEntity
import com.health.app.data.db.entities.CabinetItemEntity
import com.health.app.data.db.entities.CareNoteEntity
import com.health.app.data.db.entities.ConditionEntity
import com.health.app.data.db.entities.DocumentEntity
import com.health.app.data.db.entities.DoseEntity
import com.health.app.data.db.entities.EpisodeEntity
import com.health.app.data.db.entities.ImmunizationEntity
import com.health.app.data.db.entities.InsuranceMemberEntity
import com.health.app.data.db.entities.InsurancePlanEntity
import com.health.app.data.db.entities.MedicationEntity
import com.health.app.data.db.entities.NetworkCheckEntity
import com.health.app.data.db.entities.ProfileEntity
import com.health.app.data.db.entities.ProfileTombstoneEntity
import com.health.app.data.db.entities.ProviderEntity
import com.health.app.data.db.entities.ProviderLinkEntity
import com.health.app.data.db.entities.ReadingEntity
import com.health.app.data.db.entities.SymptomEntity
import com.health.app.data.model.Allergy
import com.health.app.data.model.CabinetEntry
import com.health.app.data.model.CabinetItem
import com.health.app.data.model.CabinetUse
import com.health.app.data.model.CareKind
import com.health.app.data.model.CareNote
import com.health.app.data.model.CareRole
import com.health.app.data.model.CareTeamMember
import com.health.app.data.model.Condition
import com.health.app.data.model.CoverageCard
import com.health.app.data.model.Document
import com.health.app.data.model.Dose
import com.health.app.data.model.Episode
import com.health.app.data.model.Immunization
import com.health.app.data.model.InsuranceMembership
import com.health.app.data.model.InsurancePlan
import com.health.app.data.model.Medication
import com.health.app.data.model.MedicationStatus
import com.health.app.data.model.NetworkCheck
import com.health.app.data.model.Profile
import com.health.app.data.model.ProfileSnapshot
import com.health.app.data.model.Provider
import com.health.app.data.model.ProviderLink
import com.health.app.data.model.Reading
import com.health.app.data.model.ReadingType
import com.health.app.data.model.StandingRecord
import com.health.app.data.model.Symptom
import com.health.app.data.prefs.HealthPrefs
import com.health.app.logic.Age
import com.health.app.logic.Allergies
import com.health.app.logic.AllergyKind
import com.health.app.logic.AllergySeverity
import com.health.app.logic.AllergyWarning
import com.health.app.logic.Cabinet
import com.health.app.logic.CabinetFacts
import com.health.app.logic.CardFacts
import com.health.app.logic.CareLevel
import com.health.app.logic.CheckOutcome
import com.health.app.logic.ConditionStatus
import com.health.app.logic.Conditions
import com.health.app.logic.CoverageKind
import com.health.app.logic.DirectoryOutcome
import com.health.app.logic.DirectoryProbe
import com.health.app.logic.DosePoint
import com.health.app.logic.DoseRecord
import com.health.app.logic.DoseReminder
import com.health.app.logic.DocumentKind
import com.health.app.logic.Documents
import com.health.app.logic.DoseSchedule
import com.health.app.logic.DrugMonograph
import com.health.app.logic.EpisodeFacts
import com.health.app.logic.EpisodeSummaries
import com.health.app.logic.EpisodeSummary
import com.health.app.logic.Fever
import com.health.app.logic.Immunizations
import com.health.app.logic.Insurance
import com.health.app.logic.MedicationRule
import com.health.app.logic.MedicineFacts
import com.health.app.logic.NetworkCheckRecord
import com.health.app.logic.NetworkStatus
import com.health.app.logic.PlanType
import com.health.app.logic.ProviderDirectory
import com.health.app.logic.ReminderMode
import com.health.app.logic.SymptomPoint
import com.health.app.logic.TempPoint
import com.health.app.logic.VaccineSeries
import com.health.app.logic.VaccineSource
import com.health.app.logic.Temperature
import com.health.app.logic.TempSite
import com.health.app.logic.Timeline
import com.health.app.logic.TimelineDay
import com.health.app.logic.TimelineEntry
import com.health.app.logic.TimelineFacts
import com.health.app.logic.TimelineKind
import com.health.app.logic.TempUnit
import com.people.app.sync.LocalRosterChange
import com.people.app.sync.PersonBinder
import com.people.app.sync.PersonPacket
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * Health's one repository: rows in, [com.health.app.data.model] out, with every judgement delegated
 * to the framework-free `logic/` package rather than made here. This layer knows about ids,
 * timestamps and Room; it decides nothing about fevers or dose timing.
 *
 * Two behaviours are worth knowing about because they are invisible in the schema:
 *
 *  - **Open episodes capture what happens during them.** Anything logged while a person has an open
 *    illness is filed against it automatically. Nobody remembers to tick "this is part of the flu"
 *    at 3am, and an episode you have to assemble by hand afterwards is one you never assemble.
 *  - **One open episode per person.** Starting a new one closes the previous, so "how long has this
 *    been going on" always has a single answer.
 */
class HealthRepository(
    private val dao: HealthDao,
    private val prefs: HealthPrefs,
    /**
     * How the People sync seam hears that a profile changed here.
     *
     * A local profile edit stamps a new `syncVersion`, but a stamp nobody publishes is a change that
     * sits in the database until the next time Health happens to be opened — by which point the user
     * has usually gone looking for it in People or LifeOps and found the old name. Stamping the
     * version and telling the seam are the same event, so they happen in the same place rather than
     * being remembered separately at each screen.
     *
     * Fires for local edits only. [applyMergedProfile] and [createFromPacket] are writes that
     * arrived over the seam and deliberately do not call it — re-publishing them would hand the
     * other peer its own change straight back.
     */
    private val onProfileEdit: (LocalRosterChange) -> Unit = {},
    /**
     * How the reminder scheduler hears that a medicine's reminder needs re-arming.
     *
     * Called with the medicine's id after anything that can change when it should next nudge: the
     * reminder itself being set or cleared, the medicine being paused, resumed or deleted, and a
     * dose being recorded (which is what moves a "when the next dose is due" reminder). Called with
     * null to mean "re-arm everything", after a restore or when the app starts.
     *
     * A hook rather than a call into WorkManager, for the reason `logic/` exists at all: the
     * repository is JVM-testable and stays that way. The Android half lives in
     * `reminder/MedicationReminderScheduler`, wired up once in [com.health.app.HealthApp].
     */
    private val onReminderChange: (medicationId: String?) -> Unit = {},
    /**
     * How the card-image store hears that a photo is no longer referenced by anything.
     *
     * A hook for the same reason [onReminderChange] is one: the repository stays JVM-testable, and
     * deleting a file is Android I/O. Called with the file name after the row that pointed at it is
     * gone — never before, because a file deleted ahead of a write that then fails leaves a card
     * pointing at nothing.
     */
    private val onCardImageDiscarded: (fileName: String) -> Unit = {},
    /**
     * How the document store hears that a stored file is no longer referenced by any row.
     *
     * A hook for the same reason [onCardImageDiscarded] is one: the repository stays JVM-testable
     * and knows nothing about disk. Called with the file name *after* the row naming it is gone.
     */
    private val onDocumentDiscarded: (fileName: String) -> Unit = {}
) {

    private fun now() = System.currentTimeMillis()
    private fun newId() = UUID.randomUUID().toString()

    // --- profiles -----------------------------------------------------------------------------

    fun observeProfiles(): Flow<List<Profile>> = dao.observeProfiles().map { rows -> rows.map { it.toModel() } }

    fun observeAllProfiles(): Flow<List<Profile>> = dao.observeAllProfiles().map { rows -> rows.map { it.toModel() } }

    fun observeTemperatureUnit(): Flow<TempUnit> = prefs.observeTemperatureUnit()

    fun setTemperatureUnit(unit: TempUnit) {
        prefs.temperatureUnit = unit
    }

    /**
     * The person currently on screen. Falls back to the first profile when the stored selection is
     * stale (deleted, archived, or never set), so the app never opens on nobody.
     */
    fun observeSelectedProfile(): Flow<Profile?> =
        combine(observeProfiles(), prefs.observeSelectedProfileId()) { profiles, selectedId ->
            profiles.firstOrNull { it.id == selectedId } ?: profiles.firstOrNull()
        }

    fun selectProfile(profileId: String) {
        prefs.selectedProfileId = profileId
    }

    /**
     * Create a profile here and publish it as a new household member.
     *
     * **No longer reachable from Health's UI**, since Health has no household screen: profiles now
     * arrive over the seam, from somebody being ticked as a household member in People (see
     * [createFromPacket]). It is kept because it is the other half of a symmetric seam — a peer that
     * can only ever receive is a peer that cannot be tested against one that sends — and because
     * "Health may never create a person" is a product decision that could reasonably be revisited,
     * where deleting the code would be a one-way door.
     */
    suspend fun addProfile(
        name: String,
        relationship: String?,
        birthDate: String?,
        colorArgb: Long,
        baselineTempC: Double? = null,
        notes: String? = null
    ): String {
        val id = newId()
        val timestamp = now()
        dao.upsertProfile(
            ProfileEntity(
                id = id,
                name = name.trim(),
                relationship = relationship?.trim()?.ifBlank { null },
                birthDate = birthDate?.trim()?.ifBlank { null },
                colorArgb = colorArgb,
                baselineTempC = baselineTempC,
                notes = notes?.trim()?.ifBlank { null },
                // Adding somebody here *is* saying they're a household member being tracked, so the
                // directory hears the tick rather than having to be ticked separately.
                household = true,
                sortOrder = dao.nextSortOrder(),
                archived = false,
                createdAt = timestamp,
                updatedAt = timestamp,
                // Locally authored: mint a key and publish on the next round, so somebody added
                // here reaches the household directory rather than existing only in Health.
                personKey = newId(),
                syncVersion = dao.maxSyncVersion() + 1
            )
        )
        // First person in an empty app becomes the selection, so the app opens on them.
        if (prefs.selectedProfileId == null) prefs.selectedProfileId = id
        onProfileEdit(LocalRosterChange.PERSON_ADDED)
        return id
    }

    suspend fun updateProfile(profile: Profile) {
        val existing = dao.getProfile(profile.id) ?: return
        dao.upsertProfile(
            existing.copy(
                name = profile.name.trim(),
                relationship = profile.relationship?.trim()?.ifBlank { null },
                birthDate = profile.birthDate?.trim()?.ifBlank { null },
                colorArgb = profile.colorArgb,
                baselineTempC = profile.baselineTempC,
                notes = profile.notes?.trim()?.ifBlank { null },
                archived = profile.archived,
                updatedAt = now(),
                syncVersion = dao.maxSyncVersion() + 1
            )
        )
        onProfileEdit(LocalRosterChange.PERSON_EDITED)
    }

    /**
     * Remove a person and everything recorded about them (see [HealthDao.deleteProfileCascade]).
     *
     * What reaches the other peers is **not** a withdrawal. Removing somebody from Health means
     * "stop tracking their health", not "remove them from the household" — so the directory keeps
     * them and simply un-ticks them as a household member (see [ProfileTombstoneEntity]).
     *
     * That tick has to be published, and the deleted row is the very thing that would otherwise have
     * carried the news: Health's profile was created *from* the directory's tick, so without a trace
     * of the removal the next edit to that person in People brings their packet round again and
     * Health dutifully re-creates the profile that was just deleted.
     */
    /**
     * **No longer reachable from Health's UI either**, and the reason is worth writing down because
     * it is not the same reason as [addProfile]'s.
     *
     * Removing somebody is the household directory's act now. What reaches Health when People
     * deletes a person is a `deleted` packet, and `PersonMerge` turns that into an **archive** rather
     * than a cascade — deliberately, and across all three peers: losing a household member's entire
     * medical history because another app dropped a row is not a recoverable mistake, and the seam
     * says so at the point of declaration (see [com.people.app.sync.PersonPacket.deleted]). An
     * archived profile disappears from every screen here, because `observeProfiles` filters them out.
     *
     * So this cascade is the only code in the app that can actually destroy a person's medical
     * records, and nothing calls it. That is the correct number of callers until somebody decides
     * what should: a purge is a different feature from a removal, and it needs a confirmation in the
     * app that holds the data rather than a side effect in the app that doesn't.
     */
    suspend fun deleteProfile(profileId: String) {
        dao.getProfile(profileId)?.let { profile ->
            dao.upsertProfileTombstone(
                ProfileTombstoneEntity(
                    personKey = profile.personKey ?: profile.id,
                    name = profile.name,
                    removedAt = now(),
                    syncVersion = dao.maxSyncVersion() + 1
                )
            )
        }
        // Read the file names before the cascade drops the rows that name them — afterwards there is
        // nothing left to ask, and the files would sit in `documents/` for ever with no row pointing
        // at them. Household documents (profileId null) are untouched: an insurance statement is not
        // about the person who has left.
        val orphanedFiles = dao.documentFileNamesForProfile(profileId)
        dao.deleteProfileCascade(profileId)
        orphanedFiles.forEach(onDocumentDiscarded)
        if (prefs.selectedProfileId == profileId) {
            prefs.selectedProfileId = dao.getProfiles().firstOrNull { !it.archived }?.id
        }
        onProfileEdit(LocalRosterChange.PERSON_EDITED)
    }

    // --- the People sync seam ---
    //
    // Health grows a profile for a **household member** and for nobody else, and which is which is
    // the directory's answer to give: you tick somebody as household in People and Health picks them
    // up, birth date and all (the fever rules are age-aware, so that date is the whole point).
    // Everyone else in the envelope is left alone — a roster that silently grew a medical profile
    // for every adult in the house would be worse than no sync at all.
    //
    // Removing somebody here means "stop tracking their health", not "remove them from the
    // household": it un-ticks them and leaves the directory's record of them intact.
    //
    // As everywhere on this seam: a local edit stamps a new syncVersion, a write that arrived over
    // the seam does not.

    /** Everything edited locally since [sinceVersion], as packets, oldest first. */
    suspend fun profileChangesSince(sinceVersion: Long): List<Pair<Long, PersonPacket>> {
        val edits = dao.profilesChangedSince(sinceVersion).map { it.syncVersion to it.toPacket() }
        val removals = dao.profileTombstonesSince(sinceVersion).map { tombstone ->
            tombstone.syncVersion to PersonPacket(
                personKey = tombstone.personKey,
                name = tombstone.name,
                // Un-tick, don't withdraw: they are still in the household, Health has just stopped
                // tracking them. `deleted` would have every peer archive them.
                household = false,
                updatedAt = tombstone.removedAt
            )
        }
        return (edits + removals).sortedBy { it.first }
    }

    suspend fun currentSyncVersion(): Long = dao.maxSyncVersion()

    suspend fun bindingCandidates(): List<PersonBinder.Candidate> =
        dao.getProfiles().map { PersonBinder.Candidate(it.id, it.personKey, it.name, email = null) }

    suspend fun profileEntity(id: String): ProfileEntity? = dao.getProfile(id)

    /**
     * Store a record merged from another peer, without stamping a new outgoing version.
     *
     * Only the identity fields are touched. [ProfileEntity.notes] is pointedly absent — Health's
     * notes are medical and People's are not, so the seam neither publishes nor accepts them — and
     * so are the baseline temperature and colour, which no other peer can show or edit.
     */
    suspend fun applyMergedProfile(localId: String, packet: PersonPacket) {
        val existing = dao.getProfile(localId) ?: return
        val key = adoptableKey(existing.personKey, packet.personKey)
        // A profile exists for this key again, so an old removal must stop speaking for it —
        // otherwise Health would keep publishing "un-tick them" about somebody it is now tracking.
        dao.deleteProfileTombstone(key)
        dao.upsertProfile(
            existing.copy(
                personKey = key,
                name = packet.name,
                relationship = packet.relationship,
                birthDate = packet.birthDate,
                // A peer with no column for the flag (LifeOps) says nothing about it; only an
                // explicit answer moves it. Un-ticking never deletes the profile — see the note on
                // [ProfileTombstoneEntity] for why that asymmetry is deliberate.
                household = packet.household ?: existing.household,
                archived = packet.archived,
                updatedAt = packet.updatedAt
            )
        )
    }

    /**
     * Grow a profile for somebody the directory has marked as a household member.
     *
     * Only reached for a packet the creation policy accepted, so the decision has already been made
     * elsewhere; this just records it. What arrives is identity — the name, the relationship and the
     * **birth date** the age-aware fever rules need. Everything that makes it a *medical* profile —
     * the baseline temperature, the notes — starts empty, because no other peer has an opinion about
     * those and this seam is not going to invent one.
     */
    suspend fun createFromPacket(packet: PersonPacket): String {
        val id = newId()
        dao.deleteProfileTombstone(packet.personKey)
        dao.upsertProfile(
            ProfileEntity(
                id = id,
                personKey = packet.personKey,
                // Not a local edit: it does not go back out.
                syncVersion = 0L,
                name = packet.name,
                relationship = packet.relationship,
                birthDate = packet.birthDate,
                colorArgb = PROFILE_COLORS[dao.profileCount() % PROFILE_COLORS.size],
                baselineTempC = null,
                notes = null,
                household = true,
                sortOrder = dao.nextSortOrder(),
                archived = packet.archived,
                createdAt = now(),
                updatedAt = packet.updatedAt
            )
        )
        if (prefs.selectedProfileId == null) prefs.selectedProfileId = id
        return id
    }

    /**
     * The key this row should now carry.
     *
     * [PersonMerge] converges the two peers onto the lower key, but a peer can only adopt it if no
     * *other* local row already holds it — otherwise two people here would share one identity, and
     * on the next round each would bind to whichever the query returned first. When the key is
     * taken, the row keeps its own and the peers go on binding by name, which is weaker but correct.
     */
    private suspend fun adoptableKey(current: String?, incoming: String): String =
        if (incoming == current) incoming
        else if (dao.getProfileByKey(incoming) == null) incoming
        else current ?: incoming

    // --- readings -----------------------------------------------------------------------------

    fun observeReadings(profileId: String): Flow<List<Reading>> =
        dao.observeReadings(profileId).map { rows -> rows.map { it.toModel() } }

    fun observeTemperatures(profileId: String): Flow<List<Reading>> =
        dao.observeReadingsOfType(profileId, ReadingType.TEMPERATURE.key).map { rows -> rows.map { it.toModel() } }

    /**
     * Record a temperature. [celsius] is canonical — callers convert from whatever the user typed
     * via `logic/Temperature`, which also rejects impossible values before they reach here.
     */
    suspend fun logTemperature(
        profileId: String,
        celsius: Double,
        site: TempSite,
        takenAt: Long = now(),
        note: String? = null
    ): String = logReading(
        profileId = profileId,
        type = ReadingType.TEMPERATURE,
        value = celsius,
        site = site,
        takenAt = takenAt,
        note = note
    )

    suspend fun logReading(
        profileId: String,
        type: ReadingType,
        value: Double,
        secondaryValue: Double? = null,
        site: TempSite? = null,
        takenAt: Long = now(),
        note: String? = null
    ): String {
        val id = newId()
        dao.upsertReading(
            ReadingEntity(
                id = id,
                profileId = profileId,
                episodeId = episodeIdAt(profileId, takenAt),
                type = type.key,
                value = value,
                secondaryValue = secondaryValue,
                site = site?.key,
                takenAt = takenAt,
                note = note?.trim()?.ifBlank { null },
                createdAt = now()
            )
        )
        return id
    }

    /**
     * The episode a record belongs to, decided by **when the record happened** rather than by what
     * happens to be open when it is typed in.
     *
     * The two answers agree for anything recorded as it happens, and disagree the moment the history
     * is filled in afterwards — which it now can be. Last night's dose, typed up over breakfast,
     * belongs to last night's illness; a week of last month's flu, reconstructed from memory,
     * belongs to last month's episode and not to today's cold. Filing by "what's open now" would put
     * all of it in the wrong story, and an episode summary is only worth reading if the rows under it
     * actually happened during it.
     */
    private suspend fun episodeIdAt(profileId: String, atMillis: Long): String? =
        dao.getEpisodeAt(profileId, atMillis)?.id

    suspend fun deleteReading(id: String) = dao.deleteReading(id)

    // --- symptoms -----------------------------------------------------------------------------

    fun observeSymptoms(profileId: String): Flow<List<Symptom>> =
        dao.observeSymptoms(profileId).map { rows -> rows.map { it.toModel() } }

    suspend fun addSymptom(
        profileId: String,
        name: String,
        severity: Int,
        startedAt: Long = now(),
        note: String? = null
    ): String {
        val id = newId()
        dao.upsertSymptom(
            SymptomEntity(
                id = id,
                profileId = profileId,
                episodeId = episodeIdAt(profileId, startedAt),
                name = name.trim(),
                severity = severity.coerceIn(1, 5),
                startedAt = startedAt,
                endedAt = null,
                note = note?.trim()?.ifBlank { null },
                createdAt = now()
            )
        )
        return id
    }

    /** Mark a symptom as over — or, with [endedAt] null, as still going after all. */
    suspend fun setSymptomEnded(symptomId: String, endedAt: Long? = now()) {
        val row = dao.getSymptom(symptomId) ?: return
        dao.upsertSymptom(row.copy(endedAt = endedAt))
    }

    suspend fun deleteSymptom(id: String) = dao.deleteSymptom(id)

    // --- medications and doses ----------------------------------------------------------------

    fun observeMedications(profileId: String): Flow<List<Medication>> =
        dao.observeMedications(profileId).map { rows -> rows.map { it.toModel() } }

    /**
     * Each of a person's medicines with its current dose window. The dose query is windowed to the
     * last 24 hours because that is the only span any limit is written against — a person with years
     * of dose history should not re-read all of it to answer "can I give it yet?". The bound is
     * fixed when the flow is built, so a screen left open overnight reads a few rows that have since
     * aged out; that costs nothing, because [DoseSchedule.evaluate] applies the real window itself
     * against the current time rather than trusting the query's.
     */
    fun observeMedicationStatuses(profileId: String): Flow<List<MedicationStatus>> =
        combine(
            dao.observeMedications(profileId),
            dao.observeDosesSince(profileId, now() - DoseSchedule.WINDOW_MS),
            dao.observeCabinetItems(),
            dao.observeAllDrugFacts()
        ) { medications, doses, cabinet, facts ->
            val nowMillis = now()
            val byId = cabinet.associateBy { it.id }
            val monographs = facts.associate { it.rxcui to DrugFactsMapper.toMonograph(it) }
            medications.map { medication ->
                val history = doses
                    .filter { it.medicationId == medication.id }
                    .map { DoseRecord(it.takenAt, it.amount) }
                val item = medication.cabinetItemId?.let { byId[it] }
                MedicationStatus(
                    medication = medication.toModel(),
                    window = DoseSchedule.evaluate(medication.toRule(), history, nowMillis),
                    cabinetItem = item?.toModel(),
                    cabinetStatus = item?.let {
                        Cabinet.assess(it.toFacts(medication.doseAmount, medication.doseUnit))
                    },
                    monograph = medication.rxcui?.let { monographs[it] }
                )
            }
        }

    suspend fun addMedication(
        profileId: String,
        name: String,
        strength: String?,
        form: String?,
        doseAmount: Double?,
        doseUnit: String,
        minIntervalHours: Double?,
        maxDosesPer24h: Int?,
        maxAmountPer24h: Double?,
        note: String? = null,
        rxcui: String? = null,
        cabinetItemId: String? = null,
        reminderMode: ReminderMode = ReminderMode.OFF,
        reminderTimes: List<java.time.LocalTime> = emptyList()
    ): String {
        val id = newId()
        dao.upsertMedication(
            MedicationEntity(
                id = id,
                profileId = profileId,
                name = name.trim(),
                strength = strength?.trim()?.ifBlank { null },
                form = form?.trim()?.ifBlank { null },
                doseAmount = doseAmount,
                doseUnit = doseUnit.trim(),
                minIntervalHours = minIntervalHours,
                maxDosesPer24h = maxDosesPer24h,
                maxAmountPer24h = maxAmountPer24h,
                note = note?.trim()?.ifBlank { null },
                active = true,
                createdAt = now(),
                rxcui = rxcui?.trim()?.ifBlank { null },
                cabinetItemId = cabinetItemId,
                reminderMode = reminderMode.key,
                reminderTimes = DoseReminder.formatTimes(reminderTimes).ifBlank { null }
            )
        )
        onReminderChange(id)
        return id
    }

    suspend fun updateMedication(medication: Medication) {
        val existing = dao.getMedication(medication.id) ?: return
        dao.upsertMedication(
            existing.copy(
                name = medication.name.trim(),
                strength = medication.strength?.trim()?.ifBlank { null },
                form = medication.form?.trim()?.ifBlank { null },
                doseAmount = medication.doseAmount,
                doseUnit = medication.doseUnit.trim(),
                minIntervalHours = medication.minIntervalHours,
                maxDosesPer24h = medication.maxDosesPer24h,
                maxAmountPer24h = medication.maxAmountPer24h,
                note = medication.note?.trim()?.ifBlank { null },
                active = medication.active,
                rxcui = medication.rxcui?.trim()?.ifBlank { null },
                cabinetItemId = medication.cabinetItemId,
                reminderMode = medication.reminderMode.key,
                reminderTimes = DoseReminder.formatTimes(medication.reminderTimes).ifBlank { null }
            )
        )
        onReminderChange(medication.id)
    }

    /**
     * Set (or clear) a medicine's reminder without touching anything else about it.
     *
     * Its own method rather than a field on [updateMedication] because it is reached from a
     * different place — a switch on the card, not the edit form — and because clearing the times
     * along with the mode is the only correct way to turn a set-times reminder off. Leaving them
     * behind would quietly re-arm the old schedule the next time somebody switched it back on.
     */
    suspend fun setMedicationReminder(
        medicationId: String,
        mode: ReminderMode,
        times: List<java.time.LocalTime> = emptyList()
    ) {
        val existing = dao.getMedication(medicationId) ?: return
        dao.upsertMedication(
            existing.copy(
                reminderMode = mode.key,
                reminderTimes = if (mode == ReminderMode.FIXED_TIMES) {
                    DoseReminder.formatTimes(times).ifBlank { null }
                } else {
                    null
                }
            )
        )
        onReminderChange(medicationId)
    }

    suspend fun deleteMedication(id: String) {
        dao.deleteMedication(id)
        onReminderChange(id)
    }

    fun observeDoses(profileId: String): Flow<List<Dose>> =
        dao.observeDoses(profileId).map { rows -> rows.map { it.toModel() } }

    /**
     * Record a dose. The medicine's name is copied onto the row rather than referenced, so the
     * history stays readable after the medicine itself is renamed or removed.
     */
    suspend fun logDose(
        profileId: String,
        medicationId: String?,
        medicationName: String,
        amount: Double,
        unit: String,
        takenAt: Long = now(),
        note: String? = null
    ): String {
        val id = newId()
        dao.upsertDose(
            DoseEntity(
                id = id,
                profileId = profileId,
                medicationId = medicationId,
                medicationName = medicationName.trim(),
                amount = amount,
                unit = unit.trim(),
                takenAt = takenAt,
                note = note?.trim()?.ifBlank { null },
                episodeId = episodeIdAt(profileId, takenAt),
                createdAt = now()
            )
        )
        medicationId?.let { medication ->
            drawFromCabinet(medication, amount, unit)
            // A "when the next dose is due" reminder is measured from the last dose, so the dose
            // that was just given is exactly the event that moves it.
            onReminderChange(medication)
        }
        return id
    }

    /**
     * Take a dose out of the bottle it came from, when the household is tracking that bottle.
     *
     * Only when the dose and the stock are written in the same unit — 15 mL out of 120 mL is
     * arithmetic, 15 mL out of "1 bottle" is a guess, and a stock count that quietly invents its own
     * conversions is worse than no stock count at all. Where the units differ the dose is still
     * recorded in full; only the deduction is skipped.
     *
     * The stock floors at zero rather than going negative: a bottle that has run out has run out,
     * and a negative quantity on screen reads as a bug rather than as "you've used more than you
     * told me you had".
     */
    private suspend fun drawFromCabinet(medicationId: String, amount: Double, unit: String) {
        if (amount <= 0.0) return
        val medication = dao.getMedication(medicationId) ?: return
        val itemId = medication.cabinetItemId ?: return
        val item = dao.getCabinetItem(itemId) ?: return
        val quantity = item.quantity ?: return
        if (!Cabinet.sameUnit(item.quantityUnit, unit)) return
        dao.upsertCabinetItem(
            item.copy(
                quantity = (quantity - amount).coerceAtLeast(0.0),
                updatedAt = now()
            )
        )
    }

    /** Give the medicine its own default dose — the one-tap path from the Today screen. */
    suspend fun logDoseOf(medication: Medication, takenAt: Long = now(), note: String? = null): String =
        logDose(
            profileId = medication.profileId,
            medicationId = medication.id,
            medicationName = medication.name,
            amount = medication.doseAmount ?: 0.0,
            unit = medication.doseUnit,
            takenAt = takenAt,
            note = note
        )

    /**
     * Remove a dose that was never given — a mis-tap, or a dose recorded twice because two people
     * both reached for the phone.
     *
     * Deleting it puts the stock back, on the same same-unit rule [drawFromCabinet] deducts under.
     * The inverse has to exist: without it, a household that fat-fingers one dose is left with a
     * bottle that Health believes is emptier than it is, and no way to say otherwise except by
     * re-typing the quantity.
     */
    suspend fun deleteDose(id: String) {
        val dose = dao.getDose(id)
        dao.deleteDose(id)
        val medicationId = dose?.medicationId ?: return
        returnToCabinet(medicationId, dose.amount, dose.unit)
        onReminderChange(medicationId)
    }

    /** The inverse of [drawFromCabinet], under exactly the same same-unit rule. */
    private suspend fun returnToCabinet(medicationId: String, amount: Double, unit: String) {
        if (amount <= 0.0) return
        val medication = dao.getMedication(medicationId) ?: return
        val itemId = medication.cabinetItemId ?: return
        val item = dao.getCabinetItem(itemId) ?: return
        val quantity = item.quantity ?: return
        if (!Cabinet.sameUnit(item.quantityUnit, unit)) return
        dao.upsertCabinetItem(item.copy(quantity = quantity + amount, updatedAt = now()))
    }

    // --- the medicine cabinet -------------------------------------------------------------------
    //
    // Household-scoped, deliberately. A bottle is a possession and a drug label is a fact about a
    // product; neither varies by who is taking it. Everything per-person stays on `medications`,
    // which points here.

    fun observeCabinetItems(): Flow<List<CabinetItem>> =
        dao.observeCabinetItems().map { rows -> rows.map { it.toModel() } }

    /**
     * The cabinet as it is actually read: every item with its expiry and stock verdicts, the label
     * Health cached for it, and each person who takes it with their own dose and their own live dose
     * window.
     *
     * The last part is the whole point of the tab. Standing in front of a bottle, the question is
     * almost never "what is this" — it is "how much of this does *she* get, and can she have some
     * yet". Assembling that here means one flow answers it for every person at once, rather than
     * five screens each re-deriving it and disagreeing at the edges.
     *
     * Items sort by [com.health.app.logic.CabinetStatus.sortRank]: expired first, then out of stock,
     * then expiring soon, then running low, then everything that is simply fine. A cabinet is read
     * top-down when something is wrong and searched by name when nothing is, so the ordering serves
     * the first case and the name sort inside each rank serves the second.
     */
    fun observeCabinet(): Flow<List<CabinetEntry>> =
        combine(
            dao.observeCabinetItems(),
            dao.observeProfiles(),
            dao.observeAllDrugFacts(),
            dao.observeAllMedications(),
            dao.observeAllDosesSince(now() - DoseSchedule.WINDOW_MS)
        ) { items, profiles, facts, medications, doses ->
            val nowMillis = now()
            val profilesById = profiles.associateBy { it.id }
            val monographs = facts.associate { it.rxcui to DrugFactsMapper.toMonograph(it) }

            items.map { item ->
                val uses = medications
                    .filter { it.cabinetItemId == item.id }
                    .mapNotNull { medication ->
                        val profile = profilesById[medication.profileId] ?: return@mapNotNull null
                        val history = doses
                            .filter { it.medicationId == medication.id }
                            .map { DoseRecord(it.takenAt, it.amount) }
                        CabinetUse(
                            profile = profile.toModel(),
                            medication = medication.toModel(),
                            window = DoseSchedule.evaluate(medication.toRule(), history, nowMillis)
                        )
                    }
                    .sortedBy { it.profile.sortOrder }

                // Stock is measured against a dose, and different people take different doses. The
                // item's own verdict uses the largest of them — the one that runs the bottle down
                // first — so "enough for one more dose" is never a promise made about the wrong
                // person. With nobody assigned there is no dose to measure against, and the stock
                // is simply reported as it stands.
                val reference = uses.maxByOrNull { it.medication.doseAmount ?: 0.0 }?.medication
                val status = Cabinet.assess(
                    item.toFacts(reference?.doseAmount, reference?.doseUnit.orEmpty())
                )

                CabinetEntry(
                    item = item.toModel(),
                    status = status,
                    monograph = item.rxcui?.let { monographs[it] },
                    takenBy = uses
                )
            }.sortedWith(compareBy({ it.status.sortRank }, { it.item.name.lowercase() }))
        }

    suspend fun getCabinetItem(id: String): CabinetItem? = dao.getCabinetItem(id)?.toModel()

    /**
     * Put something in the cabinet. Only the name is required — a cabinet that has to be filled in
     * completely is a cabinet that stays empty, and "there's Calpol in the bathroom" is already
     * worth more than nothing.
     */
    suspend fun addCabinetItem(
        name: String,
        rxcui: String? = null,
        brandName: String? = null,
        strength: String? = null,
        form: String? = null,
        quantity: Double? = null,
        quantityUnit: String = "",
        expiryDate: String? = null,
        location: String? = null,
        lowStockThreshold: Double? = null,
        note: String? = null
    ): String {
        val id = newId()
        val stamp = now()
        dao.upsertCabinetItem(
            CabinetItemEntity(
                id = id,
                rxcui = rxcui?.trim()?.ifBlank { null },
                name = name.trim(),
                brandName = brandName?.trim()?.ifBlank { null },
                strength = strength?.trim()?.ifBlank { null },
                form = form?.trim()?.ifBlank { null },
                quantity = quantity,
                quantityUnit = quantityUnit.trim(),
                expiryDate = expiryDate?.trim()?.ifBlank { null },
                location = location?.trim()?.ifBlank { null },
                lowStockThreshold = lowStockThreshold,
                note = note?.trim()?.ifBlank { null },
                createdAt = stamp,
                updatedAt = stamp
            )
        )
        return id
    }

    suspend fun updateCabinetItem(item: CabinetItem) {
        val existing = dao.getCabinetItem(item.id) ?: return
        dao.upsertCabinetItem(
            existing.copy(
                rxcui = item.rxcui?.trim()?.ifBlank { null },
                name = item.name.trim(),
                brandName = item.brandName?.trim()?.ifBlank { null },
                strength = item.strength?.trim()?.ifBlank { null },
                form = item.form?.trim()?.ifBlank { null },
                quantity = item.quantity,
                quantityUnit = item.quantityUnit.trim(),
                expiryDate = item.expiryDate?.trim()?.ifBlank { null },
                location = item.location?.trim()?.ifBlank { null },
                lowStockThreshold = item.lowStockThreshold,
                note = item.note?.trim()?.ifBlank { null },
                updatedAt = now()
            )
        )
    }

    /**
     * Restock — a new bottle of the same thing. Sets the quantity outright rather than adding to it
     * and takes the new box's expiry date, because that is what actually happened: the old bottle is
     * gone and this is a different one. Adding would carry the old bottle's remaining 20 mL into a
     * new bottle it is not in.
     */
    suspend fun restockCabinetItem(id: String, quantity: Double?, expiryDate: String?) {
        val existing = dao.getCabinetItem(id) ?: return
        dao.upsertCabinetItem(
            existing.copy(
                quantity = quantity,
                expiryDate = expiryDate?.trim()?.ifBlank { null },
                updatedAt = now()
            )
        )
    }

    /**
     * Throw a bottle away, keeping every medicine given from it and every dose recorded against it.
     * Binning the box does not mean the child stopped taking the medicine, and it certainly does not
     * un-happen the doses.
     */
    suspend fun deleteCabinetItem(id: String) {
        dao.deleteCabinetItemKeepingMedications(id)
        dao.pruneUnreferencedDrugFacts()
    }

    /** Point a person's medicine at a bottle — or, with null, stop tracking its stock. */
    suspend fun linkMedicationToCabinet(medicationId: String, cabinetItemId: String?) {
        val existing = dao.getMedication(medicationId) ?: return
        dao.upsertMedication(existing.copy(cabinetItemId = cabinetItemId))
    }

    // --- looked-up drug facts ---------------------------------------------------------------------

    /** The cached monograph for a product, if Health has ever looked it up. */
    suspend fun getMonograph(rxcui: String): DrugMonograph? =
        dao.getDrugFacts(rxcui)?.let { DrugFactsMapper.toMonograph(it) }

    fun observeMonograph(rxcui: String): Flow<DrugMonograph?> =
        dao.observeDrugFacts(rxcui).map { row -> row?.let { DrugFactsMapper.toMonograph(it) } }

    /**
     * Cache what a lookup found. Upserts by RxNorm concept id, so a refresh replaces the previous
     * answer for everybody who has that product rather than accumulating copies of it.
     */
    suspend fun saveMonograph(monograph: DrugMonograph) {
        val rxcui = monograph.rxcui?.trim()?.ifBlank { null } ?: return
        dao.upsertDrugFacts(DrugFactsMapper.toEntity(monograph, rxcui))
    }

    // --- episodes -----------------------------------------------------------------------------

    fun observeEpisodes(profileId: String): Flow<List<Episode>> =
        dao.observeEpisodes(profileId).map { rows -> rows.map { it.toModel() } }

    fun observeOpenEpisode(profileId: String): Flow<Episode?> =
        dao.observeOpenEpisode(profileId).map { it?.toModel() }

    /**
     * Start an illness. Any episode still open for this person is closed first — one open episode
     * per person, so "how long has this been going on" has exactly one answer.
     *
     * Readings, symptoms and doses already recorded in the [backfillWindowMillis] before it started
     * are adopted into it: an illness is nearly always noticed after the first temperature was taken,
     * and making someone re-enter that reading is how records stop being kept.
     */
    suspend fun startEpisode(
        profileId: String,
        title: String,
        startedAt: Long = now(),
        note: String? = null,
        backfillWindowMillis: Long = DEFAULT_BACKFILL_MS
    ): String {
        dao.getOpenEpisode(profileId)?.let { open ->
            dao.upsertEpisode(open.copy(endedAt = startedAt, updatedAt = now()))
        }
        val id = newId()
        val timestamp = now()
        dao.upsertEpisode(
            EpisodeEntity(
                id = id,
                profileId = profileId,
                title = title.trim().ifBlank { "Illness" },
                startedAt = startedAt,
                endedAt = null,
                note = note?.trim()?.ifBlank { null },
                createdAt = timestamp,
                updatedAt = timestamp
            )
        )
        adoptRecentRecords(profileId, id, since = startedAt - backfillWindowMillis)
        return id
    }

    /**
     * File this person's unattached records in a span against an episode.
     *
     * Only records with no episode of their own — anything already filed under another illness stays
     * where it is. That is what keeps this safe to run when an episode's dates change: widening a
     * span cannot steal last month's flu's records into this month's cold.
     *
     * [until] matters once episodes can be backdated. Adopting everything after a start date would,
     * for an illness entered a fortnight late, sweep up two weeks of unrelated records that happened
     * after it was over.
     */
    private suspend fun adoptRecentRecords(
        profileId: String,
        episodeId: String,
        since: Long,
        until: Long = Long.MAX_VALUE
    ) {
        dao.getAllReadings()
            .filter { it.profileId == profileId && it.episodeId == null && it.takenAt in since..until }
            .forEach { dao.upsertReading(it.copy(episodeId = episodeId)) }
        dao.getAllSymptoms()
            .filter { it.profileId == profileId && it.episodeId == null && it.startedAt in since..until }
            .forEach { dao.upsertSymptom(it.copy(episodeId = episodeId)) }
        dao.getAllDoses()
            .filter { it.profileId == profileId && it.episodeId == null && it.takenAt in since..until }
            .forEach { dao.upsertDose(it.copy(episodeId = episodeId)) }
        dao.getAllCareNotes()
            .filter { it.profileId == profileId && it.episodeId == null && it.at in since..until }
            .forEach { dao.upsertCareNote(it.copy(episodeId = episodeId)) }
    }

    /**
     * Move an illness's dates — which is how one that was never recorded at the time gets entered at
     * all. "We had the flu the first week of March" is an episode with both ends in the past, and
     * without this there is no way to say so.
     *
     * Re-files as it goes, in both directions, because the span *is* what decides membership:
     * unattached records that now fall inside are adopted, and records filed under this episode that
     * now fall outside are released rather than left claiming to have happened during an illness
     * they no longer overlap. Records belonging to another illness are never touched.
     */
    suspend fun setEpisodeDates(episodeId: String, startedAt: Long, endedAt: Long?) {
        val episode = dao.getEpisode(episodeId) ?: return
        dao.upsertEpisode(episode.copy(startedAt = startedAt, endedAt = endedAt, updatedAt = now()))

        val until = endedAt ?: Long.MAX_VALUE
        releaseRecordsOutside(episodeId, startedAt, until)
        adoptRecentRecords(episode.profileId, episodeId, since = startedAt, until = until)
    }

    /** Detach this episode's records that no longer fall inside its span. */
    private suspend fun releaseRecordsOutside(episodeId: String, from: Long, until: Long) {
        dao.getReadingsForEpisode(episodeId)
            .filterNot { it.takenAt in from..until }
            .forEach { dao.upsertReading(it.copy(episodeId = null)) }
        dao.getSymptomsForEpisode(episodeId)
            .filterNot { it.startedAt in from..until }
            .forEach { dao.upsertSymptom(it.copy(episodeId = null)) }
        dao.getDosesForEpisode(episodeId)
            .filterNot { it.takenAt in from..until }
            .forEach { dao.upsertDose(it.copy(episodeId = null)) }
        dao.getCareNotesForEpisode(episodeId)
            .filterNot { it.at in from..until }
            .forEach { dao.upsertCareNote(it.copy(episodeId = null)) }
    }

    /** Close an episode, and resolve anything still marked active inside it. */
    suspend fun endEpisode(episodeId: String, endedAt: Long = now()) {
        val episode = dao.getEpisode(episodeId) ?: return
        dao.upsertEpisode(episode.copy(endedAt = endedAt, updatedAt = now()))
        dao.getSymptomsForEpisode(episodeId)
            .filter { it.endedAt == null }
            .forEach { dao.upsertSymptom(it.copy(endedAt = endedAt)) }
    }

    suspend fun reopenEpisode(episodeId: String) {
        val episode = dao.getEpisode(episodeId) ?: return
        dao.getOpenEpisode(episode.profileId)
            ?.takeIf { it.id != episodeId }
            ?.let { dao.upsertEpisode(it.copy(endedAt = now(), updatedAt = now())) }
        dao.upsertEpisode(episode.copy(endedAt = null, updatedAt = now()))
    }

    /** Delete the episode, keeping every reading, symptom and dose that was filed under it. */
    suspend fun deleteEpisode(episodeId: String) = dao.deleteEpisodeKeepingRecords(episodeId)

    /** Read one episode back as the summary in `logic/EpisodeSummary` — the whole story, assessed. */
    suspend fun summarizeEpisode(episodeId: String, nowMillis: Long = now()): EpisodeSummary? {
        val episode = dao.getEpisode(episodeId) ?: return null
        val profile = dao.getProfile(episode.profileId)
        return EpisodeSummaries.summarize(
            EpisodeFacts(
                title = episode.title,
                startedAtMillis = episode.startedAt,
                endedAtMillis = episode.endedAt,
                ageMonths = profile?.toModel()?.ageMonthsAt(nowMillis),
                temps = dao.getReadingsForEpisode(episodeId)
                    .filter { it.type == ReadingType.TEMPERATURE.key }
                    .map { TempPoint(it.takenAt, it.value, TempSite.fromKey(it.site)) },
                symptoms = dao.getSymptomsForEpisode(episodeId)
                    .map { SymptomPoint(it.name, it.severity, it.startedAt, it.endedAt) },
                doses = dao.getDosesForEpisode(episodeId)
                    .map { DosePoint(it.medicationName, it.takenAt, it.amount, it.unit) }
            ),
            nowMillis
        )
    }

    // --- care notes ---------------------------------------------------------------------------

    fun observeCareNotes(profileId: String): Flow<List<CareNote>> =
        dao.observeCareNotes(profileId).map { rows -> rows.map { it.toModel() } }

    suspend fun addCareNote(
        profileId: String,
        kind: CareKind,
        text: String,
        at: Long = now()
    ): String {
        val id = newId()
        dao.upsertCareNote(
            CareNoteEntity(
                id = id,
                profileId = profileId,
                episodeId = episodeIdAt(profileId, at),
                kind = kind.key,
                text = text.trim(),
                at = at,
                createdAt = now()
            )
        )
        return id
    }

    suspend fun deleteCareNote(id: String) = dao.deleteCareNote(id)

    // --- the history --------------------------------------------------------------------------

    /**
     * Everything that was done for one illness, in the order it was done.
     *
     * The other half of reading an episode back. `summarizeEpisode` answers *how did it go*; this
     * answers *what actually happened, and when* — which is the question a doctor asks, and the one
     * the person who was up all three nights cannot answer from memory.
     *
     * Read once on demand rather than observed, like the summary: it folds four tables, which is
     * worth doing when somebody opens the history and not worth redoing on every unrelated write.
     * The display unit is read the same way, at the same moment, so the temperatures in the history
     * are written in the scale the rest of the app is showing.
     */
    suspend fun episodeHistory(episodeId: String): List<TimelineDay> {
        val episode = dao.getEpisode(episodeId) ?: return emptyList()
        val profile = dao.getProfile(episode.profileId)
        val ageMonths = profile?.birthDate?.let { Age.monthsAt(it, now()) }
        val unit = prefs.temperatureUnit

        return Timeline.build(
            TimelineFacts(
                readings = dao.getReadingsForEpisode(episodeId).map { it.toTimelineEntry(ageMonths, unit) },
                symptoms = dao.getSymptomsForEpisode(episodeId).flatMap { it.toTimelineEntries() },
                doses = dao.getDosesForEpisode(episodeId).map { it.toTimelineEntry() },
                careNotes = dao.getCareNotesForEpisode(episodeId).map { it.toTimelineEntry() },
                episodeStartedAtMillis = episode.startedAt,
                episodeEndedAtMillis = episode.endedAt,
                episodeTitle = episode.title
            )
        )
    }

    /**
     * The same history for a person over a window, illness or no illness.
     *
     * Not everything worth reconstructing happened during a declared episode — the week of bad
     * headaches nobody called an illness, the doses given before anyone thought to start one — and a
     * history that could only be read inside an episode would quietly lose all of it. Days are not
     * numbered here, because there is no day one to count from.
     */
    suspend fun profileHistory(profileId: String, sinceMillis: Long): List<TimelineDay> {
        val profile = dao.getProfile(profileId)
        val ageMonths = profile?.birthDate?.let { Age.monthsAt(it, now()) }
        val unit = prefs.temperatureUnit

        return Timeline.build(
            TimelineFacts(
                readings = dao.getReadingsSince(profileId, sinceMillis).map { it.toTimelineEntry(ageMonths, unit) },
                symptoms = dao.getSymptomsSince(profileId, sinceMillis).flatMap { it.toTimelineEntries() },
                doses = dao.getDosesSince(profileId, sinceMillis).map { it.toTimelineEntry() },
                careNotes = dao.getCareNotesSince(profileId, sinceMillis).map { it.toTimelineEntry() }
            )
        )
    }

    // --- the headline -------------------------------------------------------------------------

    /**
     * Everything the Today screen shows for one person, assembled from four live sources so the
     * screen re-reads nothing itself. The care level is the highest call any part of it makes.
     */
    fun observeSnapshot(profile: Profile): Flow<ProfileSnapshot> =
        combine(
            dao.observeLatestOfType(profile.id, ReadingType.TEMPERATURE.key),
            dao.observeOpenEpisode(profile.id),
            dao.observeSymptoms(profile.id),
            observeMedicationStatuses(profile.id)
        ) { latestTemp, openEpisode, symptoms, medications ->
            val nowMillis = now()
            val reading = latestTemp?.toModel()
            val assessment = reading?.let {
                Fever.assess(it.value, it.site ?: TempSite.ORAL, profile.ageMonthsAt(nowMillis))
            }
            ProfileSnapshot(
                profile = profile,
                latestTemperature = reading,
                temperatureAssessment = assessment,
                openEpisode = openEpisode?.toModel(),
                activeSymptoms = symptoms.filter { it.endedAt == null }
                    .map { it.toModel() }
                    .sortedByDescending { it.severity },
                medications = medications,
                careLevel = assessment?.careLevel ?: CareLevel.ROUTINE
            )
        }

    /** One person's current snapshot, read once — used by the Advisor bridge, which is not reactive. */
    suspend fun snapshotOnce(profile: Profile): ProfileSnapshot = observeSnapshot(profile).first()

    // --- the standing record ------------------------------------------------------------------
    //
    // Allergies and conditions. Read together because that is how a record is read — "what must she
    // not have, and what does she already have" is one question asked in one breath.

    fun observeAllergies(profileId: String): Flow<List<Allergy>> =
        dao.observeAllergies(profileId).map { rows -> sortAllergies(rows.map { it.toModel() }) }

    fun observeConditions(profileId: String): Flow<List<Condition>> =
        dao.observeConditions(profileId).map { rows -> sortConditions(rows.map { it.toModel() }) }

    /** One person's standing facts as a unit — what the record screen and the Today strip both read. */
    fun observeStandingRecord(profileId: String): Flow<StandingRecord> =
        combine(
            dao.observeAllergies(profileId),
            dao.observeConditions(profileId)
        ) { allergies, conditions ->
            StandingRecord(
                allergies = sortAllergies(allergies.map { it.toModel() }),
                conditions = sortConditions(conditions.map { it.toModel() })
            )
        }

    suspend fun standingRecordOnce(profileId: String): StandingRecord = StandingRecord(
        allergies = sortAllergies(dao.getAllergies(profileId).map { it.toModel() }),
        conditions = sortConditions(dao.getConditions(profileId).map { it.toModel() })
    )

    /**
     * Worst first, and the ordering lives here rather than in SQL for the reason the DAO's own note
     * gives: severity is stored as its key, so ordering by the column alphabetically would file
     * "mild" above "severe". The enum in `logic/` is the only definition of worse, and this is the
     * one place it is applied.
     */
    private fun sortAllergies(allergies: List<Allergy>): List<Allergy> =
        allergies.sortedWith(
            compareByDescending<Allergy> { it.severity.ordinal }
                .thenBy { it.kind.ordinal }
                .thenBy { it.substance.lowercase() }
        )

    private fun sortConditions(conditions: List<Condition>): List<Condition> =
        Conditions.sort(conditions, status = { it.status }, onset = { it.onsetDate }, name = { it.name })

    suspend fun addAllergy(
        profileId: String,
        substance: String,
        kind: AllergyKind,
        severity: AllergySeverity,
        reaction: String? = null,
        rxcui: String? = null,
        noticedDate: String? = null,
        note: String? = null
    ): String {
        val id = newId()
        val timestamp = now()
        dao.upsertAllergy(
            AllergyEntity(
                id = id,
                profileId = profileId,
                substance = substance.trim(),
                kind = kind.key,
                severity = severity.key,
                reaction = reaction.clean(),
                rxcui = rxcui.clean(),
                noticedDate = noticedDate.clean(),
                note = note.clean(),
                createdAt = timestamp,
                updatedAt = timestamp
            )
        )
        return id
    }

    suspend fun updateAllergy(allergy: Allergy) {
        val existing = dao.getAllergy(allergy.id) ?: return
        dao.upsertAllergy(
            existing.copy(
                substance = allergy.substance.trim(),
                kind = allergy.kind.key,
                severity = allergy.severity.key,
                reaction = allergy.reaction.clean(),
                rxcui = allergy.rxcui.clean(),
                noticedDate = allergy.noticedDate.clean(),
                note = allergy.note.clean(),
                updatedAt = now()
            )
        )
    }

    suspend fun deleteAllergy(id: String) = dao.deleteAllergy(id)

    suspend fun addCondition(
        profileId: String,
        name: String,
        status: ConditionStatus = ConditionStatus.ACTIVE,
        onsetDate: String? = null,
        resolvedDate: String? = null,
        providerId: String? = null,
        monitorReadingType: ReadingType? = null,
        note: String? = null
    ): String {
        val id = newId()
        val timestamp = now()
        dao.upsertCondition(
            ConditionEntity(
                id = id,
                profileId = profileId,
                name = name.trim(),
                status = status.key,
                onsetDate = onsetDate.clean(),
                resolvedDate = resolvedDate.clean(),
                providerId = providerId.clean(),
                monitorReadingType = monitorReadingType?.key,
                note = note.clean(),
                createdAt = timestamp,
                updatedAt = timestamp
            )
        )
        return id
    }

    suspend fun updateCondition(condition: Condition) {
        val existing = dao.getCondition(condition.id) ?: return
        dao.upsertCondition(
            existing.copy(
                name = condition.name.trim(),
                status = condition.status.key,
                onsetDate = condition.onsetDate.clean(),
                resolvedDate = condition.resolvedDate.clean(),
                providerId = condition.providerId.clean(),
                monitorReadingType = condition.monitorReadingType?.key,
                note = condition.note.clean(),
                updatedAt = now()
            )
        )
    }

    suspend fun deleteCondition(id: String) = dao.deleteCondition(id)

    /**
     * Everything recorded for this person that matches a medicine, worst first.
     *
     * The ingredients come from the cached monograph when the product was looked up, which is what
     * makes the check worth having: a household that searched for a medicine gets matched against
     * the label's own ingredient list, while one that typed the name in gets matched against the
     * name. Both are honest; they are not equally strong, and `logic/Allergies` says which is which.
     *
     * An **empty list is not a clearance.** It means nothing recorded matched — see the note on
     * `logic/Allergies`. Callers must render it as that and never as an all-clear.
     */
    suspend fun allergyWarnings(
        profileId: String,
        name: String,
        rxcui: String? = null
    ): List<AllergyWarning> {
        val allergies = dao.getAllergies(profileId).map { it.toModel().facts }
        if (allergies.isEmpty()) return emptyList()
        val monograph = rxcui?.clean()?.let { dao.getDrugFacts(it)?.let { row -> DrugFactsMapper.toMonograph(row) } }
        return Allergies.check(
            allergies,
            MedicineFacts(
                rxcui = rxcui.clean(),
                name = name.trim(),
                brandName = monograph?.brandName,
                genericName = monograph?.genericName,
                ingredients = monograph?.ingredients.orEmpty()
            )
        )
    }

    /** The same check for a medicine already on somebody's list — used when a dose is about to be given. */
    suspend fun allergyWarnings(medication: Medication): List<AllergyWarning> =
        allergyWarnings(medication.profileId, medication.name, medication.rxcui)

    // --- the vaccination record ------------------------------------------------------------------

    fun observeImmunizations(profileId: String): Flow<List<Immunization>> =
        dao.observeImmunizations(profileId).map { rows -> rows.map { it.toModel() } }

    /**
     * The record as it is read: grouped into series, most recently given first.
     *
     * The grouping is `logic/Immunizations`' rather than SQL's, because deciding that "M.M.R." and
     * "MMR" are one vaccine is a judgement about names and the database has no opinion about it.
     */
    fun observeVaccineSeries(profileId: String): Flow<List<VaccineSeries>> =
        dao.observeImmunizations(profileId).map { rows ->
            Immunizations.group(rows.map { it.toModel().dose })
        }

    suspend fun addImmunization(
        profileId: String,
        vaccine: String,
        givenDate: String? = null,
        doseNumber: Int? = null,
        source: VaccineSource = VaccineSource.UNKNOWN,
        cvxCode: String? = null,
        providerId: String? = null,
        lotNumber: String? = null,
        site: String? = null,
        note: String? = null
    ): String {
        val id = newId()
        val timestamp = now()
        dao.upsertImmunization(
            ImmunizationEntity(
                id = id,
                profileId = profileId,
                vaccine = vaccine.trim(),
                cvxCode = cvxCode.clean(),
                givenDate = givenDate.clean(),
                doseNumber = doseNumber,
                source = source.key,
                providerId = providerId.clean(),
                lotNumber = lotNumber.clean(),
                site = site.clean(),
                note = note.clean(),
                createdAt = timestamp,
                updatedAt = timestamp
            )
        )
        return id
    }

    suspend fun updateImmunization(immunization: Immunization) {
        val existing = dao.getImmunization(immunization.id) ?: return
        dao.upsertImmunization(
            existing.copy(
                vaccine = immunization.vaccine.trim(),
                cvxCode = immunization.cvxCode.clean(),
                givenDate = immunization.givenDate.clean(),
                doseNumber = immunization.doseNumber,
                source = immunization.source.key,
                providerId = immunization.providerId.clean(),
                lotNumber = immunization.lotNumber.clean(),
                site = immunization.site.clean(),
                note = immunization.note.clean(),
                updatedAt = now()
            )
        )
    }

    suspend fun deleteImmunization(id: String) = dao.deleteImmunization(id)

    // --- documents --------------------------------------------------------------------------------
    //
    // Health stores the household's paperwork and reads none of it. Every fact on a document row was
    // typed by a person; nothing here inspects a file's contents. See `logic/Documents`.

    fun observeDocuments(profileId: String): Flow<List<Document>> =
        dao.observeDocuments(profileId).map { rows -> sortDocuments(rows.map { it.toModel() }) }

    /** The paperwork that belongs to the house rather than to anybody in it. */
    fun observeHouseholdDocuments(): Flow<List<Document>> =
        dao.observeHouseholdDocuments().map { rows -> sortDocuments(rows.map { it.toModel() }) }

    suspend fun getDocument(id: String): Document? = dao.getDocument(id)?.toModel()

    private fun sortDocuments(documents: List<Document>): List<Document> =
        Documents.sort(documents, date = { it.documentDate }, title = { it.title })

    /**
     * File a document that has already been copied into the store.
     *
     * The bytes are moved first and the row is written second, deliberately. The other order leaves
     * a window in which a row names a file that does not exist yet — and the screen that renders it
     * in that window shows a document the household does not actually have.
     */
    suspend fun addDocument(
        title: String,
        kind: DocumentKind,
        fileName: String,
        profileId: String? = null,
        documentDate: String? = null,
        mimeType: String? = null,
        sizeBytes: Long? = null,
        episodeId: String? = null,
        conditionId: String? = null,
        immunizationId: String? = null,
        providerId: String? = null,
        note: String? = null
    ): String {
        val id = newId()
        val timestamp = now()
        dao.upsertDocument(
            DocumentEntity(
                id = id,
                profileId = profileId.clean(),
                title = title.trim().ifBlank { kind.label },
                kind = kind.key,
                documentDate = documentDate.clean(),
                fileName = fileName,
                mimeType = mimeType.clean(),
                sizeBytes = sizeBytes,
                episodeId = episodeId.clean(),
                conditionId = conditionId.clean(),
                immunizationId = immunizationId.clean(),
                providerId = providerId.clean(),
                note = note.clean(),
                createdAt = timestamp,
                updatedAt = timestamp
            )
        )
        return id
    }

    /**
     * Edit what was typed about a document. The file itself is never touched here — re-filing an
     * after-visit summary under the right child does not change the PDF.
     */
    suspend fun updateDocument(document: Document) {
        val existing = dao.getDocument(document.id) ?: return
        dao.upsertDocument(
            existing.copy(
                profileId = document.profileId.clean(),
                title = document.title.trim().ifBlank { document.kind.label },
                kind = document.kind.key,
                documentDate = document.documentDate.clean(),
                episodeId = document.episodeId.clean(),
                conditionId = document.conditionId.clean(),
                immunizationId = document.immunizationId.clean(),
                providerId = document.providerId.clean(),
                note = document.note.clean(),
                updatedAt = now()
            )
        )
    }

    /** Drop a document, and then the file behind it — in that order, never the other way round. */
    suspend fun deleteDocument(id: String) {
        val existing = dao.getDocument(id) ?: return
        dao.deleteDocumentRow(id)
        onDocumentDiscarded(existing.fileName)
    }

    // --- coverage: the cards ---------------------------------------------------------------------
    //
    // A plan is household-scoped and a membership is not, exactly as a bottle is household-scoped and
    // a person's dose of it is not. Everything here stores what is *printed on the card*; nothing
    // here stores, computes or infers what a policy actually covers.

    fun observeInsurancePlans(): Flow<List<InsurancePlan>> =
        dao.observeInsurancePlans().map { rows -> rows.map { it.toModel() } }

    suspend fun getInsurancePlan(id: String): InsurancePlan? = dao.getInsurancePlan(id)?.toModel()

    /**
     * One person's cards: each policy they are on, their own membership of it, whether it is current,
     * and the layout the screen and the PDF both draw from.
     *
     * Assembled here rather than at the screen for the reason every other combined flow in this file
     * exists: two renderers deriving a card independently is two renderers that will eventually
     * disagree about a member number, and the place that shows up is a reception desk.
     *
     * Sorted by the coverage verdict — an ended card first, then one not yet started, then one about
     * to end — with the primary card ahead of a secondary within each. A wallet is read top-down when
     * something is wrong with it.
     */
    fun observeCoverage(profileId: String): Flow<List<CoverageCard>> =
        combine(
            dao.observeInsurancePlans(),
            dao.observeInsuranceMembers(profileId),
            dao.observeProfiles()
        ) { plans, members, profiles ->
            val plansById = plans.associateBy { it.id }
            val memberName = profiles.firstOrNull { it.id == profileId }?.name.orEmpty()
            members.mapNotNull { member ->
                plansById[member.planId]?.let { plan -> card(plan, member, memberName) }
            }.sortedWith(
                compareBy({ it.coverage.status.sortRank }, { !it.membership.primaryCoverage })
            )
        }

    /** One card, for the export sheet — the same assembly [observeCoverage] does, for a single row. */
    suspend fun getCoverageCard(membershipId: String): CoverageCard? {
        val member = dao.getInsuranceMember(membershipId) ?: return null
        val plan = dao.getInsurancePlan(member.planId) ?: return null
        val name = dao.getProfile(member.profileId)?.name.orEmpty()
        return card(plan, member, name)
    }

    private fun card(
        plan: InsurancePlanEntity,
        member: InsuranceMemberEntity,
        memberName: String
    ): CoverageCard {
        // The member's own dates win where they were recorded, and fall back to the policy's. They
        // differ for the ordinary reason: a baby added to a family plan in March is covered from
        // March, not from the policy's January.
        val effective = member.effectiveDate?.ifBlank { null } ?: plan.effectiveDate
        val ends = member.endDate?.ifBlank { null } ?: plan.endDate
        return CoverageCard(
            plan = plan.toModel(),
            membership = member.toModel(),
            memberName = memberName,
            coverage = Insurance.assessCoverage(effective, ends),
            facts = CardFacts(
                carrierName = plan.carrierName,
                planName = plan.planName,
                coverageKind = CoverageKind.fromKey(plan.coverageKind),
                planType = PlanType.fromKey(plan.planType),
                memberName = memberName,
                memberId = member.memberId,
                personCode = member.personCode,
                groupNumber = plan.groupNumber,
                subscriberName = member.subscriberName,
                relationshipToSubscriber = member.relationshipToSubscriber,
                payerId = plan.payerId,
                rxBin = plan.rxBin,
                rxPcn = plan.rxPcn,
                rxGroup = plan.rxGroup,
                effectiveDate = effective,
                endDate = ends,
                memberServicesPhone = plan.memberServicesPhone,
                nurseLinePhone = plan.nurseLinePhone,
                directoryUrl = plan.directoryUrl,
                note = plan.note
            )
        )
    }

    /**
     * Add a policy, and optionally put somebody on it in the same gesture — which is what actually
     * happens when a card comes out of an envelope.
     *
     * Only the carrier is required. A card photographed in a hurry with nothing typed in but the
     * insurer's name is still a card in the app rather than a card in a drawer.
     */
    suspend fun addInsurancePlan(
        carrierName: String,
        planName: String? = null,
        coverageKind: CoverageKind = CoverageKind.MEDICAL,
        planType: PlanType = PlanType.OTHER,
        groupNumber: String? = null,
        payerId: String? = null,
        rxBin: String? = null,
        rxPcn: String? = null,
        rxGroup: String? = null,
        memberServicesPhone: String? = null,
        nurseLinePhone: String? = null,
        effectiveDate: String? = null,
        endDate: String? = null,
        directoryUrl: String? = null,
        note: String? = null
    ): String {
        val id = newId()
        val stamp = now()
        dao.upsertInsurancePlan(
            InsurancePlanEntity(
                id = id,
                carrierName = carrierName.trim(),
                planName = planName.clean(),
                coverageKind = coverageKind.key,
                planType = planType.key,
                groupNumber = groupNumber.clean(),
                payerId = payerId.clean(),
                rxBin = rxBin.clean(),
                rxPcn = rxPcn.clean(),
                rxGroup = rxGroup.clean(),
                memberServicesPhone = memberServicesPhone.clean(),
                nurseLinePhone = nurseLinePhone.clean(),
                effectiveDate = effectiveDate.clean(),
                endDate = endDate.clean(),
                directoryUrl = directoryUrl.clean(),
                directoryBaseUrl = null,
                directoryStatus = null,
                directoryCheckedAt = null,
                directoryDetail = null,
                frontImagePath = null,
                backImagePath = null,
                note = note.clean(),
                archived = false,
                createdAt = stamp,
                updatedAt = stamp
            )
        )
        return id
    }

    /**
     * Edit a policy. The directory columns are left alone: they are written by a probe, not by a
     * form, and an edit to the phone number is no reason to forget that the directory answered last
     * Tuesday. Changing the published URL *does* clear them — see below — because a new address makes
     * the old verdict meaningless.
     */
    suspend fun updateInsurancePlan(plan: InsurancePlan) {
        val existing = dao.getInsurancePlan(plan.id) ?: return
        val newUrl = plan.directoryUrl.clean()
        val urlChanged = newUrl != existing.directoryUrl
        dao.upsertInsurancePlan(
            existing.copy(
                carrierName = plan.carrierName.trim(),
                planName = plan.planName.clean(),
                coverageKind = plan.coverageKind.key,
                planType = plan.planType.key,
                groupNumber = plan.groupNumber.clean(),
                payerId = plan.payerId.clean(),
                rxBin = plan.rxBin.clean(),
                rxPcn = plan.rxPcn.clean(),
                rxGroup = plan.rxGroup.clean(),
                memberServicesPhone = plan.memberServicesPhone.clean(),
                nurseLinePhone = plan.nurseLinePhone.clean(),
                effectiveDate = plan.effectiveDate.clean(),
                endDate = plan.endDate.clean(),
                directoryUrl = newUrl,
                directoryBaseUrl = if (urlChanged) null else existing.directoryBaseUrl,
                directoryStatus = if (urlChanged) null else existing.directoryStatus,
                directoryCheckedAt = if (urlChanged) null else existing.directoryCheckedAt,
                directoryDetail = if (urlChanged) null else existing.directoryDetail,
                note = plan.note.clean(),
                archived = plan.archived,
                updatedAt = now()
            )
        )
    }

    /**
     * Put a policy away without losing it. Last year's plan is still the plan that covered a visit in
     * November, and the checks recorded under it are still the evidence that a doctor used to be in
     * network — which is the one thing that makes this year's "not listed" mean anything.
     */
    suspend fun setInsurancePlanArchived(planId: String, archived: Boolean) {
        val existing = dao.getInsurancePlan(planId) ?: return
        dao.upsertInsurancePlan(existing.copy(archived = archived, updatedAt = now()))
    }

    /** Record what a directory probe found, so the next check starts from the endpoint that answered. */
    suspend fun recordDirectoryProbe(planId: String, probe: DirectoryProbe) {
        val existing = dao.getInsurancePlan(planId) ?: return
        dao.upsertInsurancePlan(
            existing.copy(
                // Only a probe that actually found a directory rewrites the base URL. A failed one
                // records what happened without throwing away the endpoint that worked last month.
                directoryBaseUrl = if (probe.searchable) probe.baseUrl else existing.directoryBaseUrl,
                directoryStatus = probe.outcome.key,
                directoryCheckedAt = now(),
                directoryDetail = probe.detail.clean(),
                updatedAt = now()
            )
        )
    }

    /** Throw a policy away, along with everybody's membership of it and any card photos it held. */
    suspend fun deleteInsurancePlan(planId: String) {
        val existing = dao.getInsurancePlan(planId) ?: return
        val orphaned = dao.getInsuranceMembersForPlan(planId)
            .flatMap { listOfNotNull(it.frontImagePath, it.backImagePath) } +
            listOfNotNull(existing.frontImagePath, existing.backImagePath)
        dao.deleteInsurancePlanCascade(planId)
        orphaned.forEach(onCardImageDiscarded)
    }

    // --- coverage: who is on which card ------------------------------------------------------------

    fun observeMemberships(profileId: String): Flow<List<InsuranceMembership>> =
        dao.observeInsuranceMembers(profileId).map { rows -> rows.map { it.toModel() } }

    suspend fun addMembership(
        profileId: String,
        planId: String,
        memberId: String? = null,
        personCode: String? = null,
        subscriberName: String? = null,
        relationshipToSubscriber: String? = null,
        effectiveDate: String? = null,
        endDate: String? = null,
        primaryCoverage: Boolean = true,
        note: String? = null
    ): String {
        val id = newId()
        val stamp = now()
        dao.upsertInsuranceMember(
            InsuranceMemberEntity(
                id = id,
                profileId = profileId,
                planId = planId,
                memberId = memberId.clean(),
                personCode = personCode.clean(),
                subscriberName = subscriberName.clean(),
                relationshipToSubscriber = relationshipToSubscriber.clean(),
                effectiveDate = effectiveDate.clean(),
                endDate = endDate.clean(),
                primaryCoverage = primaryCoverage,
                frontImagePath = null,
                backImagePath = null,
                note = note.clean(),
                createdAt = stamp,
                updatedAt = stamp
            )
        )
        return id
    }

    suspend fun updateMembership(membership: InsuranceMembership) {
        val existing = dao.getInsuranceMember(membership.id) ?: return
        dao.upsertInsuranceMember(
            existing.copy(
                planId = membership.planId,
                memberId = membership.memberId.clean(),
                personCode = membership.personCode.clean(),
                subscriberName = membership.subscriberName.clean(),
                relationshipToSubscriber = membership.relationshipToSubscriber.clean(),
                effectiveDate = membership.effectiveDate.clean(),
                endDate = membership.endDate.clean(),
                primaryCoverage = membership.primaryCoverage,
                note = membership.note.clean(),
                updatedAt = now()
            )
        )
    }

    /** Take somebody off a card, keeping the card and the photos that belong to the policy itself. */
    suspend fun deleteMembership(membershipId: String) {
        val existing = dao.getInsuranceMember(membershipId) ?: return
        val orphaned = listOfNotNull(existing.frontImagePath, existing.backImagePath)
        dao.deleteInsuranceMember(membershipId)
        orphaned.forEach(onCardImageDiscarded)
    }

    /**
     * Attach — or replace — the photographs of a card.
     *
     * Saved on upload rather than re-read from the picker each time, which is the whole reason the
     * PDF can be produced later without asking for the image again. The previous file for that face
     * is discarded once the row pointing at it is written, never before: a file deleted ahead of a
     * write that then fails leaves a card pointing at nothing.
     *
     * Passing null for a face leaves it alone; [clearFront]/[clearBack] is how a photo is removed,
     * because "no new file" and "delete the one there" are different intentions and conflating them
     * makes the second one unreachable.
     */
    suspend fun setMembershipCardImages(
        membershipId: String,
        frontFileName: String? = null,
        backFileName: String? = null,
        clearFront: Boolean = false,
        clearBack: Boolean = false
    ) {
        val existing = dao.getInsuranceMember(membershipId) ?: return
        val nextFront = when {
            clearFront -> null
            frontFileName != null -> frontFileName
            else -> existing.frontImagePath
        }
        val nextBack = when {
            clearBack -> null
            backFileName != null -> backFileName
            else -> existing.backImagePath
        }
        dao.upsertInsuranceMember(
            existing.copy(frontImagePath = nextFront, backImagePath = nextBack, updatedAt = now())
        )
        listOfNotNull(
            existing.frontImagePath.takeIf { it != nextFront },
            existing.backImagePath.takeIf { it != nextBack }
        ).forEach(onCardImageDiscarded)
    }

    /** The same, for the household card that came in one envelope for everybody on the policy. */
    suspend fun setPlanCardImages(
        planId: String,
        frontFileName: String? = null,
        backFileName: String? = null,
        clearFront: Boolean = false,
        clearBack: Boolean = false
    ) {
        val existing = dao.getInsurancePlan(planId) ?: return
        val nextFront = when {
            clearFront -> null
            frontFileName != null -> frontFileName
            else -> existing.frontImagePath
        }
        val nextBack = when {
            clearBack -> null
            backFileName != null -> backFileName
            else -> existing.backImagePath
        }
        dao.upsertInsurancePlan(
            existing.copy(frontImagePath = nextFront, backImagePath = nextBack, updatedAt = now())
        )
        listOfNotNull(
            existing.frontImagePath.takeIf { it != nextFront },
            existing.backImagePath.takeIf { it != nextBack }
        ).forEach(onCardImageDiscarded)
    }

    // --- the care team ------------------------------------------------------------------------------
    //
    // Doctors are household-scoped and are **not** owned by an insurance plan. The policy changes
    // every January; the paediatrician does not. Hanging the care team off the plan would mean
    // re-entering every clinician in the house on a carrier change — and would throw away the check
    // history that is the only way to notice the new plan doesn't cover somebody the old one did.

    fun observeProviders(): Flow<List<Provider>> =
        dao.observeProviders().map { rows -> rows.map { it.toModel() } }

    suspend fun getProvider(id: String): Provider? = dao.getProvider(id)?.toModel()

    /**
     * One person's care team, each member carrying where they stand against *that person's* coverage.
     *
     * The verdict is derived from the checks that are actually about this person's cover: the ones
     * made under a policy they are on, plus the ones made under no policy at all — a phone call to
     * the office is evidence whoever is asking. A check made under a policy the household has since
     * left is deliberately **not** counted: last year's network is not this year's, and letting an
     * old carrier's yes stand under a new plan would be the most convincing wrong answer this feature
     * could produce. Those checks are not deleted; they simply stop speaking for a plan they were
     * never about, and reappear the moment somebody is put back on that policy.
     *
     * Sorted by verdict — anything that needs a phone call first, then the unanswered, then the
     * settled — and by role within it, so "who is her GP" stays a glance rather than a search.
     */
    fun observeCareTeam(profileId: String): Flow<List<CareTeamMember>> =
        combine(
            dao.observeProviders(),
            dao.observeProviderLinks(profileId),
            dao.observeNetworkChecks(),
            dao.observeInsuranceMembers(profileId)
        ) { providers, links, checks, memberships ->
            val nowMillis = now()
            val providersById = providers.associateBy { it.id }
            val myPlanIds = memberships.map { it.planId }.toSet()
            val relevant = checks.filter { it.planId == null || it.planId in myPlanIds }
                .groupBy { it.providerId }

            links.mapNotNull { link ->
                val provider = providersById[link.providerId] ?: return@mapNotNull null
                val mine = relevant[link.providerId].orEmpty().map { it.toModel() }
                CareTeamMember(
                    provider = provider.toModel(),
                    link = link.toModel(),
                    network = NetworkStatus.evaluate(mine.map { it.toRecord() }, nowMillis),
                    checks = mine.sortedBy { it.checkedAt }
                )
            }.sortedWith(
                compareBy(
                    { it.network.verdict.sortRank },
                    { it.link.role.sortRank },
                    { it.provider.name.lowercase() }
                )
            )
        }

    /**
     * Add a provider, and optionally put them on somebody's care team in the same gesture — the way
     * it happens when a receptionist hands over a card at the end of an appointment.
     *
     * Only the name is required. Everything else, the NPI included, can be filled in later or never;
     * a form that demands a national provider identifier before it will save is a form that gets
     * abandoned, and a doctor with only a name and a phone number in the app is already worth having.
     */
    suspend fun addProvider(
        name: String,
        npi: String? = null,
        specialty: String? = null,
        practiceName: String? = null,
        phone: String? = null,
        addressLine: String? = null,
        website: String? = null,
        note: String? = null,
        forProfileId: String? = null,
        role: CareRole = CareRole.PRIMARY
    ): String {
        val id = newId()
        val stamp = now()
        dao.upsertProvider(
            ProviderEntity(
                id = id,
                name = name.trim(),
                npi = ProviderDirectory.digits(npi),
                specialty = specialty.clean(),
                practiceName = practiceName.clean(),
                phone = phone.clean(),
                addressLine = addressLine.clean(),
                website = website.clean(),
                note = note.clean(),
                createdAt = stamp,
                updatedAt = stamp
            )
        )
        forProfileId?.let { linkProvider(it, id, role) }
        return id
    }

    suspend fun updateProvider(provider: Provider) {
        val existing = dao.getProvider(provider.id) ?: return
        dao.upsertProvider(
            existing.copy(
                name = provider.name.trim(),
                npi = ProviderDirectory.digits(provider.npi),
                specialty = provider.specialty.clean(),
                practiceName = provider.practiceName.clean(),
                phone = provider.phone.clean(),
                addressLine = provider.addressLine.clean(),
                website = provider.website.clean(),
                note = provider.note.clean(),
                updatedAt = now()
            )
        )
    }

    /**
     * Remove a provider from the household, along with everybody who saw them and every check made
     * about them.
     *
     * The one deletion in this repository that genuinely destroys history, and it is the right call:
     * a network check is evidence *about a provider*, so with the provider gone it is evidence about
     * nothing. That is not true of a dose, which happened to a person and stays whatever becomes of
     * the medicine.
     */
    suspend fun deleteProvider(providerId: String) = dao.deleteProviderCascade(providerId)

    /** Put a provider on somebody's care team. Returns the link, so the screen can edit it straight away. */
    suspend fun linkProvider(
        profileId: String,
        providerId: String,
        role: CareRole = CareRole.PRIMARY,
        since: String? = null,
        note: String? = null
    ): String {
        val id = newId()
        val stamp = now()
        dao.upsertProviderLink(
            ProviderLinkEntity(
                id = id,
                profileId = profileId,
                providerId = providerId,
                role = role.key,
                since = since.clean(),
                note = note.clean(),
                createdAt = stamp,
                updatedAt = stamp
            )
        )
        return id
    }

    suspend fun updateProviderLink(link: ProviderLink) {
        val existing = dao.getProviderLink(link.id) ?: return
        dao.upsertProviderLink(
            existing.copy(
                role = link.role.key,
                since = link.since.clean(),
                note = link.note.clean(),
                updatedAt = now()
            )
        )
    }

    /**
     * Take a provider off one person's care team, keeping the provider — and keeping every check made
     * about them, which still speaks for everybody else in the house who sees them.
     */
    suspend fun unlinkProvider(linkId: String) = dao.deleteProviderLink(linkId)

    // --- network checks -------------------------------------------------------------------------------
    //
    // Append-only. Nothing here updates a check or clears the older ones to make room: the earlier
    // answers are what let `logic/NetworkStatus` tell "has left the network" apart from "was never in
    // it", and those need different phone calls.

    fun observeNetworkChecks(providerId: String): Flow<List<NetworkCheck>> =
        dao.observeNetworkChecks(providerId).map { rows -> rows.map { it.toModel() } }

    suspend fun getNetworkChecks(providerId: String): List<NetworkCheck> =
        dao.getNetworkChecks(providerId).map { it.toModel() }

    /**
     * Write down what was found. The carrier's name is stored **on the row** rather than looked up
     * through the plan, for the same reason a dose carries its medicine's name: the household changes
     * plans, and a check that reads "not listed by ⟨deleted plan⟩" is a check that has stopped being
     * evidence of anything.
     */
    suspend fun recordNetworkCheck(
        providerId: String,
        planId: String?,
        outcome: CheckOutcome,
        directoryLabel: String? = null,
        directoryUrl: String? = null,
        matchedName: String? = null,
        matchedNpi: String? = null,
        matchCount: Int = 0,
        networks: List<String> = emptyList(),
        detail: String? = null,
        checkedAt: Long = now()
    ): String {
        val id = newId()
        dao.upsertNetworkCheck(
            NetworkCheckEntity(
                id = id,
                providerId = providerId,
                planId = planId,
                checkedAt = checkedAt,
                outcome = outcome.key,
                directoryLabel = directoryLabel.clean(),
                directoryUrl = directoryUrl.clean(),
                matchedName = matchedName.clean(),
                matchedNpi = matchedNpi.clean(),
                matchCount = matchCount,
                networks = networks.filter { it.isNotBlank() }.joinToString(CSV).ifBlank { null },
                detail = detail.clean()
            )
        )
        return id
    }

    /** Undo a mis-tap. Offered one row at a time and never in bulk — see the DAO's note. */
    suspend fun deleteNetworkCheck(id: String) = dao.deleteNetworkCheck(id)

    companion object {
        /** How far back a newly-started episode reaches to adopt records already taken. */
        const val DEFAULT_BACKFILL_MS: Long = 12L * 60 * 60 * 1000

        /**
         * The palette a new person is assigned from, in order — distinct at a glance, and
         * colour-blind safe. It lives here rather than in the People screen because a profile can
         * now also arrive over the seam, and two palettes would drift apart the first time either
         * was edited.
         */
        val PROFILE_COLORS = listOf(
            0xFF2C7A7B, 0xFFB7791F, 0xFF6B46C1, 0xFF2B6CB0,
            0xFFB83280, 0xFF2F855A, 0xFFC05621, 0xFF4A5568
        )
    }
}
