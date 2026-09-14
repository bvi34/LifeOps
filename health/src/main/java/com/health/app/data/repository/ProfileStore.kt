package com.health.app.data.repository

import com.health.app.data.db.dao.HealthDao
import com.health.app.data.db.entities.ProfileEntity
import com.health.app.data.db.entities.ProfileTombstoneEntity
import com.health.app.data.model.Profile
import com.health.app.data.prefs.HealthPrefs
import com.health.app.logic.TempUnit
import com.health.app.logic.WeightUnit
import com.people.app.sync.LocalRosterChange
import com.people.app.sync.PersonBinder
import com.people.app.sync.PersonPacket
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Who Health keeps records for, and the People sync seam that keeps that roster agreeing with
 * the rest of the suite.

 * The seam is here rather than beside the readings because a profile is the only thing in Health
 * that also exists somewhere else. Everything else in this package is Health's alone.
 */
class ProfileStore(
    private val dao: HealthDao,
    private val prefs: HealthPrefs,
    private val onProfileEdit: (LocalRosterChange) -> Unit,
    private val onDocumentDiscarded: (fileName: String) -> Unit
) {

    fun observeProfiles(): Flow<List<Profile>> = dao.observeProfiles().map { rows -> rows.map { it.toModel() } }

    fun observeAllProfiles(): Flow<List<Profile>> = dao.observeAllProfiles().map { rows -> rows.map { it.toModel() } }

    fun observeTemperatureUnit(): Flow<TempUnit> = prefs.observeTemperatureUnit()

    fun setTemperatureUnit(unit: TempUnit) {
        prefs.temperatureUnit = unit
    }

    fun observeWeightUnit(): Flow<WeightUnit> = prefs.observeWeightUnit()

    fun setWeightUnit(unit: WeightUnit) {
        prefs.weightUnit = unit
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

    companion object {
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
