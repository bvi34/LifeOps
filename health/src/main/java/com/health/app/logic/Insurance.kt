package com.health.app.logic

import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

/**
 * Health insurance as this app treats it: **a card you copied down, not a policy Health understands**.
 *
 * The distinction runs through the whole file and is the reason it is short. An insurance policy is a
 * legal document about what is covered, at what share, after which deductible — and Health knows
 * none of that, will not guess at it, and would be doing something reckless if it tried. What a
 * household actually needs at the counter, on the phone, or in a waiting room is far smaller and
 * entirely concrete: **who the insurer is, what the plan is called, what the member number is, and
 * which phone number is on the back.** That is a card. Health keeps the card.
 *
 * So the only judgements made here are the two a copied card can support honestly:
 *
 *  - **Is this coverage current?** — from the effective and end dates *as written down*. An
 *    undated plan is [CoverageStatus.UNKNOWN], never "probably active": a card with no dates on it
 *    is a card that says nothing about today, and pretending otherwise is how somebody turns up to
 *    an appointment on a policy that lapsed in March.
 *  - **What goes on which face of the card?** — [card], which is the single source of truth for
 *    both the on-screen card and the PDF. One layout, two renderers; a member number that reads
 *    differently on the screen and on the export is a bug that only ever shows up at a reception
 *    desk.
 *
 * Framework-free and unit-tested like the rest of `logic/`. Nothing in here formats for Android, and
 * nothing in here decides what is covered.
 */

/** How a plan pays for care, as printed on the card. Free text would defeat the point; this is a list of what cards say. */
enum class PlanType(val key: String, val label: String) {
    HMO("hmo", "HMO"),
    PPO("ppo", "PPO"),
    EPO("epo", "EPO"),
    POS("pos", "POS"),
    HDHP("hdhp", "HDHP"),
    MEDICARE("medicare", "Medicare"),
    MEDICAID("medicaid", "Medicaid"),
    TRICARE("tricare", "TRICARE"),
    OTHER("other", "Other");

    companion object {
        fun fromKey(key: String?): PlanType = entries.firstOrNull { it.key == key } ?: OTHER
    }
}

/**
 * What the card is *for*. A household routinely carries three or four separate cards from three or
 * four separate carriers, and filing the dental one under "insurance" alongside the medical one is
 * how the wrong card gets handed over. They are different coverage, so they are different rows.
 */
enum class CoverageKind(val key: String, val label: String) {
    MEDICAL("medical", "Medical"),
    DENTAL("dental", "Dental"),
    VISION("vision", "Vision"),
    PHARMACY("pharmacy", "Pharmacy"),
    OTHER("other", "Other");

    companion object {
        fun fromKey(key: String?): CoverageKind = entries.firstOrNull { it.key == key } ?: MEDICAL
    }
}

/** Whether the card is good today, judged only on the dates somebody wrote on it. */
enum class CoverageStatus(val label: String) {
    /** No dates recorded. Health says so rather than assuming the card in your hand is current. */
    UNKNOWN("Dates not recorded"),
    /** The effective date is in the future — a plan that starts on the 1st. */
    NOT_STARTED("Not started yet"),
    ACTIVE("Active"),
    /** Inside [Insurance.ENDING_SOON_DAYS] of the end date. Renewal season, not a crisis. */
    ENDING_SOON("Ending soon"),
    ENDED("Ended");

    /** Whether this is something the coverage list should be saying out loud. */
    val needsAttention: Boolean get() = this == ENDED || this == ENDING_SOON || this == NOT_STARTED

    /** Ended first, then not-yet-started, then ending soon — the order to read a wallet in. */
    val sortRank: Int
        get() = when (this) {
            ENDED -> 0
            NOT_STARTED -> 1
            ENDING_SOON -> 2
            UNKNOWN -> 3
            ACTIVE -> 4
        }
}

/**
 * Everything printed on one person's card, flattened.
 *
 * Two rows in the database feed this — the plan (household-scoped: one policy, one carrier, one set
 * of phone numbers) and the membership (per person: their own member number and person code) — and
 * they are deliberately *not* flattened in storage. They are flattened here because a card is a
 * single physical object, and the code that draws one should not have to know which half of the
 * schema each line came from.
 *
 * Every field is nullable or blank-able. A card photographed in a hurry with only a carrier name and
 * a member number on it is still worth more than nothing, and a form that demands the payer id
 * before it will save is a form nobody finishes.
 */
data class CardFacts(
    val carrierName: String,
    val planName: String? = null,
    val coverageKind: CoverageKind = CoverageKind.MEDICAL,
    val planType: PlanType = PlanType.OTHER,
    val memberName: String? = null,
    val memberId: String? = null,
    /** The two-digit suffix that tells a family plan's members apart. Printed as "Person code" or "Dep #". */
    val personCode: String? = null,
    val groupNumber: String? = null,
    val subscriberName: String? = null,
    val relationshipToSubscriber: String? = null,
    /** The payer id claims are filed against — the number a receptionist actually asks for. */
    val payerId: String? = null,
    val rxBin: String? = null,
    val rxPcn: String? = null,
    val rxGroup: String? = null,
    /** ISO `yyyy-MM-dd`. */
    val effectiveDate: String? = null,
    val endDate: String? = null,
    val memberServicesPhone: String? = null,
    val nurseLinePhone: String? = null,
    val directoryUrl: String? = null,
    val note: String? = null
)

/** One labelled line on the card. Blank values never become a [CardField]; see [Insurance.card]. */
data class CardField(val label: String, val value: String)

/** One face of the card: a heading, the lines on it, and the small print underneath. */
data class CardFace(val title: String, val subtitle: String?, val fields: List<CardField>, val footnotes: List<String>)

/** The whole card, both faces, as the screen and the PDF each draw it. */
data class CardLayout(val front: CardFace, val back: CardFace)

/** How current the coverage is, with the arithmetic that got there. */
data class CoverageAssessment(
    val status: CoverageStatus,
    /** Negative once the end date has passed. Null when no end date was recorded. */
    val daysToEnd: Long?,
    val summary: String
)

object Insurance {

    /**
     * How far ahead "ending soon" reaches. A month is long enough to renew or to ask the employer
     * what happens next, and short enough that the warning still means something.
     */
    const val ENDING_SOON_DAYS: Long = 31L

    /**
     * The line that goes on every exported card, and it is not decoration.
     *
     * A PDF that reproduces a carrier's card faithfully enough to be useful is also faithful enough
     * to be mistaken for the card itself — by a receptionist, by a pharmacy, possibly by the person
     * carrying it. This one says what it is: a copy of what somebody typed into a phone app, which
     * may be out of date, mistyped, or superseded by a card that arrived in the post last week. It
     * is the same reflex as the fever disclaimer: the app is a record, not an authority.
     */
    const val CARD_DISCLAIMER: String =
        "Copied into Health from your own card. Not issued by the insurer and not proof of coverage — " +
            "check the real card, or ring member services, before it matters."

    /**
     * Is this coverage current today?
     *
     * Judged only on the two dates. Both missing is [CoverageStatus.UNKNOWN] — which is the *common*
     * case, because most cards print an effective date and no end date at all, and a plan with no
     * printed end is exactly the plan Health has no business declaring active or lapsed.
     */
    fun assessCoverage(
        effectiveDate: String?,
        endDate: String?,
        today: LocalDate = LocalDate.now()
    ): CoverageAssessment {
        val start = parseDate(effectiveDate)
        val end = parseDate(endDate)
        val daysToEnd = end?.let { ChronoUnit.DAYS.between(today, it) }

        val status = when {
            end != null && daysToEnd!! < 0 -> CoverageStatus.ENDED
            start != null && start.isAfter(today) -> CoverageStatus.NOT_STARTED
            daysToEnd != null && daysToEnd <= ENDING_SOON_DAYS -> CoverageStatus.ENDING_SOON
            start != null || end != null -> CoverageStatus.ACTIVE
            else -> CoverageStatus.UNKNOWN
        }

        return CoverageAssessment(status, daysToEnd, summarize(status, start, end, daysToEnd, today))
    }

    private fun summarize(
        status: CoverageStatus,
        start: LocalDate?,
        end: LocalDate?,
        daysToEnd: Long?,
        today: LocalDate
    ): String = when (status) {
        CoverageStatus.UNKNOWN -> "No effective date recorded"
        CoverageStatus.NOT_STARTED -> when (val days = start?.let { ChronoUnit.DAYS.between(today, it) } ?: 0L) {
            1L -> "Starts tomorrow"
            else -> "Starts in ${describeDays(days)}"
        }
        CoverageStatus.ENDED -> when (val days = -(daysToEnd ?: 0L)) {
            1L -> "Ended yesterday"
            else -> "Ended ${describeDays(days)} ago"
        }
        CoverageStatus.ENDING_SOON -> when (val days = daysToEnd ?: 0L) {
            0L -> "Ends today"
            1L -> "Ends tomorrow"
            else -> "Ends in ${describeDays(days)}"
        }
        CoverageStatus.ACTIVE -> when {
            end != null -> "Active — ends in ${describeDays(daysToEnd ?: 0L)}"
            start != null -> "Active since ${describeDate(start)}"
            else -> "Active"
        }
    }

    /**
     * Lay a card out, both faces.
     *
     * The split follows the physical object rather than the schema: **who you are** on the front,
     * **who to ring and what to quote** on the back. That is where a person's eye already goes, and
     * a card that rearranges the familiar layout to suit a database is a card that takes longer to
     * read than the one in the wallet.
     *
     * Blank fields are dropped rather than rendered empty. A row reading "Group —" is worse than no
     * row: it invites the reader to conclude the plan has no group number, when all it means is that
     * nobody typed one in.
     */
    fun card(facts: CardFacts): CardLayout {
        val front = mutableListOf<Pair<String, String?>>(
            "Member" to facts.memberName,
            "Member ID" to facts.memberId,
            "Person code" to facts.personCode,
            "Group" to facts.groupNumber,
            "Plan" to facts.planType.takeIf { it != PlanType.OTHER }?.label,
            "Effective" to describeDate(parseDate(facts.effectiveDate)),
            "Through" to describeDate(parseDate(facts.endDate))
        )
        // The subscriber is only worth a line when it isn't the person holding the card — which is
        // exactly the case that trips people up at a desk, and exactly the case a family plan makes
        // ordinary.
        if (!facts.subscriberName.isNullOrBlank() &&
            !facts.subscriberName.equals(facts.memberName?.trim(), ignoreCase = true)
        ) {
            front += "Subscriber" to facts.subscriberName
            front += "Relationship" to facts.relationshipToSubscriber
        }

        val back = listOf(
            "Payer ID" to facts.payerId,
            "Rx BIN" to facts.rxBin,
            "Rx PCN" to facts.rxPcn,
            "Rx Group" to facts.rxGroup,
            "Member services" to facts.memberServicesPhone,
            "Nurse line" to facts.nurseLinePhone,
            "Provider directory" to facts.directoryUrl
        )

        return CardLayout(
            front = CardFace(
                title = facts.carrierName.trim(),
                subtitle = listOfNotNull(
                    facts.planName?.trim()?.ifBlank { null },
                    facts.coverageKind.takeIf { it != CoverageKind.MEDICAL }?.label
                ).joinToString(" · ").ifBlank { null },
                fields = front.toFields(),
                footnotes = listOf(CARD_DISCLAIMER)
            ),
            back = CardFace(
                title = facts.carrierName.trim(),
                subtitle = "Back of card",
                fields = back.toFields(),
                footnotes = listOfNotNull(facts.note?.trim()?.ifBlank { null }, CARD_DISCLAIMER)
            )
        )
    }

    /** Labelled values in, drawable lines out — with everything nobody filled in left off the card. */
    private fun List<Pair<String, String?>>.toFields(): List<CardField> =
        mapNotNull { (label, value) ->
            value?.trim()?.ifBlank { null }?.let { CardField(label, it) }
        }

    /**
     * A member number with everything but the last four hidden — what the *list* shows.
     *
     * A member id is not a password, but it is the number a plan will read an account out against
     * over the phone, and a household health app that prints it in full on a screen anyone can see
     * over your shoulder is being careless for no gain. The card view and the PDF show it in full,
     * because showing it in full is the entire point of those two things; the browsing list does not
     * need to.
     */
    fun maskMemberId(memberId: String?): String? {
        val trimmed = memberId?.trim()?.ifBlank { null } ?: return null
        if (trimmed.length <= VISIBLE_ID_CHARS) return trimmed
        return "•".repeat(3) + " " + trimmed.takeLast(VISIBLE_ID_CHARS)
    }

    /** ISO `yyyy-MM-dd` → a [LocalDate], or null for blank, malformed, or impossible dates. */
    fun parseDate(raw: String?): LocalDate? {
        val text = raw?.trim()?.ifBlank { null } ?: return null
        return try {
            LocalDate.parse(text)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    /** `2026-03-01` → "1 Mar 2026". Unparseable input comes back as null, never as a guess. */
    fun describeDate(date: LocalDate?): String? =
        date?.let { "${it.dayOfMonth} ${monthAbbreviation(it.monthValue)} ${it.year}" }

    private fun describeDays(days: Long): String = when {
        days < 14 -> if (days == 1L) "1 day" else "$days days"
        days < 60 -> "${days / 7} weeks"
        days < 730 -> "${days / 30} months"
        else -> "${days / 365} years"
    }

    private fun monthAbbreviation(month: Int): String = listOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
    ).getOrElse(month - 1) { month.toString() }

    private const val VISIBLE_ID_CHARS = 4
}
