package com.citation.core.rr

/**
 * The **cross-lane priority queue** that arbitrates every Royal Road body fetch.
 *
 * There are four lanes, in strict priority order — **the active buffer always wins**:
 *  1. [FetchLane.ACTIVE_BUFFER] — keep the story you're reading *right now* ahead of your page.
 *  2. [FetchLane.FINISH_CURRENT] — complete the story you're actively in.
 *  3. [FetchLane.FAVORITES_NEW] — new chapters of favourites the feed just flagged.
 *  4. [FetchLane.FAVORITES_BACKFILL] — the slow, indefinite backfill of favourites.
 *
 * Higher-priority lanes fully preempt lower ones, so a backfill can never delay the page you're
 * about to turn to. Within a lane, tasks are first-in-first-out. The queue is pure ordering; the
 * fetch loop pairs [poll] with a [RateBudget] to decide *when* the next task may actually run.
 */
class FetchQueue {

    private val tasks = ArrayList<FetchTask>()
    private var seq = 0L

    /** Enqueue a task unless an identical (lane, fiction, ordinal) task is already queued. */
    @Synchronized
    fun enqueue(task: FetchTask) {
        if (tasks.any { it.lane == task.lane && it.fictionId == task.fictionId && it.ordinal == task.ordinal }) {
            return
        }
        tasks.add(task.copy(seq = seq++))
    }

    /** The highest-priority task without removing it, or `null` if empty. */
    @Synchronized
    fun peek(): FetchTask? = tasks.minWithOrNull(ORDER)

    /** Remove and return the highest-priority task, or `null` if empty. */
    @Synchronized
    fun poll(): FetchTask? {
        val next = tasks.minWithOrNull(ORDER) ?: return null
        tasks.remove(next)
        return next
    }

    /** Drop every queued task for a fiction (e.g. when it's evicted or un-favourited). */
    @Synchronized
    fun removeFiction(fictionId: Long) {
        tasks.removeAll { it.fictionId == fictionId }
    }

    /** Drop a whole lane (e.g. clear the active buffer when you close a story). */
    @Synchronized
    fun removeLane(lane: FetchLane) {
        tasks.removeAll { it.lane == lane }
    }

    @Synchronized
    fun size(): Int = tasks.size

    private companion object {
        // Lane priority first (enum ordinal — ACTIVE_BUFFER is 0, so it sorts first), then FIFO.
        val ORDER: Comparator<FetchTask> =
            compareBy<FetchTask> { it.lane.ordinal }.thenBy { it.seq }
    }
}

/** Fetch lanes in strict descending priority (declaration order == priority). */
enum class FetchLane {
    ACTIVE_BUFFER,
    FINISH_CURRENT,
    FAVORITES_NEW,
    FAVORITES_BACKFILL
}

/**
 * A queued body fetch.
 * @property seq internal FIFO sequence within a lane (assigned on enqueue; ignore when constructing).
 */
data class FetchTask(
    val lane: FetchLane,
    val fictionId: Long,
    val ordinal: Int,
    val chapterId: Long? = null,
    val seq: Long = 0
)
