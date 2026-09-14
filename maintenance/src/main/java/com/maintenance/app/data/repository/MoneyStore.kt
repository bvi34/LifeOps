package com.maintenance.app.data.repository

import com.maintenance.app.data.db.dao.MaintenanceDao
import com.maintenance.app.data.db.entities.CoverageEntity
import com.maintenance.app.data.db.entities.LoanEntity
import com.maintenance.app.logic.CoverageKind
import com.maintenance.app.logic.Loan
import com.maintenance.app.logic.PremiumPeriod

/**
 * What an asset costs to keep: its insurance cover and the loan still owed on it.
 */
class MoneyStore(
    private val dao: MaintenanceDao
) {

    suspend fun upsertLoan(
        loanId: String?,
        assetId: String,
        label: String,
        lender: String?,
        accountRef: String?,
        principalCents: Long,
        annualRateBps: Int,
        termMonths: Int,
        paymentCents: Long?,
        escrowCents: Long,
        startEpochDay: Long?,
        notes: String?
    ): String {
        val stamp = now()
        val id = loanId ?: newId()
        dao.upsertLoan(
            LoanEntity(
                id = id,
                assetId = assetId,
                label = label.trim().ifBlank { "Loan" },
                lender = lender?.trim()?.takeIf { it.isNotBlank() },
                accountRef = accountRef?.trim()?.takeIf { it.isNotBlank() },
                principalCents = principalCents,
                annualRateBps = annualRateBps,
                termMonths = termMonths,
                paymentCents = paymentCents?.takeIf { it > 0L },
                escrowCents = escrowCents,
                startEpochDay = startEpochDay,
                notes = notes?.trim()?.takeIf { it.isNotBlank() },
                createdAt = stamp,
                updatedAt = stamp
            )
        )
        return id
    }

    suspend fun deleteLoan(loanId: String) = dao.deleteLoan(loanId)

    suspend fun upsertCoverage(
        coverageId: String?,
        assetId: String,
        kind: CoverageKind,
        provider: String,
        policyNumber: String?,
        premiumCents: Long,
        period: PremiumPeriod,
        startsAt: Long?,
        expiresAt: Long?,
        notes: String?
    ): String {
        val stamp = now()
        val id = coverageId ?: newId()
        dao.upsertCoverage(
            CoverageEntity(
                id = id,
                assetId = assetId,
                kind = kind.key,
                provider = provider.trim(),
                policyNumber = policyNumber?.trim()?.takeIf { it.isNotBlank() },
                premiumCents = premiumCents,
                period = period.key,
                startsAt = startsAt,
                expiresAt = expiresAt,
                notes = notes?.trim()?.takeIf { it.isNotBlank() },
                createdAt = stamp,
                updatedAt = stamp
            )
        )
        return id
    }

    suspend fun deleteCoverage(coverageId: String) = dao.deleteCoverage(coverageId)
}
