package com.health.app.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.health.app.data.db.entities.CareNoteEntity
import com.health.app.data.db.entities.DoseEntity
import com.health.app.data.db.entities.EpisodeEntity
import com.health.app.data.db.entities.MedicationEntity
import com.health.app.data.db.entities.ProfileEntity
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

    @Query("SELECT COALESCE(MAX(syncVersion), 0) FROM profiles")
    suspend fun maxSyncVersion(): Long

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

    @Upsert
    suspend fun upsertMedication(medication: MedicationEntity)

    @Query("DELETE FROM medications WHERE id = :id")
    suspend fun deleteMedication(id: String)

    @Query("DELETE FROM medications WHERE profileId = :profileId")
    suspend fun deleteMedicationsForProfile(profileId: String)

    // --- doses ---
    @Query("SELECT * FROM doses WHERE profileId = :profileId ORDER BY takenAt DESC")
    fun observeDoses(profileId: String): Flow<List<DoseEntity>>

    /** The trailing window every dose limit is judged against — see `logic/DoseSchedule`. */
    @Query("SELECT * FROM doses WHERE profileId = :profileId AND takenAt >= :since ORDER BY takenAt")
    fun observeDosesSince(profileId: String, since: Long): Flow<List<DoseEntity>>

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
}
