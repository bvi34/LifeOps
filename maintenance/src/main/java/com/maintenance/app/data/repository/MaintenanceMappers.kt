package com.maintenance.app.data.repository

import com.maintenance.app.data.db.entities.AssetAttributeEntity
import com.maintenance.app.data.db.entities.AssetEntity
import com.maintenance.app.data.db.entities.CoverageEntity
import com.maintenance.app.data.db.entities.LoanEntity
import com.maintenance.app.data.db.entities.ServiceRecordEntity
import com.maintenance.app.data.db.entities.UpkeepPlanEntity
import com.maintenance.app.data.model.Asset
import com.maintenance.app.data.model.LoanView
import com.maintenance.app.data.model.ServiceRecord
import com.maintenance.app.logic.AssetKind
import com.maintenance.app.logic.Coverage
import com.maintenance.app.logic.CoverageKind
import com.maintenance.app.logic.Loan
import com.maintenance.app.logic.LoanTerms
import com.maintenance.app.logic.PlanKind
import com.maintenance.app.logic.PremiumPeriod
import com.maintenance.app.logic.UpkeepPlan
import com.maintenance.app.logic.UpkeepTasks
import java.time.LocalDate
import java.time.ZoneId
import com.maintenance.app.data.db.entities.RecallEntity
import com.maintenance.app.logic.Recall

/**
 * Rows in, models out.
 *
 * Split out of the stores because they are a different kind of code: a store decides *when* to
 * write, these decide only what a row looks like once it has been read. Nothing here touches the
 * database.
 */
internal fun AssetEntity.toAsset(attributes: List<AssetAttributeEntity>) = Asset(
    id = id,
    name = name,
    kind = AssetKind.of(kind),
    make = make,
    model = model,
    year = year,
    purchasedAt = purchasedAt,
    purchasePriceCents = purchasePriceCents,
    currentValueCents = currentValueCents,
    notes = notes,
    colorArgb = colorArgb,
    archived = archived,
    sortOrder = sortOrder,
    createdAt = createdAt,
    updatedAt = updatedAt,
    attributes = attributes.associate { it.key to it.value }
)

internal fun UpkeepPlanEntity.toLink() = UpkeepTasks.TaskLink(
    taskId = lifeOpsTaskId,
    publishedDue = publishedDueDay?.let { LocalDate.ofEpochDay(it) }
)

/** `"60000,120000"` → `[60000, 120000]`, sorted and forgiving of whatever ended up in the column. */
internal fun String?.toMilestones(): List<Long> =
    orEmpty().split(',').mapNotNull { it.trim().toLongOrNull() }.filter { it > 0 }.sorted()

internal fun List<Long>.toMilestoneColumn(): String? =
    filter { it > 0 }.sorted().joinToString(",").takeIf { it.isNotEmpty() }

internal fun UpkeepPlanEntity.toPlan() = UpkeepPlan(
    id = id,
    assetId = assetId,
    title = title,
    notes = notes,
    everyDays = everyDays,
    everyMeter = everyMeter,
    atMeter = atMeter.toMilestones(),
    lastDoneAt = lastDoneAt,
    lastDoneMeter = lastDoneMeter,
    createdAt = createdAt,
    active = active,
    kind = PlanKind.of(kind),
    sourcePack = sourcePack,
    sourceItem = sourceItem,
    publishToLifeOps = publishToLifeOps
)


internal fun ServiceRecordEntity.toRecord() = ServiceRecord(
    id = id,
    assetId = assetId,
    planId = planId,
    title = title,
    vendor = vendor,
    performedAt = performedAt,
    costCents = costCents,
    meterValue = meterValue,
    notes = notes
)

internal fun CoverageEntity.toCoverage() = Coverage(
    id = id,
    assetId = assetId,
    kind = CoverageKind.of(kind),
    provider = provider,
    policyNumber = policyNumber,
    premiumCents = premiumCents,
    period = PremiumPeriod.of(period),
    startsAt = startsAt,
    expiresAt = expiresAt,
    notes = notes
)

internal fun LoanEntity.terms() = LoanTerms(
    principalCents = principalCents,
    annualRateBps = annualRateBps,
    termMonths = termMonths,
    paymentCents = paymentCents,
    escrowCents = escrowCents
)

internal fun LoanEntity.snapshotAt(now: Long) =
    Loan.snapshot(terms(), startEpochDay, epochDay(now))

internal fun LoanEntity.toView(valueCents: Long?, now: Long): LoanView {
    val terms = terms()
    val snapshot = Loan.snapshot(terms, startEpochDay, epochDay(now))
    return LoanView(
        id = id,
        assetId = assetId,
        label = label,
        lender = lender,
        accountRef = accountRef,
        terms = terms,
        startEpochDay = startEpochDay,
        notes = notes,
        snapshot = snapshot,
        payoffDate = Loan.payoffDate(terms, startEpochDay),
        equityCents = Loan.equityCents(valueCents, snapshot.balanceCents)
    )
}

/** Loans are counted in months, so their arithmetic works in local days, not milliseconds. */
internal fun epochDay(millis: Long): Long =
    java.time.Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()

internal fun recallDetail(row: RecallEntity): String = when {
    row.parkIt -> "Do not drive — ${row.summary}"
    row.parkOutside -> "Do not park indoors — ${row.summary}"
    else -> row.summary
}

internal fun RecallEntity.toRecall() = Recall(
    campaignNumber = campaignNumber,
    component = component,
    summary = summary,
    consequence = consequence,
    remedy = remedy,
    manufacturer = manufacturer,
    reportedOn = reportedOnEpochDay?.let { LocalDate.ofEpochDay(it) },
    parkIt = parkIt,
    parkOutside = parkOutside
)
