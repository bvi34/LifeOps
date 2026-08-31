package com.people.app.data.repository

import com.people.app.data.db.dao.CheckInDao
import com.people.app.data.db.dao.CheckInDayCount
import com.people.app.data.db.entities.CheckInAnswerEntity
import com.people.app.data.db.entities.CheckInEntity
import com.people.app.data.db.entities.CheckInFieldEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * The check-in tables in memory, so the repository's rules can be driven on the JVM.
 *
 * Each method reimplements what its `@Query` says, including the parts a lazier fake would skip and
 * a test would then silently pass on: the ordering that puts retired questions last, `nextPosition`
 * counting retired rows too, and the answer lookups being joins through the day rather than lookups
 * by row id.
 */
class FakeCheckInDao : CheckInDao {

    private val fields = MutableStateFlow<List<CheckInFieldEntity>>(emptyList())
    private val checkIns = MutableStateFlow<List<CheckInEntity>>(emptyList())
    private val answers = MutableStateFlow<List<CheckInAnswerEntity>>(emptyList())

    private fun List<CheckInFieldEntity>.formOrder() =
        sortedWith(compareBy({ it.retired }, { it.position }))

    // --- the form ---

    override fun observeFields(personId: String): Flow<List<CheckInFieldEntity>> =
        fields.map { rows -> rows.filter { it.personId == personId }.formOrder() }

    override suspend fun fields(personId: String): List<CheckInFieldEntity> =
        fields.value.filter { it.personId == personId }.formOrder()

    override suspend fun field(id: String): CheckInFieldEntity? =
        fields.value.firstOrNull { it.id == id }

    override suspend fun nextPosition(personId: String): Int =
        (fields.value.filter { it.personId == personId }.maxOfOrNull { it.position } ?: -1) + 1

    override suspend fun upsertField(field: CheckInFieldEntity) {
        fields.value = fields.value.filterNot { it.id == field.id } + field
    }

    override suspend fun upsertFields(fields: List<CheckInFieldEntity>) {
        val ids = fields.map { it.id }.toSet()
        this.fields.value = this.fields.value.filterNot { it.id in ids } + fields
    }

    override suspend fun deleteField(id: String) {
        fields.value = fields.value.filterNot { it.id == id }
        // The foreign key's cascade, by hand — a row deleted here takes any answers with it.
        answers.value = answers.value.filterNot { it.fieldId == id }
    }

    override suspend fun answerCount(fieldId: String): Int =
        answers.value.count { it.fieldId == fieldId }

    // --- the days ---

    override fun observeDay(personId: String, day: String): Flow<CheckInEntity?> =
        checkIns.map { rows -> rows.firstOrNull { it.personId == personId && it.day == day } }

    override suspend fun day(personId: String, day: String): CheckInEntity? =
        checkIns.value.firstOrNull { it.personId == personId && it.day == day }

    override fun observeRecent(personId: String, limit: Int): Flow<List<CheckInEntity>> =
        checkIns.map { rows ->
            rows.filter { it.personId == personId }.sortedByDescending { it.day }.take(limit)
        }

    override fun observeDays(personId: String): Flow<List<String>> =
        checkIns.map { rows ->
            rows.filter { it.personId == personId }.map { it.day }.sortedDescending()
        }

    override fun observeDayCounts(): Flow<List<CheckInDayCount>> =
        checkIns.map { rows ->
            rows.groupingBy { it.personId }.eachCount().map { (personId, days) ->
                CheckInDayCount(personId, days)
            }
        }

    override fun observeOutstanding(day: String): Flow<List<String>> =
        combine(fields, checkIns) { fieldRows, dayRows ->
            val done = dayRows.filter { it.day == day }.map { it.personId }.toSet()
            fieldRows.filterNot { it.retired }.map { it.personId }.distinct().filterNot { it in done }
        }

    override suspend fun upsertCheckIn(checkIn: CheckInEntity) {
        checkIns.value = checkIns.value.filterNot { it.id == checkIn.id } + checkIn
    }

    override suspend fun deleteCheckIn(id: String) {
        checkIns.value = checkIns.value.filterNot { it.id == id }
        answers.value = answers.value.filterNot { it.checkInId == id }
    }

    // --- the answers ---

    override fun observeAnswersOn(personId: String, day: String): Flow<List<CheckInAnswerEntity>> =
        combine(answers, checkIns) { answerRows, dayRows ->
            val ids = dayRows.filter { it.personId == personId && it.day == day }.map { it.id }.toSet()
            answerRows.filter { it.checkInId in ids }
        }

    override fun observeRecentAnswers(personId: String, limit: Int): Flow<List<CheckInAnswerEntity>> =
        combine(answers, checkIns) { answerRows, dayRows ->
            val ids = dayRows
                .filter { it.personId == personId }
                .sortedByDescending { it.day }
                .take(limit)
                .map { it.id }
                .toSet()
            answerRows.filter { it.checkInId in ids }
        }

    override suspend fun upsertAnswers(answers: List<CheckInAnswerEntity>) {
        val keys = answers.map { it.checkInId to it.fieldId }.toSet()
        this.answers.value = this.answers.value.filterNot { (it.checkInId to it.fieldId) in keys } + answers
    }

    override suspend fun clearAnswers(checkInId: String) {
        answers.value = answers.value.filterNot { it.checkInId == checkInId }
    }
}
