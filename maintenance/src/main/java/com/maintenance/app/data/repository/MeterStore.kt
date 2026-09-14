package com.maintenance.app.data.repository

import com.maintenance.app.data.db.dao.MaintenanceDao
import com.maintenance.app.data.db.entities.MeterReadingEntity

/**
 * Meter readings — the odometer, the hour counter — that upkeep intervals are measured in.
 */
class MeterStore(
    private val dao: MaintenanceDao
) {

    /**
     * File a reading — and satisfy any prompt that was asking for one.
     *
     * Returns the LifeOps tasks those prompts had published, for the caller to tick off. That is the
     * one place in this app where the seam runs the other way: everywhere else LifeOps announces a
     * completion and Maintenance reacts, but a task cannot carry a number, so *typing the number
     * here* is what completes the task over there. The nudge is the task; the reading is the work.
     */
    suspend fun addReading(assetId: String, value: Long, readAt: Long = now()): List<String> {
        dao.insertReading(
            MeterReadingEntity(
                id = newId(),
                assetId = assetId,
                readAt = readAt,
                value = value,
                source = MaintenanceRepository.READING_MANUAL
            )
        )

        val satisfied = dao.meterPromptsOf(assetId)
        satisfied.forEach { prompt ->
            dao.upsertPlan(prompt.copy(lastDoneAt = readAt, lastDoneMeter = value, updatedAt = now()))
        }
        return satisfied.mapNotNull { it.lifeOpsTaskId }
    }

    suspend fun deleteReading(readingId: String) = dao.deleteReading(readingId)
}
