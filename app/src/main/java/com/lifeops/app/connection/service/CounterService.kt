package com.lifeops.app.connection.service

import com.lifeops.app.data.model.Counter
import com.lifeops.app.data.repository.CounterRepository
import java.util.UUID

/**
 * Use-case layer for counters/habits. Wraps id generation, event logging, and archive/edit
 * transitions (each of which re-syncs the habit reminder inside the repository) behind a stable
 * surface for the connection routes.
 */
class CounterService(private val counterRepository: CounterRepository) {

    suspend fun create(
        name: String,
        categoryId: String? = null,
        isHabit: Boolean = false,
        reminderHour: Int? = null
    ): Counter {
        require(name.isNotBlank()) { "Counter name must not be blank" }
        return counterRepository.createCounter(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            categoryId = categoryId,
            isHabit = isHabit,
            reminderHour = reminderHour
        )
    }

    /**
     * Log a tick on [counterId]. [delta] is the weight (default 1). [occurredAt] (epoch millis)
     * defaults to now but may backdate the event. False if [counterId] is unknown.
     */
    suspend fun log(
        counterId: String,
        delta: Int = 1,
        note: String? = null,
        occurredAt: Long? = null
    ): Boolean {
        counterRepository.getById(counterId) ?: return false
        counterRepository.logEvent(
            counterId = counterId,
            occurredAt = occurredAt ?: System.currentTimeMillis(),
            delta = delta,
            note = note
        )
        return true
    }

    suspend fun setArchived(counterId: String, archived: Boolean): Boolean {
        val counter = counterRepository.getById(counterId) ?: return false
        counterRepository.setArchived(counter, archived)
        return true
    }

    /** Partial update; each non-null field replaces the current value. Null if [counterId] unknown. */
    suspend fun update(
        counterId: String,
        name: String? = null,
        categoryId: String? = null,
        isHabit: Boolean? = null,
        reminderHour: Int? = null
    ): Counter? {
        val current = counterRepository.getById(counterId) ?: return null
        val updated = current.copy(
            name = name?.trim()?.takeIf { it.isNotBlank() } ?: current.name,
            categoryId = categoryId ?: current.categoryId,
            isHabit = isHabit ?: current.isHabit,
            reminderHour = reminderHour ?: current.reminderHour
        )
        counterRepository.update(updated)
        return updated
    }
}
