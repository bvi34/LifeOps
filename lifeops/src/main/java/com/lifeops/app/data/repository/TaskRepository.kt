package com.lifeops.app.data.repository

import androidx.room.withTransaction
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.lifeops.app.connection.TaskCompletionBus
import com.lifeops.app.data.db.LifeOpsDatabase
import com.lifeops.app.data.db.dao.*
import com.lifeops.app.data.db.entities.WeekSnapshotEntity
import com.lifeops.app.data.model.*
import com.lifeops.app.data.model.TaskSource
import com.lifeops.app.util.*
import com.lifeops.app.util.ImportParser
import com.lifeops.app.util.ScoringUtils
import java.time.LocalDate
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// Safety bound on a single catch-up pass (~20 years). The loop terminates naturally after
// (currentWeek - openWeek) iterations; this only guards against corrupt/duplicate week rows.
private const val MAX_CATCHUP_WEEKS = 1040

/**
 * Reading's contribution to a week: [minutes] engaged reading summed across every session in the
 * week window, [points] those minutes mint (floored once over the total — cumulative, not
 * per-session), and the [aspectId] they fold into (`null` when reading rewards are off, in which
 * case [points] is zero). See [TaskRepository.expectedReadingReward].
 */
data class ReadingExpectation(val minutes: Int, val points: Int, val aspectId: String?)

class TaskRepository(
    private val db: LifeOpsDatabase,
    private val taskDao: TaskDao,
    private val aspectDao: AspectDao,
    private val categoryDao: CategoryDao,
    private val weekDao: WeekDao,
    private val weekSnapshotDao: WeekSnapshotDao,
    private val gameResourceDao: GameResourceDao,
    private val gameResourceMappingDao: GameResourceMappingDao,
    private val notificationDao: NotificationDao,
    private val notificationRepository: NotificationRepository,
    private val weekRepository: WeekRepository,
    private val counterRepository: CounterRepository,
    private val preferencesRepository: PreferencesRepository
) {
    private val gson = Gson()

    fun observeTasksForWeek(weekId: String): Flow<List<Task>> =
        taskDao.observeByWeek(weekId).map { list -> list.map { it.toModel() } }

    fun observeById(id: String): Flow<Task?> =
        taskDao.observeById(id).map { it?.toModel() }

    suspend fun completeTask(task: Task) {
        if (taskDao.getChildOf(task.id) != null) return
        var completion: TaskCompletionBus.Completion? = null
        db.withTransaction {
            val current = taskDao.getById(task.id) ?: return@withTransaction
            if (current.status == TaskStatus.COMPLETED.value) return@withTransaction // already done — no double tick
            val nowMillis = System.currentTimeMillis()
            taskDao.markCompleted(current.id, TaskStatus.COMPLETED.value, DateUtil.isoFromEpoch(nowMillis))
            // Same transaction as the status flip: if anything fails the tick rolls back too,
            // so a half-completed task can never leave a phantom CounterEvent.
            current.counterId?.let { counterRepository.logEvent(it, occurredAt = nowMillis) }
            completion = TaskCompletionBus.Completion(current.id, current.title, nowMillis)
        }
        // Announced *after* the commit, and only when the tick actually happened — a task that was
        // already complete, or whose transaction rolled back, tells nobody anything. Some tasks are
        // owned by another hosted app (Maintenance publishes its upkeep onto a future week); this is
        // how the tick gets back to whoever has to move a schedule because of it.
        completion?.let { TaskCompletionBus.announce(it) }
    }

    /**
     * The task this one was carried forward into, when a week rolled over without it being done —
     * a *new* row with a new id. Anything holding a task id across a week close (an app that
     * published the task in the first place) has to be able to follow that hop.
     */
    suspend fun childOf(taskId: String): Task? = taskDao.getChildOf(taskId)?.toModel()

    suspend fun skipTask(taskId: String) {
        taskDao.updateStatus(taskId, TaskStatus.SKIPPED.value)
    }

    suspend fun unsuccessTask(taskId: String) {
        taskDao.updateStatus(taskId, TaskStatus.UNSUCCESSFUL.value)
    }

    suspend fun unUnsuccessTask(taskId: String) {
        taskDao.updateStatus(taskId, TaskStatus.PENDING.value)
    }

    suspend fun carryForward(task: Task, reason: CarryForwardReason) {
        notificationRepository.cancelForTask(task.id)
        taskDao.updateStatusAndReason(task.id, TaskStatus.CARRIED_FORWARD.value, reason.value)
    }

    suspend fun unCarryForward(taskId: String) {
        val child = taskDao.getChildOf(taskId)
        if (child == null) taskDao.unCarryForward(taskId)
    }

    suspend fun upsertTask(task: Task) = taskDao.upsert(task.toEntity())

    suspend fun updateTask(task: Task) = taskDao.update(task.toEntity())

    suspend fun getById(id: String): Task? = taskDao.getById(id)?.toModel()

    /** Every task in [weekId] carrying [slug] — the rows behind the duplicate-title check. */
    suspend fun getBySlugInWeek(weekId: String, slug: String): List<Task> =
        taskDao.getBySlugInWeek(weekId, slug).map { it.toModel() }

    suspend fun getAllSince(since: String): List<Task> =
        taskDao.getAllSince(since).map { it.toModel() }

    suspend fun closeWeek(
        weekId: String,
        newWeekId: String,
        selfRating: Int? = null,
        selfRatingNote: String? = null,
        mentalReset: Boolean? = null,
        exhaustion: Int? = null
    ) {
        val week = weekDao.getById(weekId) ?: return
        if (week.isClosed) return
        val now = DateUtil.now()

        val pending = taskDao.getPendingByWeek(weekId)
        val carriedForward = taskDao.getCarriedForwardByWeek(weekId)

        val timeByTask = db.timeEntryDao().getByWeek(weekId)
            .groupBy { it.taskId }
            .mapValues { (_, entries) -> entries.sumOf { it.durationMinutes } }

        val newTasksToSchedule = mutableListOf<Task>()
        val newWeek = weekDao.getById(newWeekId)

        db.withTransaction {
            // Queued (future-dated) tasks ride into the new week before the snapshot is
            // built, then any whose due date now falls inside it become live.
            taskDao.moveQueuedToWeek(weekId, newWeekId)
            newWeek?.let { taskDao.activateQueuedDueBy(it.id, it.endDate) }
            pending.forEach { task ->
                val newStatus = if (task.hardDeadline) TaskStatus.EXPIRED.value else TaskStatus.INCOMPLETE.value
                taskDao.updateStatus(task.id, newStatus)
            }
            for (carried in carriedForward) {
                if (taskDao.getChildOf(carried.id) == null) {
                    // isCommitment rides along deliberately, unlike the recurring seed below: a
                    // task you called essential and didn't do has not stopped being essential
                    // because the week ended. Un-tick it next week if you've changed your mind —
                    // that's a decision, and it should have to be made rather than defaulted.
                    val newTask = carried.copy(
                        id = java.util.UUID.randomUUID().toString(),
                        weekId = newWeekId,
                        status = TaskStatus.PENDING.value,
                        carriedFromTaskId = carried.id,
                        carriedCount = carried.carriedCount + 1,
                        completedAt = null,
                        sortOrder = 0,
                        createdAt = now
                    )
                    taskDao.upsert(newTask)
                    // Phase 5: carry subtasks with their checked state into the new week
                    val parentSubtasks = db.subtaskDao().getByTask(carried.id)
                    if (parentSubtasks.isNotEmpty()) {
                        db.subtaskDao().insertAll(
                            parentSubtasks.map { it.copy(
                                id = java.util.UUID.randomUUID().toString(),
                                taskId = newTask.id
                            )}
                        )
                    }
                    newTasksToSchedule.add(newTask.toModel())
                }
            }
            val allTasks = taskDao.getAllByWeek(weekId).map { it.toModel() }
            val aspectMeta = aspectDao.getAllSync().associate { it.id to (it.name to it.color) }
            // Phase 9: count checked subtasks at close time (flat +1 each, no modifier)
            val allTaskIds = allTasks.map { it.id }
            val subtaskTickCount = if (allTaskIds.isNotEmpty())
                db.subtaskDao().getByTasks(allTaskIds).count { it.isChecked }
            else 0
            // Reading rewards: engaged reading minutes logged in this week's window earn resource
            // points into the user-chosen reading aspect (both Learning and Fun fold into it), which
            // then flow through the normal aspect→resource mapping. Off unless an aspect is chosen.
            val reading = readingRewardFor(week.startDate, week.endDate)
            val readingAspectId = reading.aspectId
            val readingPoints = reading.points
            val snapshot = buildSnapshot(
                weekId, allTasks, timeByTask, now, aspectMeta, selfRating, selfRatingNote,
                mentalReset, exhaustion, subtaskTickCount, readingAspectId, readingPoints
            )
            weekSnapshotDao.insert(snapshot)
            weekDao.update(week.copy(isClosed = true, closedAt = now))
            applySnapshotToGameResources(snapshot)
        }

        for (task in newTasksToSchedule) notificationRepository.scheduleForTask(task)
        notificationRepository.scheduleWeekCloseReminder()
    }

    /**
     * The reading reward for a week window, computed exactly as [closeWeek] mints it: every engaged
     * reading session logged between [startDate] and [endDate] is **summed first**, then turned into
     * points once at the user's rate. So six ten-minute sittings earn the same as one unbroken hour,
     * and no per-session remainder is floored away — reading is cumulative over the week, never a
     * single-session gate. Points are zero unless a reading aspect is chosen (rewards off); minutes
     * are reported regardless. The single source of truth for both the mint and its pre-close preview.
     */
    private suspend fun readingRewardFor(startDate: String, endDate: String): ReadingExpectation {
        val minutes = db.bookDao().sumReadingMinutesBetween(startDate, endDate)
        val aspectId = preferencesRepository.readingAspectId
        val points = if (aspectId != null)
            ReadingRewards.points(minutes, preferencesRepository.readingPointsPerHour) else 0
        return ReadingExpectation(minutes = minutes, points = points, aspectId = aspectId)
    }

    /**
     * The reading reward the still-open [weekId] has already accrued but not yet minted — what
     * closing will add for reading, so the cumulative total is visible before the week-close ritual.
     * Returns zeros for an unknown week. Mirrors [closeWeek]'s figure precisely via [readingRewardFor].
     */
    suspend fun expectedReadingReward(weekId: String): ReadingExpectation {
        val week = weekDao.getById(weekId) ?: return ReadingExpectation(0, 0, null)
        return readingRewardFor(week.startDate, week.endDate)
    }

    /**
     * Close every whole week that has elapsed but is still open, oldest-first, exactly as a
     * manual week-close would (createNextWeek + closeWeek + seedRecurringTasks per week). This
     * is what "kills manual date entry": opening the app after a gap auto-seals the missed
     * weeks instead of making the user close them one by one.
     *
     * Strictly closes weeks whose index < the current week's — the in-progress week is never
     * closed. Idempotent: when the open week is already the current week, it does nothing.
     * Touches no counter rows; week-close doesn't reference counters at all. Returns the
     * highest week index it closed, or null if already caught up.
     */
    suspend fun catchUpClose(now: Long = System.currentTimeMillis()): Int? {
        val currentIndex = DateUtil.weekIndexFor(now)
        var lastClosed: Int? = null
        repeat(MAX_CATCHUP_WEEKS) {
            val open = weekRepository.getCurrentWeek() ?: return lastClosed
            val openIndex = DateUtil.weekIndexFor(LocalDate.parse(open.startDate))
            if (openIndex >= currentIndex) return lastClosed   // in-progress week — leave it open
            val next = weekRepository.createNextWeek(open)
            closeWeek(open.id, next.id)
            seedRecurringTasks(open.id, next.id)
            lastClosed = openIndex
        }
        return lastClosed
    }

    suspend fun getLineageIds(taskId: String): List<String> {
        val upChain = mutableListOf<String>()
        var currentId: String? = taskId
        val visited = mutableSetOf<String>()
        while (currentId != null && currentId !in visited) {
            visited.add(currentId)
            val task = taskDao.getById(currentId) ?: break
            upChain.add(0, task.id)
            currentId = task.carriedFromTaskId
        }
        val downChain = mutableListOf<String>()
        val visitedDown = visited.toMutableSet()
        var child = taskDao.getChildOf(taskId)
        while (child != null && child.id !in visitedDown) {
            visitedDown.add(child.id)
            downChain.add(child.id)
            child = taskDao.getChildOf(child.id)
        }
        return upChain + downChain
    }

    suspend fun repairSameWeekCarries() {
        val allTasks = taskDao.getAll()
        val taskById = allTasks.associateBy { it.id }
        val badChildren = allTasks.filter { task ->
            val parent = task.carriedFromTaskId?.let { taskById[it] }
            parent != null && parent.weekId == task.weekId
        }
        if (badChildren.isEmpty()) return
        db.withTransaction {
            for (child in badChildren) {
                val parent = taskById[child.carriedFromTaskId] ?: continue
                val parentMinutes = db.timeEntryDao().getByTask(parent.id).sumOf { it.durationMinutes }
                val parentNotes = db.taskNoteDao().getByTask(parent.id)
                if (parentMinutes == 0 && parentNotes.isEmpty()) {
                    db.timeEntryDao().reassignToTask(parent.id, child.id)
                    db.taskNoteDao().reassignToTask(parent.id, child.id)
                    db.taskCostEntryDao().reassignToTask(parent.id, child.id)
                    taskDao.update(child.copy(carriedFromTaskId = parent.carriedFromTaskId))
                    taskDao.delete(parent.id)
                } else {
                    taskDao.updateStatus(parent.id, TaskStatus.CARRIED_FORWARD.value)
                }
            }
        }
    }

    suspend fun promoteTaskToOperation(taskId: String, operationId: String) {
        val lineageIds = getLineageIds(taskId)
        db.withTransaction {
            for (id in lineageIds) {
                val entity = taskDao.getById(id) ?: continue
                taskDao.update(entity.copy(operationId = operationId))
            }
        }
    }

    private fun earnedPoints(task: Task, actualMinutes: Int?, successFactor: Double = 1.0): Int =
        ScoringUtils.earnedResourceValue(task, actualMinutes, successFactor)

    private fun buildSnapshot(
        weekId: String,
        tasks: List<Task>,
        timeByTask: Map<String, Int>,
        now: String,
        aspectMeta: Map<String, Pair<String, String>>,
        selfRating: Int? = null,
        selfRatingNote: String? = null,
        mentalReset: Boolean? = null,
        exhaustion: Int? = null,
        subtaskTickCount: Int = 0,
        readingAspectId: String? = null,
        readingPoints: Int = 0
    ): WeekSnapshotEntity {
        // Phase 10: scoring keys off task-level completion only. Subtask checked state
        // contributes to subtaskTickCount but never modifies resource point calculations.
        // A task closed at 6/12 subtasks earns identical run params to one closed at 12/12.
        val aspectBreakdown = mutableMapOf<String, Int>()
        val categoryBreakdown = mutableMapOf<String, Int>()
        val categorySlipBreakdown = mutableMapOf<String, Int>()
        val categoryTotalBreakdown = mutableMapOf<String, Int>()

        tasks.forEach { task ->
            task.categoryId?.let { catId ->
                categoryTotalBreakdown[catId] = (categoryTotalBreakdown[catId] ?: 0) + 1
            }
            when (task.status) {
                TaskStatus.COMPLETED -> {
                    val earned = earnedPoints(task, timeByTask[task.id])
                    task.aspectId?.let { aspectBreakdown[it] = (aspectBreakdown[it] ?: 0) + earned }
                    task.categoryId?.let { categoryBreakdown[it] = (categoryBreakdown[it] ?: 0) + earned }
                }
                TaskStatus.UNSUCCESSFUL -> {
                    val earned = earnedPoints(task, timeByTask[task.id], successFactor = 0.5)
                    task.aspectId?.let { aspectBreakdown[it] = (aspectBreakdown[it] ?: 0) + earned }
                    task.categoryId?.let { categoryBreakdown[it] = (categoryBreakdown[it] ?: 0) + earned }
                }
                TaskStatus.INCOMPLETE, TaskStatus.EXPIRED -> {
                    task.categoryId?.let { categorySlipBreakdown[it] = (categorySlipBreakdown[it] ?: 0) + 1 }
                }
                else -> Unit
            }
        }

        // Reading rewards fold into the chosen aspect's earnings (see closeWeek). Kept out of the
        // per-task loop and the Growth-ring minutes below, so reading earns resources without
        // rewriting task history or the ring geometry.
        if (readingAspectId != null && readingPoints > 0) {
            aspectBreakdown[readingAspectId] = (aspectBreakdown[readingAspectId] ?: 0) + readingPoints
        }

        // Growth rings: minutes per aspect = ALL logged time that week (effort is effort,
        // regardless of whether the task was completed). Seal the aspect's current name +
        // colour so the ring stays faithful if the aspect is later deleted/renamed/recoloured.
        val aspectMinutes = mutableMapOf<String, Int>()
        tasks.forEach { task ->
            val aspectId = task.aspectId ?: return@forEach
            val mins = timeByTask[task.id] ?: 0
            if (mins > 0) aspectMinutes[aspectId] = (aspectMinutes[aspectId] ?: 0) + mins
        }
        val aspectHistory = aspectMinutes.mapValues { (id, mins) ->
            val meta = aspectMeta[id]
            AspectHistoryEntry(mins, meta?.first ?: id, meta?.second ?: GrowthRings.SCAR_COLOR)
        }

        val commitmentTasks = tasks.filter {
            it.isCommitment &&
                it.status != TaskStatus.CARRIED_FORWARD &&
                it.status != TaskStatus.QUEUED
        }

        val hdCompleted = tasks.count { it.hardDeadline && it.status == TaskStatus.COMPLETED }
        val hdExpired = tasks.count { it.hardDeadline && it.status == TaskStatus.EXPIRED }

        val totalEarned = tasks.sumOf { task ->
            when (task.status) {
                TaskStatus.COMPLETED -> earnedPoints(task, timeByTask[task.id])
                TaskStatus.UNSUCCESSFUL -> earnedPoints(task, timeByTask[task.id], successFactor = 0.5)
                else -> 0
            }
        } + readingPoints

        return WeekSnapshotEntity(
            id = java.util.UUID.randomUUID().toString(),
            weekId = weekId,
            completedCount = tasks.count { it.status == TaskStatus.COMPLETED },
            incompleteCount = tasks.count { it.status == TaskStatus.INCOMPLETE },
            expiredCount = tasks.count { it.status == TaskStatus.EXPIRED },
            skippedCount = tasks.count { it.status == TaskStatus.SKIPPED },
            carriedForwardCount = tasks.count { it.status == TaskStatus.CARRIED_FORWARD },
            unsuccessfulCount = tasks.count { it.status == TaskStatus.UNSUCCESSFUL },
            totalResourcesEarned = totalEarned,
            aspectBreakdown = gson.toJson(aspectBreakdown),
            categoryBreakdown = gson.toJson(categoryBreakdown),
            categorySlipBreakdown = gson.toJson(categorySlipBreakdown),
            categoryTotalBreakdown = gson.toJson(categoryTotalBreakdown),
            hardDeadlineCompletedCount = hdCompleted,
            hardDeadlineExpiredCount = hdExpired,
            createdAt = now,
            aspectHistory = gson.toJson(aspectHistory),
            selfRating = selfRating,
            selfRatingNote = selfRatingNote?.takeIf { it.isNotBlank() },
            mentalReset = mentalReset,
            exhaustion = exhaustion,
            subtaskTickCount = subtaskTickCount,
            // The week's bar, sealed. Carried-forward tasks are excluded from the denominator for
            // the same reason they're excluded from the completion rate: they were explicitly moved
            // to next week, not failed this one. A commitment left pending simply wasn't met.
            commitmentTotal = commitmentTasks.size,
            commitmentCompleted = commitmentTasks.count { it.status == TaskStatus.COMPLETED }
        )
    }

    /**
     * One-time backfill of [WeekSnapshotEntity.aspectHistory] for weeks closed before that
     * column existed, so their Growth rings are deletion-safe (and identical to what a live
     * derivation would produce today). Idempotent — only fills snapshots that are still empty.
     */
    suspend fun backfillAspectHistory() {
        val snapshots = weekSnapshotDao.getAll()
        if (snapshots.isEmpty()) return
        val aspectMeta = aspectDao.getAllSync().associate { it.id to (it.name to it.color) }
        db.withTransaction {
            for (snap in snapshots) {
                if (snap.aspectHistory.isNotBlank() && snap.aspectHistory != "{}") continue
                val timeByTask = db.timeEntryDao().getByWeek(snap.weekId)
                    .groupBy { it.taskId }
                    .mapValues { (_, entries) -> entries.sumOf { it.durationMinutes } }
                val aspectMinutes = mutableMapOf<String, Int>()
                taskDao.getAllByWeek(snap.weekId).forEach { task ->
                    val aspectId = task.aspectId ?: return@forEach
                    val mins = timeByTask[task.id] ?: 0
                    if (mins > 0) aspectMinutes[aspectId] = (aspectMinutes[aspectId] ?: 0) + mins
                }
                if (aspectMinutes.isEmpty()) continue
                val history = aspectMinutes.mapValues { (id, mins) ->
                    val meta = aspectMeta[id]
                    AspectHistoryEntry(mins, meta?.first ?: id, meta?.second ?: GrowthRings.SCAR_COLOR)
                }
                weekSnapshotDao.insert(snap.copy(aspectHistory = gson.toJson(history)))
            }
        }
    }

    // Must be called from within a database transaction.
    private suspend fun applySnapshotToGameResources(snapshot: WeekSnapshotEntity) {
        val type = object : TypeToken<Map<String, Int>>() {}.type
        val aspectBreakdown: Map<String, Int> = gson.fromJson(snapshot.aspectBreakdown, type) ?: emptyMap()
        aspectBreakdown.forEach { (aspectId, earned) ->
            val mappings = gameResourceMappingDao.getByAspect(aspectId)
            mappings.forEach { mapping ->
                val amount = (earned * mapping.weight).roundToInt()
                if (amount > 0) gameResourceDao.addValue(mapping.gameResourceId, amount)
            }
        }
    }

    suspend fun unCompleteTask(taskId: String) = taskDao.unmarkCompleted(taskId)

    suspend fun clearCategoryFromTasks(categoryId: String) = taskDao.nullifyCategoryId(categoryId)

    suspend fun unSkipTask(taskId: String) = taskDao.unSkipTask(taskId)

    /**
     * Mark (or unmark) a task as part of the week's commitment — the subset the week is judged on.
     * A targeted column write rather than a full upsert, so it can't disturb scoring, status or the
     * reminder: the flag deliberately changes nothing but what the week reads back as.
     */
    suspend fun setCommitment(taskId: String, isCommitment: Boolean) =
        taskDao.setCommitment(taskId, isCommitment)

    suspend fun updateTaskSortOrder(taskId: String, order: Int) = taskDao.updateSortOrder(taskId, order)

    suspend fun createTaskWithNotes(
        weekId: String,
        title: String,
        notes: List<String>,
        source: TaskSource,
        aspectId: String? = null,
        categoryId: String? = null
    ) {
        val now = DateUtil.now()
        val task = Task(
            id = java.util.UUID.randomUUID().toString(),
            weekId = weekId,
            title = title,
            aspectId = aspectId,
            categoryId = categoryId,
            priority = Priority.MEDIUM,
            status = TaskStatus.PENDING,
            resourceValue = ImportParser.computeResourceValue("medium", false, null, isManuallyAdded = true),
            createdAt = now,
            source = source
        )
        db.withTransaction {
            taskDao.upsert(task.toEntity())
            for (note in notes) {
                db.taskNoteDao().insert(
                    com.lifeops.app.data.db.entities.TaskNoteEntity(
                        id = java.util.UUID.randomUUID().toString(),
                        taskId = task.id,
                        content = note,
                        createdAt = now
                    )
                )
            }
        }
        notificationRepository.scheduleForTask(task)
    }

    suspend fun getTasksWithCarryHistory(): List<Task> =
        taskDao.getTasksWithCarryHistory().map { it.toModel() }

    /**
     * Seed the target week with any recurring series that fall due in it. [fromWeekId] is retained
     * for call-site compatibility but no longer bounds the lookup: each series is anchored on its
     * most recent instance across all weeks, so bi-weekly/monthly series survive their off-weeks
     * (when the immediately previous week holds no instance). Idempotent — a series already present
     * in the target week (matched by slug) is skipped, so re-running on every launch never dupes.
     */
    suspend fun seedRecurringTasks(fromWeekId: String, toWeekId: String) {
        val weeksById = weekRepository.getAllWeeksSync().associateBy { it.id }
        val toWeek = weeksById[toWeekId] ?: return
        val toStart = LocalDate.parse(toWeek.startDate)
        val toEnd = LocalDate.parse(toWeek.endDate)
        val toIndex = DateUtil.weekIndexFor(toStart)

        // Series already present in the target week — skip these (per-series idempotency, so a
        // due weekly series and a not-due monthly series in the same week don't block each other).
        // This looks at ALL non-queued instances, not just recurring ones: after the user turns
        // "Repeats" off on the current week's instance it is no longer recurring, and if we only
        // matched recurring instances the next launch would re-seed a fresh recurring copy right
        // back into the same week. Queued instances are excluded so a future-dated recurring task
        // doesn't suppress seeding a pending one.
        fun seriesKey(t: com.lifeops.app.data.db.entities.TaskEntity) =
            t.slug.ifBlank { t.title.toSlug() }
        val presentKeys = taskDao.getAllByWeek(toWeekId)
            .filter { it.status != TaskStatus.QUEUED.value }
            .map(::seriesKey).toSet()

        // Series identities that carry a recurring instance in at least one week.
        val recurringKeys = taskDao.getAllRecurring().map(::seriesKey).toSet()

        // Every prior instance (weeks starting strictly before the target) of each series that is
        // recurring in at least one week, grouped by series. Queued instances are excluded to
        // mirror the recurring-instance queries above. Note this deliberately gathers ALL such
        // instances — recurring or not — because Recurrence.activeAnchor anchors each series on its
        // most recent instance regardless of the flag: when the user un-checks "Repeats" on the
        // newest instance, that cleared instance ends the series even though older weeks still hold
        // recurring instances of it (the previous behaviour kept re-seeding those forever).
        val instancesPerSeries = taskDao.getAll()
            .filter { it.status != TaskStatus.QUEUED.value && seriesKey(it) in recurringKeys }
            .mapNotNull { t -> weeksById[t.weekId]?.let { w -> t to LocalDate.parse(w.startDate) } }
            .filter { (_, start) -> start.isBefore(toStart) }
            .groupBy { (t, _) -> seriesKey(t) }

        val now = DateUtil.now()
        db.withTransaction {
            for ((key, instances) in instancesPerSeries) {
                if (key in presentKeys) continue
                // Anchor on the most recent instance; null means the series was turned off.
                val anchor = Recurrence.activeAnchor(
                    instances,
                    weekIndexOf = { (_, start) -> DateUtil.weekIndexFor(start) },
                    isRecurringOf = { (entity, _) -> entity.isRecurring }
                ) ?: continue
                val (entity, lastStart) = anchor
                val due = entity.recurrenceDayOfMonth?.let { day ->
                    Recurrence.weekContainsMonthlyDay(toStart, toEnd, day)
                } ?: Recurrence.isWeeklyIntervalDue(
                    DateUtil.weekIndexFor(lastStart), toIndex, entity.recurrenceIntervalWeeks
                )
                if (!due) continue
                val newTask = entity.copy(
                    id = java.util.UUID.randomUUID().toString(),
                    weekId = toWeekId,
                    status = TaskStatus.PENDING.value,
                    completedAt = null,
                    carriedFromTaskId = null,
                    carriedCount = 0,
                    sortOrder = 0,
                    createdAt = now,
                    // A new week re-decides its own bar. Marking one instance of a recurring chore
                    // essential says something about that week, not about every week the series
                    // will ever land in — inheriting it would quietly re-mark the same tasks
                    // forever until the bar covered the whole list and meant nothing.
                    isCommitment = false
                )
                taskDao.upsert(newTask)
            }
        }
    }

    fun observeQueuedTasks(): Flow<List<Task>> =
        taskDao.observeQueued().map { list -> list.map { it.toModel() } }

    /** Flip queued tasks whose due date falls within [week] to pending (idempotent). */
    suspend fun activateDueQueuedTasks(week: Week) =
        taskDao.activateQueuedDueBy(week.id, week.endDate)

    /** Pull a queued task into the current week ahead of its due date. */
    suspend fun activateQueuedTaskNow(taskId: String) {
        val task = taskDao.getById(taskId) ?: return
        if (task.status != TaskStatus.QUEUED.value) return
        val week = weekRepository.getOrCreateCurrentWeek()
        taskDao.update(task.copy(weekId = week.id, status = TaskStatus.PENDING.value))
    }

    suspend fun deleteTask(taskId: String) {
        notificationRepository.cancelForTask(taskId)
        taskDao.delete(taskId)
    }

    suspend fun getSlugsByWeek(weekId: String): Set<String> =
        taskDao.getSlugsByWeek(weekId).toSet()

    suspend fun getAllTasks(): List<Task> = taskDao.getAll().map { it.toModel() }

    suspend fun getEarnedThisWeekByAspect(weekId: String): Map<String, Int> {
        val tasks = taskDao.getAllByWeek(weekId).map { it.toModel() }
        val timeByTask = db.timeEntryDao().getByWeek(weekId)
            .groupBy { it.taskId }
            .mapValues { (_, entries) -> entries.sumOf { it.durationMinutes } }
        val result = mutableMapOf<String, Int>()
        tasks.filter { it.status == TaskStatus.COMPLETED }.forEach { task ->
            val earned = earnedPoints(task, timeByTask[task.id])
            task.aspectId?.let { result[it] = (result[it] ?: 0) + earned }
        }
        return result
    }
}
