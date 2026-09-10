package com.health.app.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.health.app.data.db.entities.AllergyEntity
import com.health.app.data.db.entities.CabinetItemEntity
import com.health.app.data.db.entities.CareNoteEntity
import com.health.app.data.db.entities.ConditionEntity
import com.health.app.data.db.entities.DocumentEntity
import com.health.app.data.db.entities.DoseEntity
import com.health.app.data.db.entities.DrugFactsEntity
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
        // Their membership of the household's policies and their side of the care team go too. The
        // policy itself and the doctors themselves do not: those belong to the household, and one
        // person leaving it is not a reason to forget the family plan or the family dentist.
        deleteInsuranceMembersForProfile(profileId)
        deleteProviderLinksForProfile(profileId)
        // Their standing record goes too. An allergy is the least shareable fact in the app: it is
        // about one person and is meaningless — and dangerous — attached to anybody else.
        deleteAllergiesForProfile(profileId)
        deleteConditionsForProfile(profileId)
        deleteImmunizationsForProfile(profileId)
        // Their documents go too. A household document (profileId null) is nobody's to delete here,
        // and stays: an insurance statement is not about the person who has left.
        deleteDocumentsForProfile(profileId)
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

    /** One row, read before it is deleted so the delete can be offered back. */
    @Query("SELECT * FROM readings WHERE id = :id")
    suspend fun getReading(id: String): ReadingEntity?

    // --- the history window --------------------------------------------------------------------
    //
    // Four `…Since` queries, one per kind of record, so a person's history can be read over a span
    // rather than only inside a declared illness. Not everything worth reconstructing happened
    // during one.

    @Query("SELECT * FROM readings WHERE profileId = :profileId AND takenAt >= :since ORDER BY takenAt")
    suspend fun getReadingsSince(profileId: String, since: Long): List<ReadingEntity>

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

    /** A symptom that started before the window but ended inside it belongs in it — hence the OR. */
    @Query(
        "SELECT * FROM symptoms WHERE profileId = :profileId " +
            "AND (startedAt >= :since OR endedAt >= :since) ORDER BY startedAt"
    )
    suspend fun getSymptomsSince(profileId: String, since: Long): List<SymptomEntity>

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

    @Query("SELECT * FROM doses WHERE profileId = :profileId AND takenAt >= :since ORDER BY takenAt")
    suspend fun getDosesSince(profileId: String, since: Long): List<DoseEntity>

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

    /**
     * The episode this person was in the middle of **at a given instant** — the one whose span
     * contains it, open episodes included.
     *
     * This is what a record gets filed against, rather than "whichever episode happens to be open
     * right now". The two are the same thing only when everything is recorded as it happens; the
     * moment somebody types up last night's dose, or fills in a week of last month's flu, they stop
     * being the same thing and the second answer is the wrong one.
     *
     * Latest start wins if spans somehow overlap — the repository allows only one open episode per
     * person, but a reopened one can be edited into overlapping a closed one, and the more recent
     * illness is the better guess for a record inside both.
     */
    @Query(
        "SELECT * FROM episodes WHERE profileId = :profileId AND startedAt <= :atMillis " +
            "AND (endedAt IS NULL OR endedAt >= :atMillis) ORDER BY startedAt DESC LIMIT 1"
    )
    suspend fun getEpisodeAt(profileId: String, atMillis: Long): EpisodeEntity?

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

    /** One row, read before it is deleted so the delete can be offered back. */
    @Query("SELECT * FROM care_notes WHERE id = :id")
    suspend fun getCareNote(id: String): CareNoteEntity?

    @Query("SELECT * FROM care_notes WHERE profileId = :profileId AND at >= :since ORDER BY at")
    suspend fun getCareNotesSince(profileId: String, since: Long): List<CareNoteEntity>

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

    // --- coverage: plans and who is on them -------------------------------------------------------
    //
    // The plan is household-scoped and the membership is not, exactly as the cabinet splits a bottle
    // from a person's dose of it. A family policy is one row; four people on it are four rows here.

    @Query("SELECT * FROM insurance_plans ORDER BY archived, carrierName COLLATE NOCASE")
    fun observeInsurancePlans(): Flow<List<InsurancePlanEntity>>

    @Query("SELECT * FROM insurance_plans ORDER BY archived, carrierName COLLATE NOCASE")
    suspend fun getInsurancePlans(): List<InsurancePlanEntity>

    @Query("SELECT * FROM insurance_plans WHERE id = :id")
    suspend fun getInsurancePlan(id: String): InsurancePlanEntity?

    @Upsert
    suspend fun upsertInsurancePlan(plan: InsurancePlanEntity)

    @Query("SELECT * FROM insurance_members ORDER BY primaryCoverage DESC")
    fun observeInsuranceMembers(): Flow<List<InsuranceMemberEntity>>

    @Query("SELECT * FROM insurance_members WHERE profileId = :profileId ORDER BY primaryCoverage DESC")
    fun observeInsuranceMembers(profileId: String): Flow<List<InsuranceMemberEntity>>

    @Query("SELECT * FROM insurance_members WHERE id = :id")
    suspend fun getInsuranceMember(id: String): InsuranceMemberEntity?

    @Query("SELECT * FROM insurance_members WHERE planId = :planId")
    suspend fun getInsuranceMembersForPlan(planId: String): List<InsuranceMemberEntity>

    @Query("SELECT * FROM insurance_members")
    suspend fun getInsuranceMembers(): List<InsuranceMemberEntity>

    @Upsert
    suspend fun upsertInsuranceMember(member: InsuranceMemberEntity)

    @Query("DELETE FROM insurance_members WHERE id = :id")
    suspend fun deleteInsuranceMember(id: String)

    @Query("DELETE FROM insurance_members WHERE profileId = :profileId")
    suspend fun deleteInsuranceMembersForProfile(profileId: String)

    /**
     * Remove a policy along with everybody's membership of it.
     *
     * The network checks made under it are **kept**. A plan Health no longer holds is still the plan
     * that listed a doctor last March, and that listing is the only reason the app can later say
     * somebody was dropped rather than never listed — see [NetworkCheckEntity]. The check rows carry
     * the carrier's name at the time, so they still read correctly with the plan gone.
     */
    @Transaction
    suspend fun deleteInsurancePlanCascade(planId: String) {
        deleteInsuranceMembersForPlan(planId)
        deleteInsurancePlanRow(planId)
    }

    @Query("DELETE FROM insurance_members WHERE planId = :planId")
    suspend fun deleteInsuranceMembersForPlan(planId: String)

    @Query("DELETE FROM insurance_plans WHERE id = :id")
    suspend fun deleteInsurancePlanRow(id: String)

    // --- the care team ----------------------------------------------------------------------------
    //
    // A doctor is household-scoped and deliberately not owned by a plan: the policy changes every
    // January and the paediatrician doesn't.

    @Query("SELECT * FROM providers ORDER BY name COLLATE NOCASE")
    fun observeProviders(): Flow<List<ProviderEntity>>

    @Query("SELECT * FROM providers ORDER BY name COLLATE NOCASE")
    suspend fun getProviders(): List<ProviderEntity>

    @Query("SELECT * FROM providers WHERE id = :id")
    suspend fun getProvider(id: String): ProviderEntity?

    @Upsert
    suspend fun upsertProvider(provider: ProviderEntity)

    @Query("SELECT * FROM provider_links")
    fun observeProviderLinks(): Flow<List<ProviderLinkEntity>>

    @Query("SELECT * FROM provider_links WHERE profileId = :profileId")
    fun observeProviderLinks(profileId: String): Flow<List<ProviderLinkEntity>>

    @Query("SELECT * FROM provider_links WHERE id = :id")
    suspend fun getProviderLink(id: String): ProviderLinkEntity?

    @Query("SELECT * FROM provider_links WHERE providerId = :providerId")
    suspend fun getProviderLinksFor(providerId: String): List<ProviderLinkEntity>

    @Query("SELECT * FROM provider_links")
    suspend fun getProviderLinks(): List<ProviderLinkEntity>

    @Upsert
    suspend fun upsertProviderLink(link: ProviderLinkEntity)

    @Query("DELETE FROM provider_links WHERE id = :id")
    suspend fun deleteProviderLink(id: String)

    @Query("DELETE FROM provider_links WHERE profileId = :profileId")
    suspend fun deleteProviderLinksForProfile(profileId: String)

    /**
     * Remove a provider, along with everybody who saw them and every check made about them.
     *
     * The one place in this file where history is genuinely thrown away, and it is the right call:
     * a network check is evidence *about a provider*, so with the provider gone it is evidence about
     * nothing. That is not true of a dose, which happened to a person and stays.
     */
    @Transaction
    suspend fun deleteProviderCascade(providerId: String) {
        deleteProviderLinksForProvider(providerId)
        deleteNetworkChecksForProvider(providerId)
        // A condition they managed is a fact about the *patient*, so it stays and simply loses its
        // clinician — the same reasoning that keeps a dose when its medicine is deleted. Removing a
        // doctor from the care team must never be a way to delete somebody's asthma.
        clearProviderOnConditions(providerId)
        // Same reasoning: the dose was still given, whoever gave it and wherever they work now.
        clearProviderOnImmunizations(providerId)
        deleteProviderRow(providerId)
    }

    @Query("UPDATE conditions SET providerId = NULL WHERE providerId = :providerId")
    suspend fun clearProviderOnConditions(providerId: String)

    @Query("UPDATE immunizations SET providerId = NULL WHERE providerId = :providerId")
    suspend fun clearProviderOnImmunizations(providerId: String)

    @Query("DELETE FROM provider_links WHERE providerId = :providerId")
    suspend fun deleteProviderLinksForProvider(providerId: String)

    @Query("DELETE FROM providers WHERE id = :id")
    suspend fun deleteProviderRow(id: String)

    // --- network checks ---------------------------------------------------------------------------
    //
    // Append-only. Nothing in this file updates a check or deletes one to make room for a newer
    // answer: the older answers are what let `logic/NetworkStatus` tell "left the network" apart from
    // "never in it", which is the most useful thing the feature produces.

    @Query("SELECT * FROM network_checks ORDER BY checkedAt")
    fun observeNetworkChecks(): Flow<List<NetworkCheckEntity>>

    @Query("SELECT * FROM network_checks WHERE providerId = :providerId ORDER BY checkedAt")
    fun observeNetworkChecks(providerId: String): Flow<List<NetworkCheckEntity>>

    @Query("SELECT * FROM network_checks WHERE providerId = :providerId ORDER BY checkedAt")
    suspend fun getNetworkChecks(providerId: String): List<NetworkCheckEntity>

    @Query("SELECT * FROM network_checks ORDER BY checkedAt")
    suspend fun getAllNetworkChecks(): List<NetworkCheckEntity>

    @Upsert
    suspend fun upsertNetworkCheck(check: NetworkCheckEntity)

    @Query("DELETE FROM network_checks WHERE providerId = :providerId")
    suspend fun deleteNetworkChecksForProvider(providerId: String)

    /**
     * Drop one recorded check — for the mis-taps, and only for those. Offered because a phone
     * confirmation typed against the wrong doctor is worse than no record at all; not offered in
     * bulk, because "clear the history" here means "make the app forget that this doctor used to be
     * in network", which is exactly the fact worth keeping.
     */
    @Query("DELETE FROM network_checks WHERE id = :id")
    suspend fun deleteNetworkCheck(id: String)

    // --- the standing record ----------------------------------------------------------------------
    //
    // Allergies and conditions are per-person and are never household-scoped — the one part of this
    // schema where sharing a row between people would be actively dangerous rather than merely wrong.
    //
    // Neither is ordered meaningfully in SQL. Severity and status are stored as their keys, and
    // ordering by those alphabetically would put "mild" above "severe"; the real order comes from the
    // enums in `logic/`, applied by the repository, so there is exactly one definition of "worst
    // first" in the app.

    @Query("SELECT * FROM allergies WHERE profileId = :profileId ORDER BY substance COLLATE NOCASE")
    fun observeAllergies(profileId: String): Flow<List<AllergyEntity>>

    @Query("SELECT * FROM allergies WHERE profileId = :profileId ORDER BY substance COLLATE NOCASE")
    suspend fun getAllergies(profileId: String): List<AllergyEntity>

    @Query("SELECT * FROM allergies ORDER BY substance COLLATE NOCASE")
    suspend fun getAllAllergies(): List<AllergyEntity>

    @Query("SELECT * FROM allergies WHERE id = :id")
    suspend fun getAllergy(id: String): AllergyEntity?

    @Upsert
    suspend fun upsertAllergy(allergy: AllergyEntity)

    @Query("DELETE FROM allergies WHERE id = :id")
    suspend fun deleteAllergy(id: String)

    @Query("DELETE FROM allergies WHERE profileId = :profileId")
    suspend fun deleteAllergiesForProfile(profileId: String)

    @Query("SELECT * FROM conditions WHERE profileId = :profileId ORDER BY name COLLATE NOCASE")
    fun observeConditions(profileId: String): Flow<List<ConditionEntity>>

    @Query("SELECT * FROM conditions WHERE profileId = :profileId ORDER BY name COLLATE NOCASE")
    suspend fun getConditions(profileId: String): List<ConditionEntity>

    @Query("SELECT * FROM conditions ORDER BY name COLLATE NOCASE")
    suspend fun getAllConditions(): List<ConditionEntity>

    @Query("SELECT * FROM conditions WHERE id = :id")
    suspend fun getCondition(id: String): ConditionEntity?

    @Upsert
    suspend fun upsertCondition(condition: ConditionEntity)

    @Query("DELETE FROM conditions WHERE id = :id")
    suspend fun deleteCondition(id: String)

    @Query("DELETE FROM conditions WHERE profileId = :profileId")
    suspend fun deleteConditionsForProfile(profileId: String)

    // --- the vaccination record -------------------------------------------------------------------
    //
    // Ordered by date here only so the rows arrive in a sensible order; the grouping into series is
    // `logic/Immunizations`' job, because a series is a judgement about which names mean the same
    // vaccine and SQL has no opinion about whether "M.M.R." and "MMR" are one thing.

    @Query("SELECT * FROM immunizations WHERE profileId = :profileId ORDER BY givenDate DESC")
    fun observeImmunizations(profileId: String): Flow<List<ImmunizationEntity>>

    @Query("SELECT * FROM immunizations WHERE profileId = :profileId ORDER BY givenDate DESC")
    suspend fun getImmunizations(profileId: String): List<ImmunizationEntity>

    @Query("SELECT * FROM immunizations ORDER BY givenDate DESC")
    suspend fun getAllImmunizations(): List<ImmunizationEntity>

    @Query("SELECT * FROM immunizations WHERE id = :id")
    suspend fun getImmunization(id: String): ImmunizationEntity?

    @Upsert
    suspend fun upsertImmunization(immunization: ImmunizationEntity)

    @Query("DELETE FROM immunizations WHERE id = :id")
    suspend fun deleteImmunization(id: String)

    @Query("DELETE FROM immunizations WHERE profileId = :profileId")
    suspend fun deleteImmunizationsForProfile(profileId: String)

    // --- documents --------------------------------------------------------------------------------
    //
    // A document belongs to a person or to the household, so there are two observers rather than one
    // filtered query — "her lab results" and "the paperwork" are different lists that are read in
    // different places, and folding them together would put the family's insurance statement into a
    // child's medical record.
    //
    // Deleting a row never deletes its file: the repository does that afterwards, through the store,
    // because a file removed ahead of a write that then fails leaves a document pointing at nothing.

    @Query("SELECT * FROM documents WHERE profileId = :profileId ORDER BY documentDate DESC")
    fun observeDocuments(profileId: String): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE profileId IS NULL ORDER BY documentDate DESC")
    fun observeHouseholdDocuments(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents ORDER BY documentDate DESC")
    suspend fun getAllDocuments(): List<DocumentEntity>

    /** Every document, as it changes — what Health lends the suite's shelf. See `shelf/`. */
    @Query("SELECT * FROM documents ORDER BY documentDate DESC")
    fun observeAllDocuments(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE id = :id")
    suspend fun getDocument(id: String): DocumentEntity?

    @Query("SELECT * FROM documents WHERE episodeId = :episodeId ORDER BY documentDate")
    suspend fun getDocumentsForEpisode(episodeId: String): List<DocumentEntity>

    @Upsert
    suspend fun upsertDocument(document: DocumentEntity)

    @Query("DELETE FROM documents WHERE id = :id")
    suspend fun deleteDocumentRow(id: String)

    @Query("DELETE FROM documents WHERE profileId = :profileId")
    suspend fun deleteDocumentsForProfile(profileId: String)

    /** The file names a profile's documents point at, read before the cascade drops the rows. */
    @Query("SELECT fileName FROM documents WHERE profileId = :profileId")
    suspend fun documentFileNamesForProfile(profileId: String): List<String>
}
