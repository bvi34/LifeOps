package com.people.app.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.people.app.data.db.entities.CheckInAnswerEntity
import com.people.app.data.db.entities.CheckInEntity
import com.people.app.data.db.entities.CheckInFieldEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CheckInDao {

    // --- the form ---

    /** Every question, retired ones included — what the form editor shows. */
    @Query("SELECT * FROM check_in_fields WHERE personId = :personId ORDER BY retired, position")
    fun observeFields(personId: String): Flow<List<CheckInFieldEntity>>

    @Query("SELECT * FROM check_in_fields WHERE personId = :personId ORDER BY retired, position")
    suspend fun fields(personId: String): List<CheckInFieldEntity>

    @Query("SELECT * FROM check_in_fields WHERE id = :id")
    suspend fun field(id: String): CheckInFieldEntity?

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM check_in_fields WHERE personId = :personId")
    suspend fun nextPosition(personId: String): Int

    @Upsert
    suspend fun upsertField(field: CheckInFieldEntity)

    @Upsert
    suspend fun upsertFields(fields: List<CheckInFieldEntity>)

    /**
     * Only ever called for a question nothing has answered — see `CheckInRepository.removeField`.
     * The answers' foreign key would cascade, and cascading away six months of lunches because
     * somebody tidied their form is exactly what the retire flag exists to prevent.
     */
    @Query("DELETE FROM check_in_fields WHERE id = :id")
    suspend fun deleteField(id: String)

    @Query("SELECT COUNT(*) FROM check_in_answers WHERE fieldId = :fieldId")
    suspend fun answerCount(fieldId: String): Int

    // --- the days ---

    @Query("SELECT * FROM check_ins WHERE personId = :personId AND day = :day LIMIT 1")
    fun observeDay(personId: String, day: String): Flow<CheckInEntity?>

    @Query("SELECT * FROM check_ins WHERE personId = :personId AND day = :day LIMIT 1")
    suspend fun day(personId: String, day: String): CheckInEntity?

    @Query("SELECT * FROM check_ins WHERE personId = :personId ORDER BY day DESC LIMIT :limit")
    fun observeRecent(personId: String, limit: Int): Flow<List<CheckInEntity>>

    /** The days themselves, for the streak — a string per recorded day and nothing else. */
    @Query("SELECT day FROM check_ins WHERE personId = :personId ORDER BY day DESC")
    fun observeDays(personId: String): Flow<List<String>>

    @Query("SELECT personId, COUNT(*) AS days FROM check_ins GROUP BY personId")
    fun observeDayCounts(): Flow<List<CheckInDayCount>>

    /** Whose check-in is still outstanding today: every person with a form, minus today's rows. */
    @Query(
        "SELECT DISTINCT f.personId FROM check_in_fields f WHERE f.retired = 0 AND f.personId NOT IN " +
            "(SELECT c.personId FROM check_ins c WHERE c.day = :day)"
    )
    fun observeOutstanding(day: String): Flow<List<String>>

    @Upsert
    suspend fun upsertCheckIn(checkIn: CheckInEntity)

    @Query("DELETE FROM check_ins WHERE id = :id")
    suspend fun deleteCheckIn(id: String)

    // --- the answers ---

    /**
     * One day's answers, joined through the day rather than looked up by its row id.
     *
     * That keeps the screen's read a plain `combine` of three queries: asking for the id first and
     * *then* its answers would make the observation depend on the result of an observation, which is
     * a `flatMapLatest` and a re-subscription every time the day is saved.
     */
    @Query(
        "SELECT a.* FROM check_in_answers a JOIN check_ins c ON a.checkInId = c.id " +
            "WHERE c.personId = :personId AND c.day = :day"
    )
    fun observeAnswersOn(personId: String, day: String): Flow<List<CheckInAnswerEntity>>

    /** The answers belonging to the same recent days [observeRecent] returns, and no others. */
    @Query(
        "SELECT a.* FROM check_in_answers a JOIN check_ins c ON a.checkInId = c.id " +
            "WHERE c.personId = :personId AND c.day IN " +
            "(SELECT day FROM check_ins WHERE personId = :personId ORDER BY day DESC LIMIT :limit)"
    )
    fun observeRecentAnswers(personId: String, limit: Int): Flow<List<CheckInAnswerEntity>>

    @Upsert
    suspend fun upsertAnswers(answers: List<CheckInAnswerEntity>)

    @Query("DELETE FROM check_in_answers WHERE checkInId = :checkInId")
    suspend fun clearAnswers(checkInId: String)

    // --- the whole log, once ---
    //
    // One-shot reads across every person, for a reader that wants the log entire rather than one
    // person's screen: Advisor's knowledge source flattens it into its retrieval corpus on each
    // question. They are suspend rather than Flow because such a reader takes a snapshot and is
    // done, with nothing left to observe.

    @Query("SELECT * FROM check_in_fields ORDER BY personId, retired, position")
    suspend fun allFields(): List<CheckInFieldEntity>

    @Query("SELECT * FROM check_ins ORDER BY personId, day DESC")
    suspend fun allCheckIns(): List<CheckInEntity>

    @Query("SELECT * FROM check_in_answers")
    suspend fun allAnswers(): List<CheckInAnswerEntity>
}

/** How many days one person has recorded, for the roster. */
data class CheckInDayCount(val personId: String, val days: Int)
