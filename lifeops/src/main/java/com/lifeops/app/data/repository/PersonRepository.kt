package com.lifeops.app.data.repository

import com.lifeops.app.data.db.dao.PersonDao
import com.lifeops.app.data.db.entities.TaskPersonEntity
import com.lifeops.app.data.model.Person
import com.lifeops.app.data.model.PersonNote
import com.lifeops.app.data.model.Task
import com.lifeops.app.util.DateUtil
import com.lifeops.app.util.toEntity
import com.lifeops.app.util.toModel
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
        personDao.upsertPerson(person.toEntity())
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
        personDao.upsertPerson(person.toEntity())
        return person
    }

    /** Persist an edited person (rename, preferences, archive). Upsert never wipes notes/links. */
    suspend fun update(person: Person) = personDao.upsertPerson(person.toEntity())

    suspend fun setArchived(person: Person, archived: Boolean) =
        personDao.upsertPerson(person.copy(isArchived = archived).toEntity())

    suspend fun delete(person: Person) = personDao.deletePerson(person.toEntity())

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
