package com.lifeops.app.connection.service

import com.lifeops.app.data.repository.CostResourceRepository

/**
 * Use-case layer for cost resources (budgets/quotas) and the per-task cost entries logged against
 * them.
 */
class CostService(private val costResourceRepository: CostResourceRepository) {

    suspend fun createResource(name: String, resetCycle: String = "monthly", capacity: Int? = null) {
        require(name.isNotBlank()) { "Resource name must not be blank" }
        costResourceRepository.addResource(name.trim(), resetCycle, capacity)
    }

    suspend fun setResourceActive(id: String, active: Boolean) =
        costResourceRepository.setActive(id, active)

    suspend fun logCost(taskId: String, resourceId: String, amount: Int, note: String? = null) =
        costResourceRepository.logCost(taskId, resourceId, amount, note)

    suspend fun deleteEntry(id: String) = costResourceRepository.deleteCostEntry(id)
}
