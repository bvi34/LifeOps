package com.lifeops.app.connection.service

import com.lifeops.app.data.model.Person
import com.lifeops.app.data.repository.PersonRepository

/** Use-case layer for household people, their notes, and task involvement. */
class PersonService(private val personRepository: PersonRepository) {

    suspend fun create(name: String): Person {
        require(name.isNotBlank()) { "Person name must not be blank" }
        return personRepository.createPerson(name)
    }

    suspend fun rename(id: String, name: String): Person? {
        require(name.isNotBlank()) { "Person name must not be blank" }
        val current = personRepository.getById(id) ?: return null
        val updated = current.copy(name = name.trim())
        personRepository.update(updated)
        return updated
    }

    suspend fun setArchived(id: String, archived: Boolean): Boolean {
        val person = personRepository.getById(id) ?: return false
        personRepository.setArchived(person, archived)
        return true
    }

    suspend fun delete(id: String): Boolean {
        val person = personRepository.getById(id) ?: return false
        personRepository.delete(person)
        return true
    }

    /** Add a note to a person. False if the person is unknown or [content] is blank. */
    suspend fun addNote(personId: String, content: String): Boolean {
        if (content.isBlank()) return false
        personRepository.getById(personId) ?: return false
        personRepository.addNote(personId, content)
        return true
    }

    suspend fun deleteNote(noteId: String) = personRepository.deleteNote(noteId)

    suspend fun attach(taskId: String, personId: String): Boolean {
        personRepository.getById(personId) ?: return false
        personRepository.attach(taskId, personId)
        return true
    }

    suspend fun detach(taskId: String, personId: String) = personRepository.detach(taskId, personId)
}
