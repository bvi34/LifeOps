package com.finance.app.logic

import kotlin.math.abs

/**
 * What an account *is*, and the one rule that makes a pile of balances into a picture.
 *
 * ## The sign problem, and how this app answers it
 *
 * Every institution reports a credit card balance as a **positive** number, because from the card
 * issuer's point of view you owe them $840 and 840 is a positive quantity. Add that to a checking
 * balance and the household is richer for having a credit card, which is the single most common bug
 * in a personal finance app and the reason this file exists before any of the others.
 *
 * The rule taken here: **an account's stored balance is always the institution's own number, exactly
 * as reported**, and the *direction* lives on the kind ([AccountKind.owed]). Nothing in this module
 * flips a sign at the point it is stored — a balance you can compare against the bank's app is worth
 * more than one that is already interpreted — and anything that adds accounts together goes through
 * [NetPosition], which asks the kind which way the money points.
 *
 * The consequence is worth stating because it looks like an inconsistency: `Account.balanceCents`
 * for a card reads `84_000` (what you owe), while `NetPosition.liabilitiesCents` for the same card
 * also reads `84_000`, and `net` subtracts it. Two positive numbers, one meaning "the size of the
 * debt", never a negative balance pretending to be a bank statement.
 */
object Accounts {

    /**
     * How much of an account's balance is real right now.
     *
     * Institutions report two figures and they are routinely thousands apart. [available] is what
     * you can actually spend — settled, minus holds, plus overdraft line if the bank counts one.
     * [current] is the ledger figure, which includes transactions that have posted but not settled
     * and excludes holds that have not.
     *
     * The picture in this app is built from *available* wherever there is one, because the question
     * it answers is "will the mortgage clear on the 14th", and a hold on a $600 hotel deposit is
     * money that will not be there when the mortgage lands. [current] is what a card is compared
     * against instead, since a card has no meaningful available balance in this sense — its
     * "available" is a credit limit, which is not money.
     */
    data class Balance(val currentCents: Long, val availableCents: Long?, val limitCents: Long? = null) {

        /**
         * The figure this app plans with. For anything you owe there is only one honest number —
         * the balance — since a card's "available" is headroom to borrow rather than money held.
         */
        fun spendable(kind: AccountKind): Long =
            if (kind.owed) currentCents else availableCents ?: currentCents

        /**
         * How much of a credit line is used, 0..1, or null when there is no line to be used.
         *
         * Kept here rather than on a screen because a utilisation over about 0.3 is the single
         * number most likely to be the reason a credit score moved, and it should mean the same
         * thing everywhere it is shown.
         */
        fun utilisation(): Double? {
            val limit = limitCents ?: return null
            if (limit <= 0L) return null
            return currentCents.toDouble() / limit.toDouble()
        }
    }

    /**
     * One account at one institution, as this app holds it.
     *
     * [providerAccountId] is the institution's own id, not ours, and is what a refresh matches on:
     * an account renamed at the bank is the same account, and re-linking an institution must not
     * produce a second copy of every account with the balances split between them.
     */
    data class Account(
        val id: String,
        val connectionId: String,
        val providerAccountId: String,
        val name: String,
        val officialName: String?,
        /** The last four digits, and only the last four — see [Masking]. */
        val mask: String?,
        val kind: AccountKind,
        val balance: Balance,
        val currency: String = "USD",
        /** Excluded accounts still refresh and still show; they just don't count towards the picture. */
        val includeInPicture: Boolean = true,
        val closed: Boolean = false
    ) {
        /** What to call it in a list: "USAA Classic Checking ••1234". */
        fun displayName(): String = Masking.label(name, mask)
    }

    /**
     * Assets, debts and the difference — the one figure the Picture screen leads with.
     *
     * [assetsCents] and [liabilitiesCents] are both **positive magnitudes**; [netCents] is the
     * subtraction and is the only number here allowed to be negative, which for a household with a
     * mortgage and a young car is the normal case rather than an alarm.
     */
    data class NetPosition(
        val assetsCents: Long,
        val liabilitiesCents: Long,
        /** Cash you could spend today: depository accounts only, at their available figure. */
        val cashCents: Long,
        /** The currency every figure above is in. Nothing in another one was added to them. */
        val currency: String = "USD",
        /**
         * The currencies of accounts left out because they are not [currency].
         *
         * Empty for almost every household, and the whole point when it is not: a screen that has
         * something here has to say so, because the alternative is a total that silently means
         * nothing.
         */
        val excludedCurrencies: Set<String> = emptySet()
    ) {
        val netCents: Long get() = assetsCents - liabilitiesCents

        /** True when something was left out and the figures are therefore a partial answer. */
        val partial: Boolean get() = excludedCurrencies.isNotEmpty()
    }

    /**
     * The currency the picture is denominated in: whichever most of the counted accounts use.
     *
     * Derived rather than configured, because it is not a decision anybody wants to make — a
     * household knows what its money is in, and asking would be a settings row that exists to state
     * the obvious.
     *
     * Three keys, in order, and the order is the whole of it. **Most accounts** first, because the
     * ordinary shape of this problem is a household with several accounts at home and one abroad.
     * **Most money** second, for the genuine tie — one account each way, where the count says
     * nothing and the larger holding is the better guess. **Alphabetical** last, which is arbitrary
     * and admits it: what it buys is that the answer cannot depend on the order rows came back from
     * SQLite in, so a figure never changes between two identical reads.
     */
    fun baseCurrency(accounts: List<Account>, fallback: String = "USD"): String {
        val counted = accounts.filter { it.includeInPicture && !it.closed }
        if (counted.isEmpty()) return fallback
        return counted
            .groupBy { it.currency.uppercase() }
            .entries
            .sortedWith(
                compareByDescending<Map.Entry<String, List<Account>>> { it.value.size }
                    .thenByDescending { entry -> entry.value.sumOf { abs(it.balance.currentCents) } }
                    .thenBy { it.key }
            )
            .first().key
    }

    /**
     * Fold accounts into a net position.
     *
     * Closed accounts and ones you excluded are skipped; a closed account with a zero balance would
     * not change the arithmetic, but one closed with a balance still on it (a card paid off and shut
     * last month that the institution keeps reporting) very much would.
     *
     * ## Unlike currencies are refused, not converted
     *
     * Accounts not in [base] are left out and named in [NetPosition.excludedCurrencies]. This was
     * once worse than a limitation: the figures were summed across currencies with no conversion and
     * no guard, so one euro account made the headline number meaningless with no visible symptom —
     * the worst kind of wrong, because nothing about the screen looked different.
     *
     * Converting instead would mean a live exchange rate, which means a third host to talk to, which
     * would break the promise this module is built around — and would put a number on screen whose
     * accuracy depends on a rate nobody chose. Leaving them out and saying so is the smaller and more
     * honest cost, and a household with genuinely mixed currencies can open each account's own page,
     * where the figure is in its own currency and correct.
     */
    fun netPosition(
        accounts: List<Account>,
        base: String = baseCurrency(accounts)
    ): NetPosition {
        var assets = 0L
        var liabilities = 0L
        var cash = 0L
        val excluded = mutableSetOf<String>()
        accounts.asSequence()
            .filter { it.includeInPicture && !it.closed }
            .filter { account ->
                val same = account.currency.equals(base, ignoreCase = true)
                if (!same) excluded += account.currency.uppercase()
                same
            }
            .forEach { account ->
                val magnitude = account.balance.spendable(account.kind)
                if (account.kind.owed) {
                    // A card in credit — you overpaid it, or a refund landed after the balance
                    // cleared — is reported as a negative balance. That is genuinely an asset, and
                    // adding it to the debt pile as a negative would understate both sides.
                    if (magnitude >= 0L) liabilities += magnitude else assets += -magnitude
                } else {
                    assets += magnitude
                    if (account.kind == AccountKind.DEPOSITORY) cash += magnitude
                }
            }
        return NetPosition(
            assetsCents = assets,
            liabilitiesCents = liabilities,
            cashCents = cash,
            currency = base.uppercase(),
            excludedCurrencies = excluded
        )
    }

    /**
     * The transactions belonging to accounts in [base] — everything a cross-account roll-up may add.
     *
     * The same refusal as [netPosition], applied one layer along: a transaction has no currency of
     * its own, it inherits its account's, so a month's spending summed over mixed accounts is wrong
     * in exactly the same invisible way a net worth was.
     */
    fun inBaseCurrency(
        transactions: List<Transaction>,
        accounts: List<Account>,
        base: String
    ): List<Transaction> {
        val allowed = accounts.asSequence()
            .filter { it.currency.equals(base, ignoreCase = true) }
            .mapTo(mutableSetOf()) { it.id }
        // A transaction whose account is not held at all (a connection removed mid-refresh) is kept:
        // dropping it would silently shrink a month's totals, and it was denominated in the base
        // currency far more often than not.
        return transactions.filter { it.accountId in allowed || accounts.none { a -> a.id == it.accountId } }
    }

    /** The accounts a payment could plausibly come out of, in the order to offer them. */
    fun fundingAccounts(accounts: List<Account>): List<Account> =
        accounts.filter { !it.closed && it.kind == AccountKind.DEPOSITORY }
            .sortedByDescending { it.balance.spendable(it.kind) }
}

/**
 * The kinds of account the picture distinguishes, and the *only* thing the app needs from a kind:
 * which way the money points.
 *
 * Providers report far more detail than this — Plaid alone has some forty subtypes, separating a
 * money market from a CD from a cash-management account — and none of that difference changes a
 * single figure on any screen here. So the subtype is carried through as a label on the account and
 * the arithmetic keys off these five.
 *
 * [key] is a string rather than an ordinal for the reason the rest of the suite gives: a kind can be
 * added or reordered without rewriting anybody's rows, and an unrecognised one from a future version
 * resolves to [OTHER] rather than throwing.
 */
enum class AccountKind(val key: String, val label: String, val owed: Boolean) {
    /** Checking, savings, money market, cash management. Money you have. */
    DEPOSITORY("depository", "Cash", owed = false),

    /** Credit cards and lines of credit. Money you owe, revolving. */
    CREDIT("credit", "Credit", owed = true),

    /** Mortgages, auto loans, student loans. Money you owe, amortising. */
    LOAN("loan", "Loans", owed = true),

    /** Brokerage, retirement, HSA. Money you have, but not money you can spend on Tuesday. */
    INVESTMENT("investment", "Investments", owed = false),

    /** Anything the provider reported that doesn't map. Counted as an asset, shown as itself. */
    OTHER("other", "Other", owed = false);

    companion object {
        fun fromKey(key: String?): AccountKind =
            entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: OTHER

        /**
         * Map a provider's own account type onto a kind.
         *
         * Deliberately forgiving, and deliberately not clever: Plaid's `type` field already uses
         * these exact five words for four of them, Mercury reports only deposit accounts, and
         * anything unrecognised becomes [OTHER] and is counted as an asset. Guessing that an unknown
         * type is a debt would be the expensive mistake — it would quietly reduce the household's
         * net worth by a number nobody could trace.
         */
        fun fromProviderType(type: String?, subtype: String? = null): AccountKind {
            val t = type?.trim()?.lowercase().orEmpty()
            val s = subtype?.trim()?.lowercase().orEmpty()
            return when {
                t == "depository" || t == "cash" || s == "checking" || s == "savings" -> DEPOSITORY
                t == "credit" || s == "credit card" || s == "creditcard" -> CREDIT
                t == "loan" || s == "mortgage" || s == "student" || s == "auto" -> LOAN
                t == "investment" || t == "brokerage" -> INVESTMENT
                else -> OTHER
            }
        }
    }
}
