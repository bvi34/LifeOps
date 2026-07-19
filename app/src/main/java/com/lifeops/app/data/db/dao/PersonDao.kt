package com.lifeops.app.data.db.dao

import androidx.room.*
import com.lifeops.app.data.db.entities.PersonEntity
import com.lifeops.app.data.db.entities.PersonNoteEntity
import com.lifeops.app.data.db.entities.TaskEntity
import com.lifeops.app.data.db.entities.TaskPersonEntity
import kotlinx.coroutines.flow.Flow

/** personId -> number of tasks currently involving them, for the list screen. */
data class PersonTaskCount(val personId: String, val count: Int)

@Dao
interface PersonDao {

    // --- People ---

    // @Upsert (not @Insert REPLACE) so an edit never deletes-then-reinserts the row, which would
    // cascade-wipe the person's notes and task links.
    @Upsert
    suspend fun upsertPerson(person: PersonEntity)

    @Delete
    suspend fun deletePerson(person: PersonEntity)

    @Query("SELECT * FROM persons ORDER BY isArchived ASC, sortOrder ASC, createdAt ASC")
    fun observeAll(): Flow<List<PersonEntity>>

    @Query("SELECT * FROM persons WHERE id = :id")
    fun observeById(id: String): Flow<PersonEntity?>

    @Query("SELECT * FROM persons WHERE id = :id")
    suspend fun getById(id: String): PersonEntity?

    @Query("SELECT * FROM persons")
    suspend fun getAll(): List<PersonEntity>

    // --- Notes ---

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNote(note: PersonNoteEntity)

    @Query("SELECT * FROM person_notes WHERE personId = :personId ORDER BY createdAt DESC")
    fun observeNotes(personId: String): Flow<List<PersonNoteEntity>>

    @Query("SELECT * FROM person_notes")
    suspend fun getAllNotes(): List<PersonNoteEntity>

    @Query("DELETE FROM person_notes WHERE id = :id")
    suspend fun deleteNote(id: String)

    // --- Task involvement (join) ---

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun attach(link: TaskPersonEntity)

    @Query("DELETE FROM task_people WHERE taskId = :taskId AND personId = :personId")
    suspend fun detach(taskId: String, personId: String)

    /** Every task involving [personId], newest first. */
    @Query(
        """
        SELECT t.* FROM tasks t
        INNER JOIN task_people tp ON tp.taskId = t.id
        WHERE tp.personId = :personId
        ORDER BY t.createdAt DESC
        """
    )
    fun observeTasksForPerson(personId: String): Flow<List<TaskEntity>>

    /** Person ids attached to [taskId] — for showing "involves …" on a task. */
    @Query("SELECT personId FROM task_people WHERE taskId = :taskId")
    fun observePersonIdsForTask(taskId: String): Flow<List<String>>

    @Query("SELECT personId AS personId, COUNT(*) AS count FROM task_people GROUP BY personId")
    fun observeTaskCounts(): Flow<List<PersonTaskCount>>

    @Query("SELECT * FROM task_people")
    fun observeAllLinks(): Flow<List<TaskPersonEntity>>

    @Query("SELECT * FROM task_people")
    suspend fun getAllLinks(): List<TaskPersonEntity>
}
