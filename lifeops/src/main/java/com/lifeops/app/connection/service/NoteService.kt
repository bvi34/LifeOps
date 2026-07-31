package com.lifeops.app.connection.service

import com.lifeops.app.data.repository.TaskNoteRepository

/** Use-case layer for task notes. */
class NoteService(private val taskNoteRepository: TaskNoteRepository) {

    /** Add a note to a task. Returns false if [content] is blank. */
    suspend fun add(taskId: String, content: String, subtaskId: String? = null): Boolean {
        if (content.isBlank()) return false
        taskNoteRepository.addNote(taskId, content.trim(), subtaskId)
        return true
    }

    suspend fun delete(noteId: String) {
        taskNoteRepository.deleteNote(noteId)
    }
}
