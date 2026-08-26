package com.people.app.data.repository

import com.people.app.data.db.dao.PeopleDao
import com.people.app.data.db.entities.ImportantDateEntity
import com.people.app.data.db.entities.PersonEntity
import com.people.app.data.db.entities.PersonNoteEntity
import com.people.app.data.model.ImportantDate
import com.people.app.data.model.Person
import com.people.app.data.model.PersonNote
import com.people.app.logic.DateKind
import com.people.app.logic.ImportantDates
import com.people.app.logic.UpcomingDate
import com.people.app.sync.PersonPacket
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.util.UUID

/**
 * People's one repository.
 *
 * The rule that matters here: **every local edit bumps [PersonEntity.syncVersion] and stamps
 * `updatedAt`; every write that came *in* over the seam does neither.** That single distinction is
 * what stops the two peers echoing the same person back and forth for ever — a merged row that we
 * re-stamped would look, to us, exactly like a local edit worth publishing, and the round would
 * never quiesce. See [applyMerged], which is the only write that deliberately leaves both alone.
 */
class PeopleRepository(private val dao: PeopleDao) {

    private fun now() = System.currentTimeMillis()
    private fun newId() = UUID.randomUUID().toString()

    // --- reads ---

    fun observePeople(): Flow<List<Person>> = dao.observeActive().map { rows -> rows.map { it.toModel() } }

    fun observeAllPeople(): Flow<List<Person>> = dao.observeAll().map { rows -> rows.map { it.toModel() } }

    fun observePerson(id: String): Flow<Person?> = dao.observeById(id).map { it?.toModel() }

    fun observeNotes(personId: String): Flow<List<PersonNote>> =
        dao.observeNotes(personId).map { rows -> rows.map { it.toModel() } }

    fun observeDates(personId: String): Flow<List<ImportantDate>> =
        dao.observeDates(personId).map { rows -> rows.map { it.toModel() } }

    suspend fun getPerson(id: String): Person? = dao.getById(id)?.toModel()

    /**
     * Everything coming up across the household, soonest first — the roster's headline. A person's
     * birth date is folded in as a birthday automatically, so nobody has to enter it twice.
     */
    fun observeUpcoming(withinDays: Long = 60): Flow<List<Pair<Person, UpcomingDate>>> =
        combine(dao.observeAll(), dao.observeAllDates()) { people, dates ->
            val today = LocalDate.now()
            val active = people.filterNot { it.archived }
            val byId = active.associateBy { it.id }
            val resolved = ArrayList<UpcomingDate>()

            // A person's birth date is folded in as a birthday automatically, so nobody has to
            // enter the same date twice to see it here.
            active.forEach { person ->
                val monthDay = ImportantDates.monthDayOf(person.birthDate) ?: return@forEach
                ImportantDates.resolve(
                    personId = person.id,
                    label = "${person.name}'s birthday",
                    kind = DateKind.BIRTHDAY,
                    monthDay = monthDay,
                    year = ImportantDates.parseIso(person.birthDate)?.year,
                    from = today
                )?.let { resolved += it }
            }

            dates.forEach { row ->
                if (row.personId !in byId) return@forEach
                ImportantDates.resolve(
                    personId = row.personId,
                    label = row.label,
                    kind = DateKind.fromKey(row.kind),
                    monthDay = row.monthDay,
                    year = row.year,
                    from = today
                )?.let { resolved += it }
            }

            ImportantDates.upcoming(resolved, withinDays)
                .mapNotNull { date -> byId[date.personId]?.let { it.toModel() to date } }
        }

    // --- local edits (these publish) ---

    suspend fun addPerson(
        name: String,
        relationship: String?,
        birthDate: String?,
        email: String?,
        phone: String?,
        note: String?,
        colorArgb: Long,
        household: Boolean = false
    ): String {
        val id = newId()
        val timestamp = now()
        dao.upsert(
            PersonEntity(
                id = id,
                // A locally-authored person mints its own key; a person that arrives over the seam
                // keeps the key it came with (see PeopleRoster.create).
                personKey = newId(),
                name = name.trim(),
                relationship = relationship.clean(),
                birthDate = birthDate.clean(),
                email = email.clean(),
                phone = phone.clean(),
                note = note.clean(),
                household = household,
                colorArgb = colorArgb,
                archived = false,
                sortOrder = dao.nextSortOrder(),
                createdAt = timestamp,
                updatedAt = timestamp,
                syncVersion = dao.maxSyncVersion() + 1
            )
        )
        return id
    }

    suspend fun updatePerson(person: Person) {
        val existing = dao.getById(person.id) ?: return
        dao.upsert(
            existing.copy(
                name = person.name.trim(),
                relationship = person.relationship.clean(),
                birthDate = person.birthDate.clean(),
                email = person.email.clean(),
                phone = person.phone.clean(),
                note = person.note.clean(),
                household = person.household,
                colorArgb = person.colorArgb,
                archived = person.archived,
                updatedAt = now(),
                syncVersion = dao.maxSyncVersion() + 1
            )
        )
    }

    /**
     * Remove someone from the directory.
     *
     * The row goes, but a tombstone has to reach the other peers or they will simply hand the person
     * straight back on the next round — so the deletion is published as an archive instruction
     * ([PersonPacket.deleted]) rather than a hole. Peers archive; nobody's history is erased from
     * across the seam. See `sync/PersonMerge`.
     */
    suspend fun deletePerson(id: String) {
        val existing = dao.getById(id) ?: return
        dao.upsert(
            existing.copy(
                archived = true,
                updatedAt = now(),
                syncVersion = dao.maxSyncVersion() + 1
            )
        )
    }

    suspend fun addNote(personId: String, content: String): String {
        val id = newId()
        dao.upsertNote(PersonNoteEntity(id, personId, content.trim(), now()))
        return id
    }

    suspend fun deleteNote(id: String) = dao.deleteNote(id)

    suspend fun addDate(
        personId: String,
        label: String,
        kind: DateKind,
        monthDay: String,
        year: Int?,
        note: String? = null
    ): String {
        val id = newId()
        dao.upsertDate(
            ImportantDateEntity(
                id = id,
                personId = personId,
                label = label.trim(),
                kind = kind.key,
                monthDay = monthDay,
                year = year,
                note = note.clean(),
                createdAt = now()
            )
        )
        return id
    }

    suspend fun deleteDate(id: String) = dao.deleteDate(id)

    // --- the sync seam ---

    /** The outbound queue: everything edited since [sinceVersion], oldest first. */
    suspend fun changesSince(sinceVersion: Long): List<Pair<Long, PersonPacket>> =
        dao.changedSince(sinceVersion).map { it.syncVersion to it.toPacket() }

    suspend fun currentSyncVersion(): Long = dao.maxSyncVersion()

    /**
     * Store a record merged from another peer **without** bumping the sync version — this write is
     * their edit, not ours, and re-publishing it would bounce it back to them for ever.
     */
    suspend fun applyMerged(localId: String, packet: PersonPacket) {
        val existing = dao.getById(localId) ?: return
        dao.upsert(
            existing.copy(
                personKey = adoptableKey(existing.personKey, packet.personKey),
                name = packet.name,
                relationship = packet.relationship,
                birthDate = packet.birthDate,
                email = packet.email,
                phone = packet.phone,
                note = packet.note,
                // A peer with no column for the flag (LifeOps) says nothing about it; only an
                // explicit answer moves it.
                household = packet.household ?: existing.household,
                archived = packet.archived,
                updatedAt = packet.updatedAt
            )
        )
    }

    /** Create a person that arrived over the seam, keeping the key it came with. */
    suspend fun createFromPacket(packet: PersonPacket, colorArgb: Long): String {
        val id = newId()
        dao.upsert(
            PersonEntity(
                id = id,
                personKey = packet.personKey,
                name = packet.name,
                relationship = packet.relationship,
                birthDate = packet.birthDate,
                email = packet.email,
                phone = packet.phone,
                note = packet.note,
                household = packet.household ?: false,
                colorArgb = colorArgb,
                archived = packet.archived,
                sortOrder = dao.nextSortOrder(),
                createdAt = now(),
                updatedAt = packet.updatedAt,
                // Not a local edit: it does not go back out.
                syncVersion = 0L
            )
        )
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
    private suspend fun adoptableKey(current: String, incoming: String): String =
        if (incoming == current) current
        else if (dao.getByKey(incoming) == null) incoming
        else current

    suspend fun allPeople(): List<PersonEntity> = dao.getAll()

    suspend fun personEntity(id: String): PersonEntity? = dao.getById(id)

    private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
}

// --- mapping ---

fun PersonEntity.toModel() = Person(
    id = id,
    personKey = personKey,
    name = name,
    relationship = relationship,
    birthDate = birthDate,
    email = email,
    phone = phone,
    note = note,
    household = household,
    colorArgb = colorArgb,
    archived = archived,
    sortOrder = sortOrder,
    updatedAt = updatedAt
)

/** This row as the seam sees it — identity only; People's own colour and ordering stay home. */
fun PersonEntity.toPacket() = PersonPacket(
    personKey = personKey,
    name = name,
    relationship = relationship,
    birthDate = birthDate,
    email = email,
    phone = phone,
    note = note,
    household = household,
    archived = archived,
    updatedAt = updatedAt,
    deleted = false
)

fun PersonNoteEntity.toModel() = PersonNote(id, personId, content, createdAt)

fun ImportantDateEntity.toModel() = ImportantDate(
    id = id,
    personId = personId,
    label = label,
    kind = DateKind.fromKey(kind),
    monthDay = monthDay,
    year = year,
    note = note
)
