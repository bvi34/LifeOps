package com.people.app.data.repository

import com.people.app.data.db.dao.CheckInDao
import com.people.app.data.db.entities.CheckInAnswerEntity
import com.people.app.data.db.entities.CheckInEntity
import com.people.app.data.db.entities.CheckInFieldEntity
import com.people.app.data.model.CheckIn
import com.people.app.data.model.CheckInAnswer
import com.people.app.data.model.CheckInField
import com.people.app.logic.CheckInKind
import com.people.app.logic.CheckIns
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.util.UUID

/**
 * The store for a person's daily check-in: their form, their days, and the answers between them.
 *
 * Two rules live here rather than in a screen, because both have to hold however the user got there:
 *
 *  - **A day is recorded only if it says something.** Saving a form nobody filled in does not create
 *    a day, and clearing every answer removes the day rather than leaving an empty one behind. A log
 *    of blank days would make every count off it — the streak, "have I done today?", how often
 *    something happened — a question about rows rather than about days.
 *  - **Removing a question never removes its answers.** A question that has been answered is retired
 *    from the form and keeps naming what it collected; only one nobody ever answered is deleted.
 *
 * Local to People, like the timeline notes and unlike the directory itself: none of this rides the
 * sync seam. What somebody's day was like is not a fact LifeOps or Health has asked for, and the
 * seam carries the household's *identity*, not its diary.
 */
class CheckInRepository(private val dao: CheckInDao) {

    private fun now() = System.currentTimeMillis()
    private fun newId() = UUID.randomUUID().toString()

    // --- the form ---

    /** Every question, retired ones last — what the form editor lists. */
    fun observeFields(personId: String): Flow<List<CheckInField>> =
        dao.observeFields(personId).map { rows -> rows.map { it.toModel() } }

    /** Just the questions being asked — what the day being filled in shows. */
    fun observeForm(personId: String): Flow<List<CheckInField>> =
        observeFields(personId).map { fields -> fields.filterNot { it.retired } }

    suspend fun addField(
        personId: String,
        label: String,
        kind: CheckInKind,
        options: List<String> = emptyList()
    ): String {
        val id = newId()
        dao.upsertField(
            CheckInFieldEntity(
                id = id,
                personId = personId,
                label = label.trim(),
                kind = kind.key,
                options = CheckIns.encodeOptions(options),
                position = dao.nextPosition(personId),
                retired = false,
                createdAt = now()
            )
        )
        return id
    }

    /** The whole starter form in one go, in the order it is written. */
    suspend fun addStarterForm(personId: String) {
        CheckIns.STARTER_FORM.forEach { (label, kind, options) ->
            addField(personId, label, kind, options)
        }
    }

    /**
     * Edit a question.
     *
     * The label and the options are editable for ever — fixing a typo should fix it everywhere the
     * question has ever been asked, which is what re-reading the label live rather than copying it
     * onto each answer buys.
     *
     * The **kind** is not, once anything has answered it. Retyping a question does not retype its
     * history: three months of "Lunch" as text would still be text, and reading them back as a
     * 1-to-5 scale would render them as nonsense or hide them. [canRetype] is the same question
     * asked in advance, so the editor can show the selector as fixed rather than silently dropping
     * the change.
     */
    suspend fun updateField(
        id: String,
        label: String,
        kind: CheckInKind,
        options: List<String> = emptyList()
    ) {
        val existing = dao.field(id) ?: return
        val keepKind = !canRetype(id)
        dao.upsertField(
            existing.copy(
                label = label.trim(),
                kind = if (keepKind) existing.kind else kind.key,
                options = CheckIns.encodeOptions(options)
            )
        )
    }

    /** Whether this question can still change kind — true until something has answered it. */
    suspend fun canRetype(id: String): Boolean = dao.answerCount(id) == 0

    /**
     * Take a question off the form.
     *
     * Answered ones are retired and keep their answers; an unanswered one is deleted outright, since
     * there is nothing to orphan and a form littered with mistakes nobody can clear is its own kind
     * of broken. Returns true when the row was deleted rather than retired, so the screen can say
     * which happened.
     */
    suspend fun removeField(id: String): Boolean {
        val field = dao.field(id) ?: return false
        if (dao.answerCount(id) == 0) {
            dao.deleteField(id)
            return true
        }
        dao.upsertField(field.copy(retired = true))
        return false
    }

    /** Put a retired question back on the form, at the end of it. */
    suspend fun restoreField(id: String) {
        val field = dao.field(id) ?: return
        dao.upsertField(field.copy(retired = false, position = dao.nextPosition(field.personId)))
    }

    /**
     * Move a question one place up or down the form.
     *
     * Positions are rewritten for the whole form rather than swapped in place: rows that arrived
     * from a restore, or from a delete that left a gap, can share or skip positions, and a swap
     * between two rows holding the same number is a no-op that looks like a bug to the person
     * pressing the arrow.
     */
    suspend fun moveField(id: String, up: Boolean) {
        val moving = dao.field(id) ?: return
        val form = dao.fields(moving.personId).filterNot { it.retired }.sortedBy { it.position }
        val index = form.indexOfFirst { it.id == id }
        val target = index + if (up) -1 else 1
        if (index < 0 || target !in form.indices) return

        val reordered = form.toMutableList().apply { add(target, removeAt(index)) }
        dao.upsertFields(reordered.mapIndexed { position, field -> field.copy(position = position) })
    }

    // --- the days ---

    /**
     * One day, with its answers resolved against the form.
     *
     * Retired questions are included when they answered *this* day: the point of keeping them is
     * that a day already recorded still reads as it was recorded.
     */
    fun observeCheckIn(personId: String, day: LocalDate): Flow<CheckIn?> =
        combine(
            dao.observeDay(personId, day.toString()),
            dao.observeAnswersOn(personId, day.toString()),
            dao.observeFields(personId)
        ) { entry, answers, fields ->
            entry?.toModel(answers, fields)
        }

    /** The last [limit] days recorded, newest first. */
    fun observeRecent(personId: String, limit: Int = 14): Flow<List<CheckIn>> =
        combine(
            dao.observeRecent(personId, limit),
            dao.observeRecentAnswers(personId, limit),
            dao.observeFields(personId)
        ) { entries, answers, fields ->
            val byCheckIn = answers.groupBy { it.checkInId }
            entries.map { entry -> entry.toModel(byCheckIn[entry.id].orEmpty(), fields) }
        }

    /** Every day this person has recorded — what the streak is counted from. */
    fun observeDays(personId: String): Flow<List<LocalDate>> =
        dao.observeDays(personId).map { days -> days.mapNotNull { CheckIns.parseDay(it) } }

    /** Whose check-in is still outstanding on [day], for the roster's nudge. */
    fun observeOutstanding(day: LocalDate): Flow<Set<String>> =
        dao.observeOutstanding(day.toString()).map { it.toSet() }

    /**
     * Record a day.
     *
     * [values] is what the controls hold, keyed by question; each is cleaned against its own kind,
     * and anything blank or no longer valid for its question is left out rather than stored as an
     * empty string (see [CheckIns.clean]).
     *
     * Returns whether the day is now recorded. Saving a form with nothing in it records nothing, and
     * clearing every answer on a day already recorded removes that day: a check-in is a thing that
     * was said, and an empty one is not a quieter version of it.
     */
    suspend fun save(personId: String, day: LocalDate, values: Map<String, String?>): Boolean {
        val fields = dao.fields(personId).associateBy { it.id }
        val answers = values.mapNotNull { (fieldId, raw) ->
            val field = fields[fieldId] ?: return@mapNotNull null
            val cleaned = CheckIns.clean(
                kind = CheckInKind.fromKey(field.kind),
                raw = raw,
                options = CheckIns.decodeOptions(field.options)
            ) ?: return@mapNotNull null
            fieldId to cleaned
        }

        val existing = dao.day(personId, day.toString())
        if (answers.isEmpty()) {
            existing?.let { dao.deleteCheckIn(it.id) }
            return false
        }

        val at = now()
        val entry = existing?.copy(updatedAt = at) ?: CheckInEntity(
            id = newId(),
            personId = personId,
            day = day.toString(),
            createdAt = at,
            updatedAt = at
        )
        dao.upsertCheckIn(entry)
        // Cleared wholesale rather than merged: the answers arriving are the whole day, so a question
        // emptied on this pass has to lose its row instead of keeping yesterday's value for ever.
        dao.clearAnswers(entry.id)
        dao.upsertAnswers(answers.map { (fieldId, value) -> CheckInAnswerEntity(entry.id, fieldId, value) })
        return true
    }

    // --- mapping ---

    private fun CheckInEntity.toModel(
        answers: List<CheckInAnswerEntity>,
        fields: List<CheckInFieldEntity>
    ): CheckIn {
        val byId = fields.associateBy { it.id }
        return CheckIn(
            id = id,
            personId = personId,
            day = CheckIns.parseDay(day) ?: LocalDate.EPOCH,
            answers = answers
                .mapNotNull { answer ->
                    val field = byId[answer.fieldId] ?: return@mapNotNull null
                    field.position to CheckInAnswer(
                        fieldId = answer.fieldId,
                        label = field.label,
                        kind = CheckInKind.fromKey(field.kind),
                        value = answer.value
                    )
                }
                .sortedBy { it.first }
                .map { it.second },
            updatedAt = updatedAt
        )
    }

    private fun CheckInFieldEntity.toModel() = CheckInField(
        id = id,
        personId = personId,
        label = label,
        kind = CheckInKind.fromKey(kind),
        options = CheckIns.decodeOptions(options),
        position = position,
        retired = retired
    )
}
