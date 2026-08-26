package com.health.app.logic

import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import kotlin.math.floor

/**
 * The medicine cabinet as an inventory: what is physically in the house, how much of it is left, and
 * whether it is still in date.
 *
 * This is the half of the Meds tab that is about *things* rather than about people. A person's
 * medication (see `DoseSchedule`) answers "can I give it yet?"; a cabinet item answers the two
 * questions you only ask when you go looking for the bottle — **is there any left**, and **is it
 * still good**. Both are questions a household gets wrong at exactly the wrong moment, which is why
 * they are worth a rolling answer rather than a note on the box.
 *
 * Framework-free and unit-tested, like the rest of `logic/`. It computes only from what was written
 * down: an item with no expiry date is [ExpiryStatus.UNKNOWN], never "probably fine".
 */

/** How close an item is to its printed expiry date. */
enum class ExpiryStatus(val label: String) {
    /** No expiry date was recorded. Health says so rather than guessing. */
    UNKNOWN("No expiry date"),
    IN_DATE("In date"),
    /** Inside [Cabinet.EXPIRING_SOON_DAYS] of the printed date — time to replace it, not to panic. */
    EXPIRING_SOON("Expiring soon"),
    EXPIRED("Expired")
}

/** How much is left, against the item's own "tell me when it's this low" threshold. */
enum class StockStatus(val label: String) {
    /** No quantity was recorded — the item is in the cabinet, but nobody said how much. */
    UNKNOWN("Amount not tracked"),
    IN_STOCK("In stock"),
    LOW("Running low"),
    OUT("Out")
}

/**
 * The facts a cabinet item carries, in the shape this file reasons about. The database row (and the
 * screen) carry more — a location, a note, which product it is — but none of that changes the two
 * verdicts, so none of it appears here.
 *
 * [quantity] and [quantityUnit] are the *stock* ("120 mL left", "18 tablets"), while [doseAmount]
 * and [doseUnit] are one dose of it. They are separate fields because they are separate facts and
 * are not always in the same unit — and when they aren't, Health declines to convert rather than
 * inventing a factor.
 */
data class CabinetFacts(
    val quantity: Double? = null,
    val quantityUnit: String = "",
    /** The printed expiry, ISO `yyyy-MM-dd`, or `yyyy-MM` for the month-only dates most boxes carry. */
    val expiryDate: String? = null,
    val lowStockThreshold: Double? = null,
    val doseAmount: Double? = null,
    val doseUnit: String = ""
)

/**
 * What Health can say about one item today: the two statuses, how many days to (or past) the expiry,
 * and how many more doses are in the bottle when both facts are known and comparable.
 */
data class CabinetStatus(
    val expiry: ExpiryStatus,
    /** Negative once the date has passed. Null when no expiry was recorded. */
    val daysToExpiry: Long?,
    val stock: StockStatus,
    /** Whole doses left in the container — null unless quantity and dose share a unit. */
    val dosesRemaining: Int?,
    val summary: String
) {
    /** Whether this item is something the cabinet should be shouting about at the top of the list. */
    val needsAttention: Boolean
        get() = expiry == ExpiryStatus.EXPIRED ||
            expiry == ExpiryStatus.EXPIRING_SOON ||
            stock == StockStatus.OUT ||
            stock == StockStatus.LOW

    /** Expired first, then out, then low, then everything else — the order to read a cabinet in. */
    val sortRank: Int
        get() = when {
            expiry == ExpiryStatus.EXPIRED -> 0
            stock == StockStatus.OUT -> 1
            expiry == ExpiryStatus.EXPIRING_SOON -> 2
            stock == StockStatus.LOW -> 3
            else -> 4
        }
}

object Cabinet {

    /**
     * How far ahead "expiring soon" reaches. Two months is long enough to replace something on an
     * ordinary shopping trip and short enough that the warning still means something — a cabinet
     * where half the items are flagged is a cabinet nobody looks at.
     */
    const val EXPIRING_SOON_DAYS: Long = 60L

    /**
     * When no threshold was set, an item counts as running low at this fraction of one dose... which
     * is to say: only when it can no longer cover a single dose. Health will not decide for you that
     * three tablets is "low"; it will tell you when three tablets is not enough for the next dose.
     */
    private const val DEFAULT_LOW_DOSES = 1.0

    private const val EPSILON = 1e-9

    fun assess(facts: CabinetFacts, today: LocalDate = LocalDate.now()): CabinetStatus {
        val expiryDate = parseExpiry(facts.expiryDate)
        val daysToExpiry = expiryDate?.let { ChronoUnit.DAYS.between(today, it) }
        val expiry = when {
            daysToExpiry == null -> ExpiryStatus.UNKNOWN
            daysToExpiry < 0 -> ExpiryStatus.EXPIRED
            daysToExpiry <= EXPIRING_SOON_DAYS -> ExpiryStatus.EXPIRING_SOON
            else -> ExpiryStatus.IN_DATE
        }

        val dosesRemaining = dosesRemaining(facts)
        val stock = stockStatus(facts, dosesRemaining)

        return CabinetStatus(
            expiry = expiry,
            daysToExpiry = daysToExpiry,
            stock = stock,
            dosesRemaining = dosesRemaining,
            summary = summarize(facts, expiry, daysToExpiry, stock, dosesRemaining)
        )
    }

    /**
     * How many whole doses the container still holds.
     *
     * Only computed when the stock and the dose are written in the **same unit**. 120 mL of a
     * suspension dosed in mL is eight 15 mL doses; 120 mL of a suspension dosed in mg is a
     * conversion that depends on the concentration, and getting that wrong is the exact failure this
     * app exists to prevent. Where the units differ, Health returns null and says nothing.
     */
    fun dosesRemaining(facts: CabinetFacts): Int? {
        val quantity = facts.quantity ?: return null
        val dose = facts.doseAmount ?: return null
        if (dose <= EPSILON) return null
        if (!sameUnit(facts.quantityUnit, facts.doseUnit)) return null
        return floor((quantity + EPSILON) / dose).toInt().coerceAtLeast(0)
    }

    /**
     * Units are compared as the user typed them, case- and plural-insensitively — "mL" and "ml" are
     * the same unit and "tablet"/"tablets" are the same thing. Nothing beyond that: no unit algebra,
     * no mg↔mL, no teaspoon table.
     */
    fun sameUnit(a: String, b: String): Boolean {
        val left = normalizeUnit(a)
        val right = normalizeUnit(b)
        return left.isNotEmpty() && left == right
    }

    private fun normalizeUnit(raw: String): String =
        raw.trim().lowercase().removeSuffix(".").removeSuffix("s")

    private fun stockStatus(facts: CabinetFacts, dosesRemaining: Int?): StockStatus {
        val quantity = facts.quantity ?: return StockStatus.UNKNOWN
        if (quantity <= EPSILON) return StockStatus.OUT

        facts.lowStockThreshold?.let { threshold ->
            return if (quantity <= threshold + EPSILON) StockStatus.LOW else StockStatus.IN_STOCK
        }

        // No threshold: fall back to "can it still cover a dose?", which needs a comparable dose.
        if (dosesRemaining != null) {
            return if (dosesRemaining < DEFAULT_LOW_DOSES) StockStatus.LOW else StockStatus.IN_STOCK
        }
        return StockStatus.IN_STOCK
    }

    /** One line for the card — the worst news first, because that's what the line is for. */
    private fun summarize(
        facts: CabinetFacts,
        expiry: ExpiryStatus,
        daysToExpiry: Long?,
        stock: StockStatus,
        dosesRemaining: Int?
    ): String {
        val parts = mutableListOf<String>()

        when (expiry) {
            // Past the date by at least a day — the day itself still counts as in date, and reads
            // as "expires today" in the branch below.
            ExpiryStatus.EXPIRED -> parts += when (val days = -(daysToExpiry ?: 0L)) {
                1L -> "Expired yesterday"
                else -> "Expired ${describeDays(days)} ago"
            }
            ExpiryStatus.EXPIRING_SOON -> parts += when (val days = daysToExpiry ?: 0L) {
                0L -> "Expires today"
                1L -> "Expires tomorrow"
                else -> "Expires in ${describeDays(days)}"
            }
            ExpiryStatus.IN_DATE -> daysToExpiry?.let { parts += "In date for ${describeDays(it)}" }
            ExpiryStatus.UNKNOWN -> Unit
        }

        when (stock) {
            StockStatus.OUT -> parts += "None left"
            StockStatus.LOW -> parts += stockPhrase(facts, dosesRemaining, prefix = "Running low")
            StockStatus.IN_STOCK -> parts += stockPhrase(facts, dosesRemaining, prefix = null)
            StockStatus.UNKNOWN -> Unit
        }

        return parts.filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "In the cabinet" }
    }

    private fun stockPhrase(facts: CabinetFacts, dosesRemaining: Int?, prefix: String?): String {
        val quantity = facts.quantity ?: return prefix.orEmpty()
        val amount = "${trim(quantity)} ${facts.quantityUnit}".trim()
        val doses = dosesRemaining?.let { count ->
            when (count) {
                0 -> "not enough for a dose"
                1 -> "1 dose"
                else -> "$count doses"
            }
        }
        val body = listOfNotNull(amount.ifBlank { null }, doses).joinToString(" — ")
        return listOfNotNull(prefix, body.ifBlank { null }).joinToString(": ")
    }

    private fun describeDays(days: Long): String = when {
        days < 14 -> if (days == 1L) "1 day" else "$days days"
        days < 60 -> "${days / 7} weeks"
        days < 730 -> "${days / 30} months"
        else -> "${days / 365} years"
    }

    /**
     * Boxes print `03/2027` far more often than a full date, so a month-only value is accepted and
     * read the way a pharmacist reads it: the product is good **through the end of that month**.
     * Anything unparseable returns null, and the item is simply undated rather than wrongly expired.
     */
    fun parseExpiry(raw: String?): LocalDate? {
        val text = raw?.trim()?.ifBlank { null } ?: return null
        return try {
            when {
                text.length == 7 && text[4] == '-' ->
                    LocalDate.parse("$text-01").withDayOfMonth(1).plusMonths(1).minusDays(1)
                else -> LocalDate.parse(text)
            }
        } catch (_: DateTimeParseException) {
            null
        }
    }

    /** `2027-03` → "March 2027"; a full date is left to the UI's own formatter. */
    fun describeExpiry(raw: String?): String? {
        val text = raw?.trim()?.ifBlank { null } ?: return null
        val date = parseExpiry(text) ?: return text
        return if (text.length == 7) "${monthName(date.monthValue)} ${date.year}" else text
    }

    private fun monthName(month: Int): String = listOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December"
    ).getOrElse(month - 1) { month.toString() }

    private fun trim(value: Double): String =
        if (value % 1.0 == 0.0) value.toLong().toString() else Temperature.round1(value).toString()
}
