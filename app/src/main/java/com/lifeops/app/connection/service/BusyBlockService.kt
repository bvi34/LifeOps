package com.lifeops.app.connection.service

import com.lifeops.app.data.model.BusyBlock
import com.lifeops.app.data.repository.BusyBlockRepository
import com.lifeops.app.util.DateUtil
import java.util.UUID

/**
 * Use-case layer for busy blocks (the user's or a person's schedule). A weekly-recurring block
 * carries a [daysMask] (bit 0 = Monday … bit 6 = Sunday); a one-off carries a [specificDate].
 */
class BusyBlockService(private val busyBlockRepository: BusyBlockRepository) {

    suspend fun create(
        title: String,
        startMinutes: Int,
        endMinutes: Int,
        daysMask: Int = 0,
        specificDate: String? = null,
        personId: String? = null
    ): BusyBlock {
        require(title.isNotBlank()) { "Busy block title must not be blank" }
        require(endMinutes > startMinutes) { "endMinutes must be after startMinutes" }
        val block = BusyBlock(
            id = UUID.randomUUID().toString(),
            title = title.trim(),
            startMinutes = startMinutes,
            endMinutes = endMinutes,
            daysMask = daysMask,
            specificDate = specificDate,
            personId = personId,
            createdAt = DateUtil.now()
        )
        busyBlockRepository.upsert(block)
        return block
    }

    suspend fun delete(id: String) = busyBlockRepository.delete(id)
}
