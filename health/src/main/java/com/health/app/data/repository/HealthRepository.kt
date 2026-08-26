package com.health.app.data.repository

import com.health.app.data.db.dao.HealthDao
import com.health.app.data.db.entities.CabinetItemEntity
import com.health.app.data.db.entities.CareNoteEntity
import com.health.app.data.db.entities.DoseEntity
import com.health.app.data.db.entities.EpisodeEntity
import com.health.app.data.db.entities.MedicationEntity
import com.health.app.data.db.entities.ProfileEntity
import com.health.app.data.db.entities.ProfileTombstoneEntity
import com.health.app.data.db.entities.ReadingEntity
import com.health.app.data.db.entities.SymptomEntity
import com.health.app.data.model.CabinetEntry
import com.health.app.data.model.CabinetItem
import com.health.app.data.model.CabinetUse
import com.health.app.data.model.CareKind
import com.health.app.data.model.CareNote
import com.health.app.data.model.Dose
import com.health.app.data.model.Episode
import com.health.app.data.model.Medication
import com.health.app.data.model.MedicationStatus
import com.health.app.data.model.Profile
import com.health.app.data.model.ProfileSnapshot
import com.health.app.data.model.Reading
import com.health.app.data.model.ReadingType
import com.health.app.data.model.Symptom
import com.health.app.data.prefs.HealthPrefs
import com.health.app.logic.Age
import com.health.app.logic.Cabinet
import com.health.app.logic.CabinetFacts
import com.health.app.logic.CareLevel
import com.health.app.logic.DosePoint
import com.health.app.logic.DoseRecord
import com.health.app.logic.DoseReminder
import com.health.app.logic.DoseSchedule
import com.health.app.logic.DrugMonograph
import com.health.app.logic.EpisodeFacts
import com.health.app.logic.EpisodeSummaries
import com.health.app.logic.EpisodeSummary
import com.health.app.logic.Fever
import com.health.app.logic.MedicationRule
import com.health.app.logic.ReminderMode
import com.health.app.logic.SymptomPoint
import com.health.app.logic.TempPoint
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
    private val onReminderChange: (medicationId: String?) -> Unit = {}
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
        dao.deleteProfileCascade(profileId)
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
     */
    suspend fun episodeHistory(episodeId: String): List<TimelineDay> {
        val episode = dao.getEpisode(episodeId) ?: return emptyList()
        val profile = dao.getProfile(episode.profileId)
        val ageMonths = profile?.birthDate?.let { Age.monthsAt(it, now()) }

        return Timeline.build(
            TimelineFacts(
                readings = dao.getReadingsForEpisode(episodeId).map { it.toTimelineEntry(ageMonths) },
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

        return Timeline.build(
            TimelineFacts(
                readings = dao.getReadingsSince(profileId, sinceMillis).map { it.toTimelineEntry(ageMonths) },
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

// --- entity → model mapping --------------------------------------------------------------------

fun ProfileEntity.toModel() = Profile(
    id = id,
    name = name,
    relationship = relationship,
    birthDate = birthDate,
    colorArgb = colorArgb,
    baselineTempC = baselineTempC,
    notes = notes,
    sortOrder = sortOrder,
    archived = archived
)

/**
 * This profile as the People sync seam sees them — identity only.
 *
 * Three fields are deliberately absent, and the reasons differ:
 *
 *  - [ProfileEntity.notes] is **medical** — allergies, conditions, the doctor's number. People has a
 *    field called `note` too, but it means "likes hiking, hates crowds". Mapping one onto the other
 *    would copy a person's conditions into the household directory and from there into LifeOps. A
 *    shared wire makes that leak a one-line mistake, so it is refused explicitly rather than left to
 *    whoever next edits this mapper.
 *  - [ProfileEntity.baselineTempC] and the colour are Health's own reading of a person; no other
 *    peer can show or edit them.
 *  - Email and phone are absent because Health has no columns for them. That costs a little binding
 *    strength on the very first round — Health matches by name until it has a key — and nothing
 *    afterwards, since the key is what binds from then on.
 */
fun ProfileEntity.toPacket() = PersonPacket(
    personKey = personKey ?: id,
    name = name,
    relationship = relationship,
    birthDate = birthDate,
    email = null,
    phone = null,
    note = null,
    household = household,
    archived = archived,
    updatedAt = updatedAt,
    deleted = false
)

fun ReadingEntity.toModel() = Reading(
    id = id,
    profileId = profileId,
    episodeId = episodeId,
    type = ReadingType.fromKey(type),
    value = value,
    secondaryValue = secondaryValue,
    site = site?.let { TempSite.fromKey(it) },
    takenAt = takenAt,
    note = note
)

fun SymptomEntity.toModel() = Symptom(
    id = id,
    profileId = profileId,
    episodeId = episodeId,
    name = name,
    severity = severity,
    startedAt = startedAt,
    endedAt = endedAt,
    note = note
)

fun MedicationEntity.toModel() = Medication(
    id = id,
    profileId = profileId,
    name = name,
    strength = strength,
    form = form,
    doseAmount = doseAmount,
    doseUnit = doseUnit,
    minIntervalHours = minIntervalHours,
    maxDosesPer24h = maxDosesPer24h,
    maxAmountPer24h = maxAmountPer24h,
    note = note,
    active = active,
    rxcui = rxcui,
    cabinetItemId = cabinetItemId,
    reminderMode = ReminderMode.fromKey(reminderMode),
    reminderTimes = DoseReminder.parseTimes(reminderTimes)
)

fun CabinetItemEntity.toModel() = CabinetItem(
    id = id,
    rxcui = rxcui,
    name = name,
    brandName = brandName,
    strength = strength,
    form = form,
    quantity = quantity,
    quantityUnit = quantityUnit,
    expiryDate = expiryDate,
    location = location,
    lowStockThreshold = lowStockThreshold,
    note = note,
    updatedAt = updatedAt
)

/** The stock facts `logic/Cabinet` reasons about, paired with one person's dose of the item. */
fun CabinetItemEntity.toFacts(doseAmount: Double? = null, doseUnit: String = "") = CabinetFacts(
    quantity = quantity,
    quantityUnit = quantityUnit,
    expiryDate = expiryDate,
    lowStockThreshold = lowStockThreshold,
    doseAmount = doseAmount,
    doseUnit = doseUnit
)

/** The label's rules, in the shape `logic/DoseSchedule` reasons about. */
fun MedicationEntity.toRule() = MedicationRule(
    name = name,
    doseAmount = doseAmount,
    doseUnit = doseUnit,
    minIntervalHours = minIntervalHours,
    maxDosesPer24h = maxDosesPer24h,
    maxAmountPer24h = maxAmountPer24h
)

fun DoseEntity.toModel() = Dose(
    id = id,
    profileId = profileId,
    medicationId = medicationId,
    medicationName = medicationName,
    amount = amount,
    unit = unit,
    takenAt = takenAt,
    note = note,
    episodeId = episodeId
)

/**
 * The row → history-entry mappers.
 *
 * Each one decides how its record reads in a list somebody may be holding out to a doctor, which is
 * why the wording is plain and complete rather than terse: "38.4 °C (ear)" tells you what an
 * abbreviation would not, and the site is the difference between a fever and a normal reading.
 *
 * Readings carry the fever verdict Health already computed, so the history and every other screen
 * give the same answer to the same number. Nothing else carries a care level — Health has no opinion
 * about a care note, and defaulting one to "routine" would be inventing one.
 */
fun ReadingEntity.toTimelineEntry(ageMonths: Int?): TimelineEntry {
    val readingType = ReadingType.fromKey(type)
    val tempSite = site?.let { TempSite.fromKey(it) }
    val assessment = if (readingType == ReadingType.TEMPERATURE) {
        Fever.assess(value, tempSite ?: TempSite.ORAL, ageMonths)
    } else {
        null
    }
    val headline = when (readingType) {
        ReadingType.TEMPERATURE -> buildString {
            append(Temperature.format(value, TempUnit.CELSIUS))
            tempSite?.let { append(" (").append(it.label.lowercase()).append(')') }
        }
        ReadingType.BLOOD_PRESSURE ->
            "${trimAmount(value)}/${secondaryValue?.let { trimAmount(it) } ?: "?"} ${readingType.unit}"
        else -> "${trimAmount(value)} ${readingType.unit}"
    }
    return TimelineEntry(
        id = "reading:$id",
        kind = TimelineKind.READING,
        atMillis = takenAt,
        recordedAtMillis = createdAt,
        headline = headline,
        detail = listOfNotNull(
            readingType.label.takeIf { readingType != ReadingType.TEMPERATURE },
            assessment?.band?.label,
            note
        ).joinToString(" · ").ifBlank { null },
        careLevel = assessment?.careLevel
    )
}

/**
 * A symptom becomes up to **two** entries: one where it started, one where it was marked over.
 *
 * They are separate moments and a history that showed only the start would be missing the answer to
 * "when did the cough stop?" — which is usually the question being asked, because it is how you tell
 * whether things are getting better.
 */
fun SymptomEntity.toTimelineEntries(): List<TimelineEntry> = buildList {
    add(
        TimelineEntry(
            id = "symptom-start:$id",
            kind = TimelineKind.SYMPTOM_STARTED,
            atMillis = startedAt,
            recordedAtMillis = createdAt,
            headline = name,
            detail = listOfNotNull("severity $severity of 5", note).joinToString(" · ")
        )
    )
    endedAt?.let { ended ->
        add(
            TimelineEntry(
                id = "symptom-end:$id",
                kind = TimelineKind.SYMPTOM_ENDED,
                atMillis = ended,
                // The end was recorded when it was marked over, which Health doesn't store
                // separately — the row's createdAt is when the symptom was *added*, and reusing it
                // here would claim the ending was written up months before it happened.
                recordedAtMillis = null,
                headline = "$name passed"
            )
        )
    }
}

fun DoseEntity.toTimelineEntry() = TimelineEntry(
    id = "dose:$id",
    kind = TimelineKind.DOSE,
    atMillis = takenAt,
    recordedAtMillis = createdAt,
    headline = medicationName,
    detail = listOfNotNull(
        "${trimAmount(amount)} $unit".trim().takeIf { amount > 0 },
        note
    ).joinToString(" · ").ifBlank { null }
)

fun CareNoteEntity.toTimelineEntry() = TimelineEntry(
    id = "care:$id",
    kind = TimelineKind.CARE,
    atMillis = at,
    recordedAtMillis = createdAt,
    headline = text,
    detail = CareKind.fromKey(kind).label
)

/** Numbers in the history read as people write them: "5", not "5.0". */
private fun trimAmount(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else Temperature.round1(value).toString()

fun EpisodeEntity.toModel() = Episode(
    id = id,
    profileId = profileId,
    title = title,
    startedAt = startedAt,
    endedAt = endedAt,
    note = note
)

fun CareNoteEntity.toModel() = CareNote(
    id = id,
    profileId = profileId,
    episodeId = episodeId,
    kind = CareKind.fromKey(kind),
    text = text,
    at = at
)
