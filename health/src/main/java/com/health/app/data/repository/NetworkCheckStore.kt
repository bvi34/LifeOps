package com.health.app.data.repository

import com.health.app.data.db.dao.HealthDao
import com.health.app.data.db.entities.NetworkCheckEntity
import com.health.app.data.model.NetworkCheck
import com.health.app.logic.CheckOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Whether a provider was in network, each time anyone checked.
 *
 * Append-only: the earlier answers are what let `logic/NetworkStatus` tell "has left the network"
 * apart from "was never in it", and those need different phone calls.
 */
class NetworkCheckStore(
    private val dao: HealthDao
) {

    fun observeNetworkChecks(providerId: String): Flow<List<NetworkCheck>> =
        dao.observeNetworkChecks(providerId).map { rows -> rows.map { it.toModel() } }

    suspend fun getNetworkChecks(providerId: String): List<NetworkCheck> =
        dao.getNetworkChecks(providerId).map { it.toModel() }

    /**
     * Write down what was found. The carrier's name is stored **on the row** rather than looked up
     * through the plan, for the same reason a dose carries its medicine's name: the household changes
     * plans, and a check that reads "not listed by ⟨deleted plan⟩" is a check that has stopped being
     * evidence of anything.
     */
    suspend fun recordNetworkCheck(
        providerId: String,
        planId: String?,
        outcome: CheckOutcome,
        directoryLabel: String? = null,
        directoryUrl: String? = null,
        matchedName: String? = null,
        matchedNpi: String? = null,
        matchCount: Int = 0,
        networks: List<String> = emptyList(),
        detail: String? = null,
        checkedAt: Long = now()
    ): String {
        val id = newId()
        dao.upsertNetworkCheck(
            NetworkCheckEntity(
                id = id,
                providerId = providerId,
                planId = planId,
                checkedAt = checkedAt,
                outcome = outcome.key,
                directoryLabel = directoryLabel.clean(),
                directoryUrl = directoryUrl.clean(),
                matchedName = matchedName.clean(),
                matchedNpi = matchedNpi.clean(),
                matchCount = matchCount,
                networks = networks.filter { it.isNotBlank() }.joinToString(CSV).ifBlank { null },
                detail = detail.clean()
            )
        )
        return id
    }

    /** Undo a mis-tap. Offered one row at a time and never in bulk — see the DAO's note. */
    suspend fun deleteNetworkCheck(id: String) = dao.deleteNetworkCheck(id)
}
