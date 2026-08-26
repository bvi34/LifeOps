package com.health.app.data.repository

import com.health.app.data.db.dao.HealthDao
import com.health.app.data.db.entities.CareNoteEntity
import com.health.app.data.db.entities.DoseEntity
import com.health.app.data.db.entities.EpisodeEntity
import com.health.app.data.db.entities.MedicationEntity
import com.health.app.data.db.entities.ProfileEntity
import com.health.app.data.db.entities.ProfileTombstoneEntity
import com.health.app.data.db.entities.ReadingEntity
import com.health.app.data.db.entities.SymptomEntity
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
import com.health.app.logic.CareLevel
import com.health.app.logic.DosePoint
import com.health.app.logic.DoseRecord
import com.health.app.logic.DoseSchedule
import com.health.app.logic.EpisodeFacts
import com.health.app.logic.EpisodeSummaries
import com.health.app.logic.EpisodeSummary
import com.health.app.logic.Fever
import com.health.app.logic.MedicationRule
import com.health.app.logic.SymptomPoint
import com.health.app.logic.TempPoint
import com.health.app.logic.TempSite
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
    private val onProfileEdit: (LocalRosterChange) -> Unit = {}
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
                episodeId = dao.getOpenEpisode(profileId)?.id,
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
                episodeId = dao.getOpenEpisode(profileId)?.id,
                name = name.trim(),
                severity = severity.coerceIn(1, 5),
                startedAt = startedAt,
                endedAt = null,
                note = note?.trim()?.ifBlank { null }
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
            dao.observeDosesSince(profileId, now() - DoseSchedule.WINDOW_MS)
        ) { medications, doses ->
            val nowMillis = now()
            medications.map { medication ->
                val history = doses
                    .filter { it.medicationId == medication.id }
                    .map { DoseRecord(it.takenAt, it.amount) }
                MedicationStatus(
                    medication = medication.toModel(),
                    window = DoseSchedule.evaluate(medication.toRule(), history, nowMillis)
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
        note: String? = null
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
                createdAt = now()
            )
        )
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
                active = medication.active
            )
        )
    }

    suspend fun deleteMedication(id: String) = dao.deleteMedication(id)

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
                episodeId = dao.getOpenEpisode(profileId)?.id
            )
        )
        return id
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

    suspend fun deleteDose(id: String) = dao.deleteDose(id)

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

    /** File this person's recent unattached records against a freshly started episode. */
    private suspend fun adoptRecentRecords(profileId: String, episodeId: String, since: Long) {
        dao.getAllReadings()
            .filter { it.profileId == profileId && it.episodeId == null && it.takenAt >= since }
            .forEach { dao.upsertReading(it.copy(episodeId = episodeId)) }
        dao.getAllSymptoms()
            .filter { it.profileId == profileId && it.episodeId == null && it.startedAt >= since }
            .forEach { dao.upsertSymptom(it.copy(episodeId = episodeId)) }
        dao.getAllDoses()
            .filter { it.profileId == profileId && it.episodeId == null && it.takenAt >= since }
            .forEach { dao.upsertDose(it.copy(episodeId = episodeId)) }
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
                episodeId = dao.getOpenEpisode(profileId)?.id,
                kind = kind.key,
                text = text.trim(),
                at = at
            )
        )
        return id
    }

    suspend fun deleteCareNote(id: String) = dao.deleteCareNote(id)

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
    active = active
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
