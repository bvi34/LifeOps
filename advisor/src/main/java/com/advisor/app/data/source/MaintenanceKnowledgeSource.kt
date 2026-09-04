package com.advisor.app.data.source

import android.content.Context
import com.advisor.app.logic.KnowledgeDocument
import com.advisor.app.logic.SourceApp
import com.maintenance.app.data.db.MaintenanceDatabase
import com.maintenance.app.data.db.entities.UpkeepPlanEntity
import com.maintenance.app.logic.AssetAttributes
import com.maintenance.app.logic.AssetKind
import com.maintenance.app.logic.Coverage
import com.maintenance.app.logic.CoverageKind
import com.maintenance.app.logic.Coverages
import com.maintenance.app.logic.DueStatus
import com.maintenance.app.logic.Loan
import com.maintenance.app.logic.LoanTerms
import com.maintenance.app.logic.MeterReading
import com.maintenance.app.logic.MeterState
import com.maintenance.app.logic.Money
import com.maintenance.app.logic.PlanKind
import com.maintenance.app.logic.PremiumPeriod
import com.maintenance.app.logic.Recall
import com.maintenance.app.logic.Upkeep
import com.maintenance.app.logic.UpkeepPlan
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Reads the household's register of what it owns and what those things need into
 * [KnowledgeDocument]s: the assets, the identity each is known by, the upkeep that comes round, the
 * service history, the meters, the money — mortgages, loans, policies — and any open recalls.
 *
 * Two things are deliberate here, both borrowed from the Health source for the same reasons:
 *
 *  - **Every document names its asset.** "Due in 3 weeks" without saying whose is a document that
 *    can be retrieved into an answer about the wrong car. Nothing here is anonymous.
 *  - **The verdict travels with the number.** Whether a plan is overdue, what a policy costs a year,
 *    where a loan stands today — all of it comes from Maintenance's own `logic/` (`Upkeep.evaluate`,
 *    `Coverages`, `Loan.snapshot`), so Advisor repeats the app's arithmetic rather than inventing a
 *    second opinion from the raw columns.
 *
 * The paperwork itself — the title, the policy PDF, the manual — is not here. Those are documents
 * the household filed and they are indexed once, from Repository's shelf, by
 * [RepositoryKnowledgeSource]; a document filed against an asset already carries that asset's name.
 *
 * Like every source, it is loaded only when the user has granted Maintenance in
 * [com.advisor.app.logic.AdvisorPermissions] — the permission gate lives above this class.
 */
class MaintenanceKnowledgeSource(context: Context) : KnowledgeSource {

    private val appContext = context.applicationContext
    override val source = SourceApp.MAINTENANCE

    override suspend fun load(): List<KnowledgeDocument> {
        val dao = MaintenanceDatabase.getInstance(appContext).maintenanceDao()
        val now = System.currentTimeMillis()
        val today = LocalDate.now()
        val docs = ArrayList<KnowledgeDocument>()

        val assets = dao.allAssets()
        if (assets.isEmpty()) return docs

        val names = assets.associate { it.id to it.name }
        val kinds = assets.associate { it.id to AssetKind.of(it.kind) }
        fun which(assetId: String) = names[assetId] ?: "an asset"

        val attributes = dao.allAttributes().groupBy { it.assetId }
        val readings = dao.allReadings().groupBy { it.assetId }
        val meters = assets.associate { asset ->
            asset.id to MeterState.from(
                kinds.getValue(asset.id).meter,
                readings[asset.id].orEmpty().map { MeterReading(it.readAt, it.value) }
            )
        }

        val plans = dao.allPlans()
        val plansByAsset = plans.groupBy { it.assetId }
        val planTitles = plans.associate { it.id to it.title }

        // --- the assets themselves ---
        for (asset in assets) {
            val kind = kinds.getValue(asset.id)
            val meter = meters[asset.id]
            val verdicts = plansByAsset[asset.id].orEmpty()
                .filter { it.active }
                .map { Upkeep.evaluate(it.toPlan(), now, meter) }
            val pressing = verdicts.count { it.status.isPressing }
            docs += KnowledgeDocument(
                id = "maintenance:asset:${asset.id}",
                source = source,
                kind = "asset",
                title = asset.name,
                body = buildString {
                    append("Asset: ").append(asset.name)
                    append(" (").append(kind.label.lowercase()).append(')')
                    val made = listOfNotNull(
                        asset.year?.toString(),
                        asset.make?.takeIf { it.isNotBlank() },
                        asset.model?.takeIf { it.isNotBlank() }
                    )
                    if (made.isNotEmpty()) append(". ").append(made.joinToString(" "))
                    // The identity the thing is known by — a VIN, a parcel number, a serial. It is
                    // the answer to half the questions ever asked of a register like this.
                    for (attribute in attributes[asset.id].orEmpty()) {
                        val spec = kind.attributes.firstOrNull { it.key == attribute.key }
                        val shown = if (spec != null) AssetAttributes.display(spec, attribute.value)
                        else attribute.value
                        if (shown.isBlank()) continue
                        append(". ").append(spec?.label ?: attribute.key).append(": ").append(shown)
                    }
                    meter?.let { state ->
                        state.current?.let {
                            append(". ").append(state.unit.reading)
                            append(": ").append(state.unit.format(it))
                        }
                        // A rate is what turns a mileage interval into a date, so it is worth
                        // saying in the corpus too: "about 9,000 miles a year" answers questions
                        // no single reading can.
                        state.perDay?.takeIf { it > 0.0 }?.let { rate ->
                            append(". About ").append(Math.round(rate * 365))
                            append(' ').append(state.unit.noun).append(" a year")
                        }
                    }
                    asset.purchasePriceCents?.let { append(". Bought for ").append(Money.format(it)) }
                    asset.currentValueCents?.let { append(". Worth about ").append(Money.format(it)) }
                    if (pressing > 0) append(". ").append(pressing).append(" job(s) due or overdue")
                    asset.notes?.takeIf { it.isNotBlank() }?.let { append(". Notes: ").append(it) }
                    if (asset.archived) append(". No longer owned.")
                },
                timestamp = asset.updatedAt
            )
        }

        // --- upkeep: the jobs that come round, graded the way the docket grades them ---
        for (plan in plans) {
            val kind = kinds[plan.assetId] ?: AssetKind.OTHER
            val verdict = Upkeep.evaluate(plan.toPlan(), now, meters[plan.assetId])
            docs += KnowledgeDocument(
                id = "maintenance:plan:${plan.id}",
                source = source,
                kind = "upkeep",
                title = plan.title,
                body = buildString {
                    append(PlanKind.of(plan.kind).label).append(" for ")
                    append(which(plan.assetId)).append(": ").append(plan.title)
                    append(". Status: ").append(verdict.status.phrase())
                    append(". ").append(verdict.summary)
                    plan.everyDays?.let { append(". Every ").append(it).append(" days") }
                    plan.everyMeter?.let {
                        append(". Every ").append(kind.meter?.format(it) ?: it.toString())
                    }
                    plan.lastDoneAt?.let { append(". Last done: ").append(dayOf(it)) }
                    plan.notes?.takeIf { it.isNotBlank() }?.let { append(". Notes: ").append(it) }
                    if (!plan.active) append(". Paused.")
                },
                timestamp = plan.updatedAt
            )
        }

        // --- the service history: what was actually done, and what it cost ---
        for (record in dao.allRecords()) {
            val kind = kinds[record.assetId] ?: AssetKind.OTHER
            docs += KnowledgeDocument(
                id = "maintenance:service:${record.id}",
                source = source,
                kind = "service",
                title = record.title,
                body = buildString {
                    append("Service on ").append(which(record.assetId)).append(": ").append(record.title)
                    append(". Done: ").append(dayOf(record.performedAt))
                    append(". Cost: ").append(Money.format(record.costCents))
                    record.vendor?.takeIf { it.isNotBlank() }?.let { append(". By: ").append(it) }
                    record.meterValue?.let { append(". At: ").append(kind.meter?.format(it) ?: it.toString()) }
                    record.planId?.let { id -> planTitles[id]?.let { append(". Satisfied: ").append(it) } }
                    record.notes?.takeIf { it.isNotBlank() }?.let { append(". Notes: ").append(it) }
                },
                timestamp = record.performedAt
            )
        }

        // --- the money: what is owed, and what is covered ---
        for (loan in dao.allLoans()) {
            val terms = LoanTerms(
                principalCents = loan.principalCents,
                annualRateBps = loan.annualRateBps,
                termMonths = loan.termMonths,
                paymentCents = loan.paymentCents,
                escrowCents = loan.escrowCents
            )
            val snapshot = Loan.snapshot(terms, loan.startEpochDay, today.toEpochDay())
            docs += KnowledgeDocument(
                id = "maintenance:loan:${loan.id}",
                source = source,
                kind = "loan",
                title = loan.label,
                body = buildString {
                    append("Loan on ").append(which(loan.assetId)).append(": ").append(loan.label)
                    loan.lender?.takeIf { it.isNotBlank() }?.let { append(". Lender: ").append(it) }
                    append(". Borrowed: ").append(Money.format(loan.principalCents))
                    append(" at ").append(Loan.formatRate(loan.annualRateBps))
                    append(" over ").append(loan.termMonths).append(" months")
                    append(". Payment: ").append(Money.format(terms.totalMonthlyCents())).append(" a month")
                    if (loan.escrowCents > 0L) append(" (including ").append(Money.format(loan.escrowCents)).append(" escrow)")
                    append(". Balance: ").append(Money.format(snapshot.balanceCents))
                    append(" after ").append(snapshot.paymentsMade).append(" payment(s)")
                    snapshot.paymentsRemaining?.let { append(". ").append(it).append(" payment(s) left") }
                    Loan.payoffDate(terms, loan.startEpochDay)?.let { append(". Paid off: ").append(it) }
                    loan.notes?.takeIf { it.isNotBlank() }?.let { append(". Notes: ").append(it) }
                },
                timestamp = loan.updatedAt
            )
        }

        for (row in dao.allCoverages()) {
            val coverage = Coverage(
                id = row.id,
                assetId = row.assetId,
                kind = CoverageKind.of(row.kind),
                provider = row.provider,
                policyNumber = row.policyNumber,
                premiumCents = row.premiumCents,
                period = PremiumPeriod.of(row.period),
                startsAt = row.startsAt,
                expiresAt = row.expiresAt,
                notes = row.notes
            )
            docs += KnowledgeDocument(
                id = "maintenance:coverage:${row.id}",
                source = source,
                kind = "coverage",
                title = "${coverage.kind.label} — ${coverage.provider}",
                body = buildString {
                    append(coverage.kind.label).append(" on ").append(which(row.assetId))
                    append(" with ").append(coverage.provider)
                    coverage.policyNumber?.takeIf { it.isNotBlank() }?.let { append(". Policy number: ").append(it) }
                    append(". Status: ").append(Coverages.status(coverage, now).phrase())
                    append(". ").append(Coverages.summary(coverage, now))
                    append(". Premium: ").append(Money.format(coverage.premiumCents))
                    append(' ').append(coverage.period.label)
                    if (coverage.period != PremiumPeriod.ONE_TIME) {
                        append(" (").append(Money.format(Coverages.annualCents(coverage))).append(" a year)")
                    }
                    coverage.expiresAt?.let { append(". Ends: ").append(dayOf(it)) }
                    coverage.notes?.takeIf { it.isNotBlank() }?.let { append(". Notes: ").append(it) }
                },
                timestamp = row.updatedAt
            )
        }

        // --- recalls: the one thing on the register that goes stale on its own ---
        for (row in dao.allRecalls()) {
            val recall = Recall(
                campaignNumber = row.campaignNumber,
                component = row.component,
                summary = row.summary,
                consequence = row.consequence,
                remedy = row.remedy,
                manufacturer = row.manufacturer,
                reportedOn = row.reportedOnEpochDay?.let { LocalDate.ofEpochDay(it) },
                parkIt = row.parkIt,
                parkOutside = row.parkOutside
            )
            docs += KnowledgeDocument(
                id = "maintenance:recall:${row.assetId}:${row.campaignNumber}",
                source = source,
                kind = "recall",
                title = "Recall ${recall.campaignNumber} — ${which(row.assetId)}",
                body = buildString {
                    append("Recall on ").append(which(row.assetId))
                    append(" (").append(recall.campaignNumber).append("): ").append(recall.headline)
                    append(". Status: ").append(if (row.acknowledgedAt != null) "acknowledged" else "outstanding")
                    if (recall.isUrgent) append(". Urgent — the manufacturer says not to drive or not to park indoors.")
                    recall.reportedOn?.let { append(". Reported: ").append(it) }
                    recall.summary.takeIf { it.isNotBlank() }?.let { append(". ").append(it) }
                    recall.consequence?.takeIf { it.isNotBlank() }?.let { append(". Consequence: ").append(it) }
                    recall.remedy?.takeIf { it.isNotBlank() }?.let { append(". Remedy: ").append(it) }
                },
                timestamp = row.fetchedAt
            )
        }

        return docs
    }

    /**
     * The due vocabulary as one lower-case phrase, so `Status:` reads the same for a plan and a
     * policy and [com.advisor.app.logic.KnowledgeFacets] has one thing to normalise.
     */
    private fun DueStatus.phrase(): String = when (this) {
        DueStatus.OVERDUE -> "overdue"
        DueStatus.DUE_SOON -> "due soon"
        DueStatus.SCHEDULED -> "scheduled"
        DueStatus.NEEDS_BASELINE -> "needs baseline"
        DueStatus.DORMANT -> "dormant"
    }

    /**
     * The stored row as the arithmetic wants it. Maintenance's own mapper is private to its
     * repository, and duplicating these fourteen lines is the price of this source staying a
     * read-only consumer that constructs nothing of the app's.
     */
    private fun UpkeepPlanEntity.toPlan() = UpkeepPlan(
        id = id,
        assetId = assetId,
        title = title,
        notes = notes,
        everyDays = everyDays,
        everyMeter = everyMeter,
        atMeter = atMeter.orEmpty().split(',').mapNotNull { it.trim().toLongOrNull() }.filter { it > 0 }.sorted(),
        lastDoneAt = lastDoneAt,
        lastDoneMeter = lastDoneMeter,
        createdAt = createdAt,
        active = active,
        kind = PlanKind.of(kind),
        sourcePack = sourcePack,
        sourceItem = sourceItem,
        publishToLifeOps = publishToLifeOps
    )

    /** Epoch millis as the day it fell on here — a date a person would recognise, not a number. */
    private fun dayOf(millis: Long): LocalDate =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
}
