package com.finance.app.logic

import java.time.LocalDate

/**
 * A single movement of money, and the vocabulary the rest of the module reasons in.
 *
 * ## The other sign problem
 *
 * Plaid reports a transaction amount as **positive when money leaves the account** — a $40 grocery
 * shop is `40.00`, a paycheque is `-2400.00`. Mercury does the opposite, and so does almost every
 * human being who has ever read a bank statement. Neither convention is wrong and both are load
 * bearing somewhere, so this module picks one at the parser boundary and never mentions it again:
 *
 * > **[amountCents] is signed the way a bank statement reads it: negative is money out, positive is
 * > money in.**
 *
 * `PlaidJson` negates on the way in; `MercuryJson` does not need to. Every screen, every roll-up and
 * every forecast below this line can add amounts together without asking whose API they came from,
 * which is the whole point of doing it once and in a tested place.
 */
data class Transaction(
    val id: String,
    val accountId: String,
    /** The provider's own id, which is what a re-sync matches on so a row is never doubled. */
    val providerTransactionId: String,
    /**
     * The day the transaction is filed under.
     *
     * Providers hand over both an authorised date and a posted date, often a day or three apart over
     * a weekend. This is the *posted* date where there is one, because the picture this app builds
     * has to reconcile against a statement, and a statement is posted dates.
     */
    val date: LocalDate,
    /** Negative is money out. See the class note. */
    val amountCents: Long,
    /** What the provider called it, cleaned up but not invented — see [Merchants.clean]. */
    val description: String,
    /** The merchant the provider identified, when it identified one. Better than the description. */
    val merchant: String?,
    val category: Category,
    /**
     * Pending transactions are shown and are deliberately **excluded** from every roll-up.
     *
     * A pending charge is a claim about the future — the amount routinely changes when it settles
     * (a restaurant tip, a fuel pump's pre-authorisation) and it is occasionally abandoned entirely.
     * Counting it in a monthly total means last month's total changes for three days after the month
     * ends, which makes every figure in the app unquotable.
     */
    val pending: Boolean = false,
    /**
     * A transfer between two accounts the household owns.
     *
     * Not a category but a flag, because a transfer is real spending from the account's point of
     * view and no spending at all from the household's. Roll-ups drop them; an account's own list
     * keeps them, or moving $500 from checking to savings looks like the money evaporated.
     */
    val transfer: Boolean = false
) {
    val outflow: Boolean get() = amountCents < 0L
    val inflow: Boolean get() = amountCents > 0L

    /** The name to show and to group by: the merchant if the provider found one, else the text. */
    fun label(): String = merchant?.takeIf { it.isNotBlank() } ?: description
}

/**
 * The categories this app rolls spending up into.
 *
 * Sixteen, and stopping there is a decision. There is deliberately no "subscriptions": neither
 * provider has a category that maps to one, so it was a slice that could only ever be empty — a
 * filter chip that never appeared and a legend entry that meant nothing. "What am I subscribed to"
 * is a better question answered elsewhere, by [Recurring], which the Activity screen leads with.
 *
 * Plaid's personal-finance taxonomy has a hundred and
 * four detailed categories under sixteen primaries, and the detailed layer is genuinely useful for
 * a budgeting app that asks you to set a limit per line. This is not that app: it answers "where
 * did it go" and "what is due", and at that altitude the difference between `FOOD_AND_DRINK_FAST_FOOD`
 * and `FOOD_AND_DRINK_COFFEE` is a distinction that produces two thin slices instead of one legible
 * one. So the primary layer is kept, mapped by [Categories.fromPlaid], and the detailed string is
 * carried on the row for anyone who wants to read it.
 */
enum class Category(val key: String, val label: String) {
    INCOME("income", "Income"),
    TRANSFER("transfer", "Transfers"),
    HOUSING("housing", "Rent & mortgage"),
    UTILITIES("utilities", "Utilities"),
    GROCERIES("groceries", "Groceries"),
    DINING("dining", "Eating out"),
    TRANSPORT("transport", "Transport"),
    FUEL("fuel", "Fuel"),
    INSURANCE("insurance", "Insurance"),
    MEDICAL("medical", "Medical"),
    DEBT("debt", "Loan & card payments"),
    SHOPPING("shopping", "Shopping"),
    ENTERTAINMENT("entertainment", "Entertainment"),
    TRAVEL("travel", "Travel"),
    FEES("fees", "Fees & interest"),
    OTHER("other", "Other");

    companion object {
        fun fromKey(key: String?): Category =
            entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: OTHER
    }
}

/**
 * Turning a provider's category into one of ours.
 *
 * The mapping is a `when` over strings rather than a table, because it is read far more often than
 * it is changed and a reader checking "where do insurance premiums land" should be able to see the
 * answer rather than trace a map through two files.
 */
object Categories {

    /**
     * Plaid's `personal_finance_category.primary`, mapped down.
     *
     * The three that are not one-to-one are worth naming: Plaid files loan *payments* under
     * `LOAN_PAYMENTS` and bank charges under `BANK_FEES`, which this app keeps apart as [Category.DEBT]
     * and [Category.FEES] because one is progress and the other is leakage; and it has no fuel
     * category at all — petrol lives under `TRANSPORTATION` — so [Category.FUEL] is reached through
     * the detailed string, but only under that primary. See the note on the transport branch.
     */
    fun fromPlaid(primary: String?, detailed: String? = null): Category {
        val group = primary?.uppercase().orEmpty()
        // Plaid's detailed category is its primary with a suffix bolted on
        // (`RENT_AND_UTILITIES` → `RENT_AND_UTILITIES_GAS_AND_ELECTRICITY`), so the primary's own
        // words are present in *every* detailed string under it. Matching on the whole thing filed
        // the gas bill under rent, because "RENT_AND_UTILITIES_GAS_AND_ELECTRICITY" does indeed
        // contain "RENT". Only the suffix carries information, so only the suffix is looked at.
        val detail = detailed?.uppercase().orEmpty().removePrefix("${group}_")
        return when (group) {
            "INCOME" -> Category.INCOME
            "TRANSFER_IN", "TRANSFER_OUT" -> Category.TRANSFER
            "RENT_AND_UTILITIES" ->
                if (detail.contains("RENT")) Category.HOUSING else Category.UTILITIES
            "HOME_IMPROVEMENT" -> Category.HOUSING
            "FOOD_AND_DRINK" ->
                if (detail.contains("GROCERIES")) Category.GROCERIES else Category.DINING
            // The word "gas" means two different things and only the primary tells them apart: gas
            // for the furnace is a utility and belongs above with the electricity bill; gas for the
            // car is fuel. So the check is scoped to transport rather than run over every category.
            "TRANSPORTATION" ->
                if (detail.contains("GAS") || detail.contains("FUEL")) Category.FUEL
                else Category.TRANSPORT
            "GENERAL_SERVICES" ->
                if (detail.contains("INSURANCE")) Category.INSURANCE else Category.OTHER
            "MEDICAL" -> Category.MEDICAL
            "LOAN_PAYMENTS" -> Category.DEBT
            "GENERAL_MERCHANDISE" -> Category.SHOPPING
            "ENTERTAINMENT" -> Category.ENTERTAINMENT
            "TRAVEL" -> Category.TRAVEL
            "BANK_FEES" -> Category.FEES
            "PERSONAL_CARE" -> Category.SHOPPING
            "GOVERNMENT_AND_NON_PROFIT" -> Category.OTHER
            else -> Category.OTHER
        }
    }

    /**
     * Mercury has no category taxonomy, so this reads the description.
     *
     * Mercury is a business bank and its accounts are business accounts, which makes the guesswork
     * both easier and less important: the great majority of what lands there is a transfer, a card
     * charge from a named vendor, or a fee, and none of the household-spending distinctions matter.
     * Anything not recognised becomes [Category.OTHER] rather than a guess — an unlabelled row is
     * honest, a wrongly-labelled one silently distorts a chart.
     */
    fun fromMercuryDescription(description: String, kind: String?): Category {
        val text = description.lowercase()
        return when {
            kind.equals("externalTransfer", ignoreCase = true) ||
                kind.equals("internalTransfer", ignoreCase = true) -> Category.TRANSFER
            kind.equals("fee", ignoreCase = true) || text.contains("fee") -> Category.FEES
            text.contains("payroll") || text.contains("gusto") -> Category.INCOME
            text.contains("insurance") -> Category.INSURANCE
            text.contains("irs") || text.contains("tax") -> Category.OTHER
            else -> Category.OTHER
        }
    }
}

/**
 * Making two descriptions of the same shop compare equal.
 *
 * This is what recurring detection is built on, so it lives here and is tested here rather than
 * inside [Recurring]. Bank descriptions for the same monthly charge are not stable strings: the
 * same electricity bill arrives as `"CITY UTILITIES 0423 AUTOPAY"` one month and
 * `"CITY UTILITIES 0524 AUTOPAY"` the next, and a naive group-by sees twelve merchants a year.
 */
object Merchants {

    /**
     * Tidy a provider description for display: collapse whitespace, drop the trailing reference
     * noise, and stop shouting.
     *
     * Deliberately conservative — it does not rewrite words or expand abbreviations, because a
     * merchant this app renamed is a merchant you cannot find in your bank's app when you go
     * looking for it.
     */
    fun clean(raw: String): String {
        val collapsed = raw.trim().replace(WHITESPACE, " ")
        val trimmed = collapsed.removeSuffix(" ").trimEnd(' ', '*', '-', '#')
        return if (trimmed.isBlank()) raw.trim() else trimmed
    }

    /**
     * A stable key for "the same merchant", ignoring the parts that change every month.
     *
     * Digits go entirely, which is the single biggest win: reference numbers, store numbers, dates
     * embedded in a memo line and invoice ids are all digits, and a merchant that is *only* digits
     * is not a merchant anybody could name anyway. What is left is upper-cased and stripped of the
     * payment-network prefixes that vary by how the charge was routed rather than by who was paid.
     */
    fun key(label: String): String {
        var text = label.uppercase()
        PREFIXES.forEach { prefix -> if (text.startsWith(prefix)) text = text.removePrefix(prefix) }
        text = text.filter { it.isLetter() || it == ' ' }.replace(WHITESPACE, " ").trim()
        // Long descriptions are mostly boilerplate after the name; the first three words identify
        // the payee in practice and keep "AMAZON MKTPL" and "AMAZON MKTPLACE PMTS" together.
        return text.split(' ').filter { it.isNotBlank() }.take(3).joinToString(" ")
    }

    private val WHITESPACE = Regex("\\s+")

    /** How the charge was routed, not who was paid. */
    private val PREFIXES = listOf(
        "ACH DEBIT ", "ACH CREDIT ", "POS DEBIT ", "POS ", "DEBIT CARD PURCHASE ",
        "RECURRING PAYMENT ", "PREAUTHORIZED DEBIT ", "SQ *", "TST* ", "PAYPAL *", "PP*"
    )
}
