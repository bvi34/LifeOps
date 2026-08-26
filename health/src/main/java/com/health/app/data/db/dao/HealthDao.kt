package com.health.app.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.health.app.data.db.entities.CabinetItemEntity
import com.health.app.data.db.entities.CareNoteEntity
import com.health.app.data.db.entities.DoseEntity
import com.health.app.data.db.entities.DrugFactsEntity
import com.health.app.data.db.entities.EpisodeEntity
import com.health.app.data.db.entities.MedicationEntity
import com.health.app.data.db.entities.ProfileEntity
import com.health.app.data.db.entities.ProfileTombstoneEntity
import com.health.app.data.db.entities.ReadingEntity
import com.health.app.data.db.entities.SymptomEntity
import kotlinx.coroutines.flow.Flow

/**
 * Health's one DAO. Every query that returns rows for a person takes the profile id — there is no
 * "current profile" default at this layer, so a screen cannot accidentally show one person's
 * readings under another's name.
 */
@Dao
interface HealthDao {

    // --- profiles ---
    @Query("SELECT * FROM profiles WHERE archived = 0 ORDER BY sortOrder, name COLLATE NOCASE")
    fun observeProfiles(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profiles ORDER BY archived, sortOrder, name COLLATE NOCASE")
    fun observeAllProfiles(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profiles ORDER BY archived, sortOrder, name COLLATE NOCASE")
    suspend fun getProfiles(): List<ProfileEntity>

    @Query("SELECT * FROM profiles WHERE id = :id")
    suspend fun getProfile(id: String): ProfileEntity?

    @Query("SELECT COUNT(*) FROM profiles")
    suspend fun profileCount(): Int

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM profiles")
    suspend fun nextSortOrder(): Int

    // --- People sync bookkeeping (see HealthSyncService) ---

    /** The outbound queue, derived from the rows: everything edited since the peers' ack. */
    @Query("SELECT * FROM profiles WHERE syncVersion > :sinceVersion ORDER BY syncVersion")
    suspend fun profilesChangedSince(sinceVersion: Long): List<ProfileEntity>

    /** Removals waiting to be published — a deleted profile has no row left to speak for it. */
    @Query("SELECT * FROM profile_tombstones WHERE syncVersion > :sinceVersion ORDER BY syncVersion")
    suspend fun profileTombstonesSince(sinceVersion: Long): List<ProfileTombstoneEntity>

    /**
     * The next version to stamp, drawn across both tables so a profile and a removal can never be
     * handed the same number — the peer orders by version, and a tie would leave the order of an
     * edit and a removal to luck.
     */
    @Query(
        "SELECT MAX(v) FROM (" +
            "SELECT COALESCE(MAX(syncVersion), 0) AS v FROM profiles " +
            "UNION ALL " +
            "SELECT COALESCE(MAX(syncVersion), 0) AS v FROM profile_tombstones)"
    )
    suspend fun maxSyncVersion(): Long

    @Upsert
    suspend fun upsertProfileTombstone(tombstone: ProfileTombstoneEntity)

    /** Clear a removal once the person is being tracked again, so it stops un-ticking them. */
    @Query("DELETE FROM profile_tombstones WHERE personKey = :personKey")
    suspend fun deleteProfileTombstone(personKey: String)

    @Query("SELECT * FROM profiles WHERE personKey = :personKey LIMIT 1")
    suspend fun getProfileByKey(personKey: String): ProfileEntity?

    @Upsert
    suspend fun upsertProfile(profile: ProfileEntity)

    /**
     * Delete a person and everything recorded about them. Health has no soft-delete for this on
     * purpose: "remove this person" has to actually remove their medical history, and a cascade that
     * runs in one transaction is the only version of that which can't half-succeed.
     */
    @Transaction
    suspend fun deleteProfileCascade(profileId: String) {
        deleteReadingsForProfile(profileId)
        deleteSymptomsForProfile(profileId)
        deleteDosesForProfile(profileId)
        deleteMedicationsForProfile(profileId)
        deleteCareNotesForProfile(profileId)
        deleteEpisodesForProfile(profileId)
        deleteProfileRow(profileId)
    }

    @Query("DELETE FROM profiles WHERE id = :profileId")
    suspend fun deleteProfileRow(profileId: String)

    // --- readings ---
    @Query("SELECT * FROM readings WHERE profileId = :profileId ORDER BY takenAt DESC")
    fun observeReadings(profileId: String): Flow<List<ReadingEntity>>

    @Query("SELECT * FROM readings WHERE profileId = :profileId AND type = :type ORDER BY takenAt DESC")
    fun observeReadingsOfType(profileId: String, type: String): Flow<List<ReadingEntity>>

    @Query("SELECT * FROM readings WHERE profileId = :profileId AND type = :type ORDER BY takenAt DESC LIMIT 1")
    fun observeLatestOfType(profileId: String, type: String): Flow<ReadingEntity?>

    @Query("SELECT * FROM readings WHERE episodeId = :episodeId ORDER BY takenAt")
    suspend fun getReadingsForEpisode(episodeId: String): List<ReadingEntity>

    @Query("SELECT * FROM readings ORDER BY takenAt DESC")
    suspend fun getAllReadings(): List<ReadingEntity>

    @Upsert
    suspend fun upsertReading(reading: ReadingEntity)

    @Query("DELETE FROM readings WHERE id = :id")
    suspend fun deleteReading(id: String)

    @Query("DELETE FROM readings WHERE profileId = :profileId")
    suspend fun deleteReadingsForProfile(profileId: String)

    // --- symptoms ---
    @Query("SELECT * FROM symptoms WHERE profileId = :profileId ORDER BY endedAt IS NOT NULL, startedAt DESC")
    fun observeSymptoms(profileId: String): Flow<List<SymptomEntity>>

    @Query("SELECT * FROM symptoms WHERE id = :id")
    suspend fun getSymptom(id: String): SymptomEntity?

    @Query("SELECT * FROM symptoms WHERE episodeId = :episodeId ORDER BY startedAt")
    suspend fun getSymptomsForEpisode(episodeId: String): List<SymptomEntity>

    @Query("SELECT * FROM symptoms ORDER BY startedAt DESC")
    suspend fun getAllSymptoms(): List<SymptomEntity>

    @Upsert
    suspend fun upsertSymptom(symptom: SymptomEntity)

    @Query("DELETE FROM symptoms WHERE id = :id")
    suspend fun deleteSymptom(id: String)

    @Query("DELETE FROM symptoms WHERE profileId = :profileId")
    suspend fun deleteSymptomsForProfile(profileId: String)

    // --- medications ---
    @Query("SELECT * FROM medications WHERE profileId = :profileId ORDER BY active DESC, name COLLATE NOCASE")
    fun observeMedications(profileId: String): Flow<List<MedicationEntity>>

    @Query("SELECT * FROM medications WHERE id = :id")
    suspend fun getMedication(id: String): MedicationEntity?

    @Query("SELECT * FROM medications ORDER BY name COLLATE NOCASE")
    suspend fun getAllMedications(): List<MedicationEntity>

    /**
     * Every person's medicines at once — what the cabinet reads to answer "who takes this bottle,
     * and how much do they get?". The one query in this file that is deliberately not profile-scoped,
     * because the cabinet is a household view and scoping it would mean running it once per person.
     */
    @Query("SELECT * FROM medications ORDER BY name COLLATE NOCASE")
    fun observeAllMedications(): Flow<List<MedicationEntity>>

    @Upsert
    suspend fun upsertMedication(medication: MedicationEntity)

    @Query("DELETE FROM medications WHERE id = :id")
    suspend fun deleteMedication(id: String)

    @Query("DELETE FROM medications WHERE profileId = :profileId")
    suspend fun deleteMedicationsForProfile(profileId: String)

    /** Everyone's medicines drawn from one cabinet item — "who is this bottle for?". */
    @Query("SELECT * FROM medications WHERE cabinetItemId = :cabinetItemId")
    suspend fun getMedicationsForCabinetItem(cabinetItemId: String): List<MedicationEntity>

    @Query("SELECT * FROM medications WHERE cabinetItemId = :cabinetItemId")
    fun observeMedicationsForCabinetItem(cabinetItemId: String): Flow<List<MedicationEntity>>

    /**
     * Every medicine with a reminder set, across all profiles — what the scheduler re-arms from
     * after a restore or a device reboot. Deliberately not profile-scoped: a reminder belongs to the
     * device, and the person whose phone it is may not be the person the dose is for.
     */
    @Query("SELECT * FROM medications WHERE reminderMode != 'off' AND active = 1")
    suspend fun getMedicationsWithReminders(): List<MedicationEntity>

    /** Detach a cabinet item's medicines before it is deleted, so the regimens survive it. */
    @Query("UPDATE medications SET cabinetItemId = NULL WHERE cabinetItemId = :cabinetItemId")
    suspend fun clearCabinetItemOnMedications(cabinetItemId: String)

    // --- doses ---
    @Query("SELECT * FROM doses WHERE profileId = :profileId ORDER BY takenAt DESC")
    fun observeDoses(profileId: String): Flow<List<DoseEntity>>

    /** The trailing window every dose limit is judged against — see `logic/DoseSchedule`. */
    @Query("SELECT * FROM doses WHERE profileId = :profileId AND takenAt >= :since ORDER BY takenAt")
    fun observeDosesSince(profileId: String, since: Long): Flow<List<DoseEntity>>

    /** The same trailing window across everyone, for the household-wide cabinet view. */
    @Query("SELECT * FROM doses WHERE takenAt >= :since ORDER BY takenAt")
    fun observeAllDosesSince(since: Long): Flow<List<DoseEntity>>

    @Query("SELECT * FROM doses WHERE id = :id")
    suspend fun getDose(id: String): DoseEntity?

    @Query("SELECT * FROM doses WHERE episodeId = :episodeId ORDER BY takenAt")
    suspend fun getDosesForEpisode(episodeId: String): List<DoseEntity>

    @Query("SELECT * FROM doses ORDER BY takenAt DESC")
    suspend fun getAllDoses(): List<DoseEntity>

    @Upsert
    suspend fun upsertDose(dose: DoseEntity)

    @Query("DELETE FROM doses WHERE id = :id")
    suspend fun deleteDose(id: String)

    @Query("DELETE FROM doses WHERE profileId = :profileId")
    suspend fun deleteDosesForProfile(profileId: String)

    // --- episodes ---
    @Query("SELECT * FROM episodes WHERE profileId = :profileId ORDER BY endedAt IS NOT NULL, startedAt DESC")
    fun observeEpisodes(profileId: String): Flow<List<EpisodeEntity>>

    @Query("SELECT * FROM episodes WHERE profileId = :profileId AND endedAt IS NULL ORDER BY startedAt DESC LIMIT 1")
    fun observeOpenEpisode(profileId: String): Flow<EpisodeEntity?>

    @Query("SELECT * FROM episodes WHERE profileId = :profileId AND endedAt IS NULL ORDER BY startedAt DESC LIMIT 1")
    suspend fun getOpenEpisode(profileId: String): EpisodeEntity?

    @Query("SELECT * FROM episodes WHERE id = :id")
    suspend fun getEpisode(id: String): EpisodeEntity?

    @Query("SELECT * FROM episodes ORDER BY startedAt DESC")
    suspend fun getAllEpisodes(): List<EpisodeEntity>

    @Upsert
    suspend fun upsertEpisode(episode: EpisodeEntity)

    @Query("DELETE FROM episodes WHERE id = :id")
    suspend fun deleteEpisode(id: String)

    @Query("DELETE FROM episodes WHERE profileId = :profileId")
    suspend fun deleteEpisodesForProfile(profileId: String)

    /** Detach an episode's rows before it is deleted, so the records themselves survive it. */
    @Transaction
    suspend fun deleteEpisodeKeepingRecords(episodeId: String) {
        clearEpisodeOnReadings(episodeId)
        clearEpisodeOnSymptoms(episodeId)
        clearEpisodeOnDoses(episodeId)
        clearEpisodeOnCareNotes(episodeId)
        deleteEpisode(episodeId)
    }

    @Query("UPDATE readings SET episodeId = NULL WHERE episodeId = :episodeId")
    suspend fun clearEpisodeOnReadings(episodeId: String)

    @Query("UPDATE symptoms SET episodeId = NULL WHERE episodeId = :episodeId")
    suspend fun clearEpisodeOnSymptoms(episodeId: String)

    @Query("UPDATE doses SET episodeId = NULL WHERE episodeId = :episodeId")
    suspend fun clearEpisodeOnDoses(episodeId: String)

    @Query("UPDATE care_notes SET episodeId = NULL WHERE episodeId = :episodeId")
    suspend fun clearEpisodeOnCareNotes(episodeId: String)

    // --- care notes ---
    @Query("SELECT * FROM care_notes WHERE profileId = :profileId ORDER BY at DESC")
    fun observeCareNotes(profileId: String): Flow<List<CareNoteEntity>>

    @Query("SELECT * FROM care_notes WHERE episodeId = :episodeId ORDER BY at")
    suspend fun getCareNotesForEpisode(episodeId: String): List<CareNoteEntity>

    @Query("SELECT * FROM care_notes ORDER BY at DESC")
    suspend fun getAllCareNotes(): List<CareNoteEntity>

    @Upsert
    suspend fun upsertCareNote(note: CareNoteEntity)

    @Query("DELETE FROM care_notes WHERE id = :id")
    suspend fun deleteCareNote(id: String)

    @Query("DELETE FROM care_notes WHERE profileId = :profileId")
    suspend fun deleteCareNotesForProfile(profileId: String)

    // --- the medicine cabinet -------------------------------------------------------------------
    //
    // Neither table takes a profile id, and that is the point: a bottle and a drug label are
    // household facts, not facts about a person. Everything per-person still hangs off `medications`.

    @Query("SELECT * FROM cabinet_items ORDER BY name COLLATE NOCASE")
    fun observeCabinetItems(): Flow<List<CabinetItemEntity>>

    @Query("SELECT * FROM cabinet_items ORDER BY name COLLATE NOCASE")
    suspend fun getCabinetItems(): List<CabinetItemEntity>

    @Query("SELECT * FROM cabinet_items WHERE id = :id")
    suspend fun getCabinetItem(id: String): CabinetItemEntity?

    @Query("SELECT * FROM cabinet_items WHERE rxcui = :rxcui LIMIT 1")
    suspend fun getCabinetItemByRxcui(rxcui: String): CabinetItemEntity?

    @Upsert
    suspend fun upsertCabinetItem(item: CabinetItemEntity)

    /**
     * Remove a bottle, keeping every medicine that was given from it. Throwing away the box does not
     * mean the child stopped taking the medicine, and it certainly does not mean the doses recorded
     * against it never happened.
     */
    @Transaction
    suspend fun deleteCabinetItemKeepingMedications(id: String) {
        clearCabinetItemOnMedications(id)
        deleteCabinetItemRow(id)
    }

    @Query("DELETE FROM cabinet_items WHERE id = :id")
    suspend fun deleteCabinetItemRow(id: String)

    @Query("SELECT * FROM drug_facts WHERE rxcui = :rxcui")
    suspend fun getDrugFacts(rxcui: String): DrugFactsEntity?

    @Query("SELECT * FROM drug_facts WHERE rxcui = :rxcui")
    fun observeDrugFacts(rxcui: String): Flow<DrugFactsEntity?>

    @Query("SELECT * FROM drug_facts")
    fun observeAllDrugFacts(): Flow<List<DrugFactsEntity>>

    @Query("SELECT * FROM drug_facts")
    suspend fun getAllDrugFacts(): List<DrugFactsEntity>

    @Upsert
    suspend fun upsertDrugFacts(facts: DrugFactsEntity)

    /**
     * Drop cached monographs nothing points at any more. A label is a convenience, not a record —
     * unlike a dose, nobody needs to know that Health once knew what was in a bottle they no longer
     * own — so the cache is allowed to be tidied where the history never is.
     */
    @Query(
        "DELETE FROM drug_facts WHERE rxcui NOT IN " +
            "(SELECT rxcui FROM medications WHERE rxcui IS NOT NULL " +
            "UNION SELECT rxcui FROM cabinet_items WHERE rxcui IS NOT NULL)"
    )
    suspend fun pruneUnreferencedDrugFacts()
}
