package com.lifeops.app.data.repository

import com.lifeops.app.data.db.dao.PersonDao
import com.lifeops.app.data.db.entities.PersonEntity
import com.lifeops.app.data.db.entities.PersonTombstoneEntity
import com.lifeops.app.data.db.entities.TaskPersonEntity
import com.lifeops.app.data.model.Person
import com.lifeops.app.data.model.PersonNote
import com.lifeops.app.data.model.SunSensitivity
import com.lifeops.app.data.model.Task
import com.lifeops.app.util.DateUtil
import com.lifeops.app.util.toEntity
import com.lifeops.app.util.toModel
import com.lifeops.app.util.toPacket
import com.lifeops.app.data.model.Relationship
import com.people.app.sync.PersonBinder
import com.people.app.sync.PersonPacket
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * The one thing ViewModels talk to for household people, their notes, and which tasks involve
 * them. Mirrors CounterRepository / FutureProjectRepository in shape: plain models out, entity
 * plumbing hidden. Task involvement is a many-to-many join surfaced both ways (tasks-for-person
 * and person-ids-for-task).
 */
class PersonRepository(private val personDao: PersonDao) {

    // --- People ---

    fun observeAll(): Flow<List<Person>> =
        personDao.observeAll().map { list -> list.map { it.toModel() } }

    fun observePerson(id: String): Flow<Person?> =
        personDao.observeById(id).map { it?.toModel() }

    suspend fun getById(id: String): Person? = personDao.getById(id)?.toModel()

    /** Full roster snapshot — used by the Google Calendar sync engine to resolve #tags. */
    suspend fun getAll(): List<Person> = personDao.getAll().map { it.toModel() }

    suspend fun createPerson(name: String): Person {
        val person = Person(id = UUID.randomUUID().toString(), name = name.trim(), createdAt = DateUtil.now())
        personDao.upsertPerson(person.toEntity().stamped(personKey = person.id))
        return person
    }

    /** Case-insensitive email lookup — how a pulled-in Google Calendar attendee is recognized. */
    suspend fun findByEmail(email: String): Person? =
        email.trim().takeIf { it.isNotBlank() }?.let { personDao.findByEmail(it)?.toModel() }

    /**
     * The person matching [email] (case-insensitive), or a freshly-created one if nothing matches
     * — the "generates a new person if it doesn't have someone who matches" half of Google
     * Calendar sync. [displayName] seeds the new person's name (falls back to the email's local
     * part when blank); ignored on a match, since renaming someone from calendar metadata alone
     * would be surprising.
     */
    suspend fun findOrCreateByEmail(email: String, displayName: String?): Person {
        val trimmedEmail = email.trim()
        findByEmail(trimmedEmail)?.let { return it }
        val name = displayName?.trim()?.takeIf { it.isNotBlank() } ?: trimmedEmail.substringBefore("@")
        val person = Person(
            id = UUID.randomUUID().toString(),
            name = name,
            email = trimmedEmail,
            createdAt = DateUtil.now()
        )
        personDao.upsertPerson(person.toEntity().stamped(personKey = person.id))
        return person
    }

    /**
     * Persist an edited person (rename, preferences, archive). Upsert never wipes notes/links.
     *
     * The edit is written onto the *existing row* rather than rebuilt from the model, because the
     * model deliberately carries no sync bookkeeping — rebuilding would silently reset this person's
     * key and version to a fresh row's defaults, and the seam would lose track of them.
     */
    suspend fun update(person: Person) {
        val existing = personDao.getById(person.id)
        personDao.upsertPerson(person.toEntity().stamped(personKey = existing?.personKey ?: person.id))
    }

    suspend fun setArchived(person: Person, archived: Boolean) = update(person.copy(isArchived = archived))

    /**
     * Delete a person for real — `task_people`, `busy_block_people`, `busy_blocks` and `milestones`
     * cascade off this, and that is the behaviour LifeOps has always had.
     *
     * A withdrawal is recorded first so the other peer hears about it: the deleted row is the very
     * thing that would otherwise have carried the news, and without a trace of it People would hand
     * the person back on the next round. See [PersonTombstoneEntity].
     */
    suspend fun delete(person: Person) {
        val existing = personDao.getById(person.id)
        val key = existing?.personKey ?: person.id
        personDao.upsertTombstone(
            PersonTombstoneEntity(
                personKey = key,
                name = person.name,
                deletedAt = System.currentTimeMillis(),
                syncVersion = personDao.maxSyncVersion() + 1
            )
        )
        personDao.deletePerson(person.toEntity())
    }

    /** Stamp a locally-authored row: its key, the next version, and the merge clock. */
    private suspend fun PersonEntity.stamped(personKey: String): PersonEntity = copy(
        personKey = this.personKey ?: personKey,
        syncVersion = personDao.maxSyncVersion() + 1,
        updatedAt = System.currentTimeMillis()
    )

    // --- Notes ---

    fun observeNotes(personId: String): Flow<List<PersonNote>> =
        personDao.observeNotes(personId).map { list -> list.map { it.toModel() } }

    suspend fun addNote(personId: String, content: String) {
        if (content.isBlank()) return
        personDao.insertNote(
            PersonNote(UUID.randomUUID().toString(), personId, content.trim(), DateUtil.now()).toEntity()
        )
    }

    suspend fun deleteNote(noteId: String) = personDao.deleteNote(noteId)

    // --- People sync seam ---
    //
    // The rule that keeps a round from echoing for ever: **a local edit stamps a new syncVersion
    // and updatedAt; a write that arrived over the seam stamps neither.** A merged row we
    // re-stamped would look, to us, exactly like something worth publishing, and the two peers
    // would hand the same person back and forth until one of them was closed.

    /** Everything edited locally since [sinceVersion], as packets, oldest first. */
    suspend fun changesSince(sinceVersion: Long): List<Pair<Long, PersonPacket>> {
        val edits = personDao.changedSince(sinceVersion).map { it.syncVersion to it.toPacket() }
        val withdrawals = personDao.tombstonesSince(sinceVersion).map { tombstone ->
            tombstone.syncVersion to PersonPacket(
                personKey = tombstone.personKey,
                name = tombstone.name,
                updatedAt = tombstone.deletedAt,
                deleted = true
            )
        }
        return (edits + withdrawals).sortedBy { it.first }
    }

    suspend fun currentSyncVersion(): Long = personDao.maxSyncVersion()

    /** This peer's roster, reduced to what binding looks at. */
    suspend fun bindingCandidates(): List<PersonBinder.Candidate> =
        personDao.getAll().map { PersonBinder.Candidate(it.id, it.personKey, it.name, it.email) }

    suspend fun entityById(id: String): PersonEntity? = personDao.getById(id)

    /**
     * Store a record merged from the other peer, without stamping a new outgoing version.
     *
     * Only the fields the seam actually carries are touched. LifeOps' weather tolerances and sort
     * order are its own and are never on the wire, so a merge cannot quietly blank them — which is
     * the failure mode that makes people distrust a sync.
     */
    suspend fun applyMerged(localId: String, packet: PersonPacket) {
        val existing = personDao.getById(localId) ?: return
        personDao.upsertPerson(
            existing.copy(
                personKey = existing.personKey ?: packet.personKey,
                name = packet.name,
                relationship = incomingRelationship(packet, existing.relationship),
                email = packet.email,
                phone = packet.phone,
                activityPreferences = packet.note,
                isArchived = packet.archived,
                updatedAt = packet.updatedAt
            )
        )
    }

    /** Create a person that arrived over the seam, keeping the key it came with. */
    suspend fun createFromPacket(packet: PersonPacket): String {
        val id = UUID.randomUUID().toString()
        personDao.upsertPerson(
            PersonEntity(
                id = id,
                name = packet.name,
                heatToleranceMaxF = null,
                coldToleranceMinF = null,
                uvMax = null,
                windMaxMph = null,
                maxPrecipitationPct = null,
                sunSensitivity = SunSensitivity.MODERATE.value,
                activityPreferences = packet.note,
                isArchived = packet.archived,
                sortOrder = 0,
                createdAt = DateUtil.now(),
                relationship = incomingRelationship(packet, null),
                email = packet.email,
                phone = packet.phone,
                personKey = packet.personKey,
                // Not a local edit: it does not go back out.
                syncVersion = 0L,
                updatedAt = packet.updatedAt
            )
        )
        return id
    }

    /**
     * LifeOps files a relationship as one of a closed set (`Relationship`); People keeps it as free
     * text, because households don't fit an enum. So the wire carries the stable enum *value* —
     * which round-trips — and anything that doesn't parse is **kept out rather than written in**,
     * falling back to what LifeOps already had.
     *
     * The alternative, storing "Daughter" in a column every reader parses as an enum, would not
     * merely be lossy: `Relationship.from` returns null for it, so the person would silently drop
     * out of the relationship-balance analytics that column exists to feed. A field that can't be
     * represented is better left alone than half-written.
     */
    private fun incomingRelationship(packet: PersonPacket, fallback: String?): String? =
        Relationship.from(packet.relationship?.trim()?.lowercase())?.value ?: fallback

    // --- Task involvement ---

    fun observeTasksForPerson(personId: String): Flow<List<Task>> =
        personDao.observeTasksForPerson(personId).map { list -> list.map { it.toModel() } }

    fun observePersonIdsForTask(taskId: String): Flow<List<String>> =
        personDao.observePersonIdsForTask(taskId)

    /** personId -> count of involved tasks, for the People list. */
    fun observeTaskCounts(): Flow<Map<String, Int>> =
        personDao.observeTaskCounts().map { rows -> rows.associate { it.personId to it.count } }

    /** taskId -> the person ids involved in it, for weather recommendations. */
    fun observeTaskPeople(): Flow<Map<String, List<String>>> =
        personDao.observeAllLinks().map { rows -> rows.groupBy({ it.taskId }, { it.personId }) }

    suspend fun attach(taskId: String, personId: String) =
        personDao.attach(TaskPersonEntity(taskId, personId))

    suspend fun detach(taskId: String, personId: String) =
        personDao.detach(taskId, personId)
}
