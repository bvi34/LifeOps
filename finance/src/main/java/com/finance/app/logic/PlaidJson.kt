package com.finance.app.logic

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.annotations.SerializedName
import java.time.LocalDate

/**
 * Pure parsing of the four Plaid responses this app reads. No network and no Android; the HTTP hop
 * lives in `data/net/PlaidClient`.
 *
 * The split is the same one Maintenance uses for vPIC and Health for RxNorm, and it earns its keep
 * more here than in either: Plaid's payloads are large, deeply nested and full of fields with real
 * consequences if misread, and the way to be sure they are read correctly is to run the parser over
 * captured payloads on a JVM rather than to inspect a screen on a phone.
 *
 * ## The three conversions that happen here and nowhere else
 *
 * 1. **Dollars to cents**, through [Endpoints.dollarsToCents]. Plaid sends `5.4`; we store `540`.
 * 2. **The transaction sign flips.** Plaid is positive-when-money-leaves; this app is
 *    negative-when-money-leaves (see [Transaction]). The negation is on line one of
 *    [transaction], and it is the only place in the module it happens.
 * 3. **Account numbers are truncated to four digits** by [Masking.truncate] before anything is
 *    returned, so a full number never reaches the caller and cannot reach the database.
 *
 * ## Errors
 *
 * Plaid returns errors as a 400-series body with a stable shape, and one of them is not really an
 * error at all: `ITEM_LOGIN_REQUIRED` means the bank wants the person to log in again, which is a
 * normal event every few months and needs a *prompt*, not a failure toast. [PlaidError.reauth]
 * distinguishes it so the connection can be shown as needing attention rather than as broken.
 */
object PlaidJson {

    private val gson = Gson()

    /** A Plaid error body, parsed. Null when [json] is not one. */
    fun error(json: String): PlaidError? {
        val body = runCatching { gson.fromJson(json, ErrorBody::class.java) }.getOrNull() ?: return null
        val code = body.error_code ?: return null
        return PlaidError(
            code = code,
            type = body.error_type.orEmpty(),
            // `display_message` is the one Plaid writes for end users; `error_message` is for
            // developers and says things like "the provided access token is malformed". Prefer
            // theirs, fall back to ours, never show a raw code alone.
            message = body.display_message?.takeIf { it.isNotBlank() }
                ?: body.error_message?.takeIf { it.isNotBlank() }
                ?: code
        )
    }

    /** `/link/token/create` → the token the Hosted Link page is opened with. */
    fun linkToken(json: String): String? =
        runCatching { gson.fromJson(json, LinkTokenBody::class.java) }.getOrNull()
            ?.link_token?.takeIf { it.isNotBlank() }

    /** `/item/public_token/exchange` → the long-lived access token, and the item it belongs to. */
    fun exchange(json: String): Exchange? {
        val body = runCatching { gson.fromJson(json, ExchangeBody::class.java) }.getOrNull() ?: return null
        val token = body.access_token?.takeIf { it.isNotBlank() } ?: return null
        return Exchange(accessToken = token, itemId = body.item_id.orEmpty())
    }

    /** `/institutions/get_by_id` → what to call the connection. Null when Plaid didn't say. */
    fun institutionName(json: String): String? =
        runCatching { gson.fromJson(json, InstitutionBody::class.java) }.getOrNull()
            ?.institution?.name?.takeIf { it.isNotBlank() }

    /**
     * `/accounts/balance/get` (and the `accounts` array on any other response) → accounts.
     *
     * [connectionId] is ours, not Plaid's: an account belongs to the connection it arrived through,
     * and the id has to be stable across refreshes so balances land on the same row.
     *
     * The id minted here is `connectionId:providerAccountId`, deliberately derived rather than
     * random. A refresh that generated fresh ids would orphan every transaction and every bill
     * pointing at the old ones; deriving it means "the same account" is the same row by construction
     * rather than by a matching pass that could get it wrong.
     */
    fun accounts(json: String, connectionId: String): List<Accounts.Account> {
        val body = try {
            gson.fromJson(json, AccountsBody::class.java)
        } catch (_: JsonSyntaxException) {
            return emptyList()
        } ?: return emptyList()

        return body.accounts.orEmpty().mapNotNull { row ->
            val providerId = row.account_id?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val kind = AccountKind.fromProviderType(row.type, row.subtype)
            val balances = row.balances
            Accounts.Account(
                id = "$connectionId:$providerId",
                connectionId = connectionId,
                providerAccountId = providerId,
                name = row.name?.takeIf { it.isNotBlank() } ?: row.official_name ?: "Account",
                officialName = row.official_name?.takeIf { it.isNotBlank() },
                mask = Masking.truncate(row.mask),
                kind = kind,
                balance = Accounts.Balance(
                    currentCents = Endpoints.dollarsToCents(balances?.current) ?: 0L,
                    availableCents = Endpoints.dollarsToCents(balances?.available),
                    limitCents = Endpoints.dollarsToCents(balances?.limit)
                ),
                currency = balances?.iso_currency_code?.takeIf { it.isNotBlank() } ?: "USD"
            )
        }
    }

    /**
     * `/transactions/sync` → what changed since the cursor.
     *
     * Sync rather than `/transactions/get` because the cursor makes a refresh cheap and, more
     * importantly, because it reports **removals**. Banks do un-post transactions — a pending charge
     * that was abandoned, a duplicate the merchant reversed — and an app built on `get` never hears
     * about it and shows a phantom charge forever.
     */
    fun sync(json: String, accountIdFor: (String) -> String?): Sync? {
        val body = try {
            gson.fromJson(json, SyncBody::class.java)
        } catch (_: JsonSyntaxException) {
            return null
        } ?: return null

        fun rows(list: List<TransactionRow>?): List<Transaction> =
            list.orEmpty().mapNotNull { transaction(it, accountIdFor) }

        return Sync(
            added = rows(body.added),
            modified = rows(body.modified),
            removedProviderIds = body.removed.orEmpty().mapNotNull { it.transaction_id },
            nextCursor = body.next_cursor.orEmpty(),
            hasMore = body.has_more == true
        )
    }

    private fun transaction(row: TransactionRow, accountIdFor: (String) -> String?): Transaction? {
        val providerId = row.transaction_id?.takeIf { it.isNotBlank() } ?: return null
        val providerAccountId = row.account_id?.takeIf { it.isNotBlank() } ?: return null
        val accountId = accountIdFor(providerAccountId) ?: return null
        val cents = Endpoints.dollarsToCents(row.amount) ?: return null
        // Posted date where there is one; Plaid's `date` is the posted date and `authorized_date`
        // the earlier one, so this reads the way the class note says it does.
        val date = parseDate(row.date) ?: parseDate(row.authorized_date) ?: return null

        val description = Merchants.clean(row.name.orEmpty().ifBlank { row.merchant_name.orEmpty() })
        val category = Categories.fromPlaid(
            row.personal_finance_category?.primary,
            row.personal_finance_category?.detailed
        )

        return Transaction(
            id = "$accountId:$providerId",
            accountId = accountId,
            providerTransactionId = providerId,
            date = date,
            // The sign flip. Plaid: positive is money out. Us: negative is money out.
            amountCents = -cents,
            description = description.ifBlank { "Transaction" },
            merchant = row.merchant_name?.takeIf { it.isNotBlank() }?.let { Merchants.clean(it) },
            category = category,
            pending = row.pending == true,
            transfer = category == Category.TRANSFER
        )
    }

    /**
     * `/liabilities/get` → the bills the institution itself put a date on.
     *
     * This is the single most valuable call in the module. Everything else in the app can be derived
     * from transactions with more or less confidence; a credit card's `next_payment_due_date` is the
     * biller telling you when it is due, and there is no substitute for it.
     *
     * The three liability shapes are read into one list because they mean the same thing to a
     * household — money owed on a date — and differ only in which field carries the amount:
     * a card has a statement balance, a mortgage has a monthly payment, a student loan has a minimum.
     */
    fun liabilities(json: String, accountIdFor: (String) -> String?, today: LocalDate): List<Bills.Bill> {
        val body = try {
            gson.fromJson(json, LiabilitiesBody::class.java)
        } catch (_: JsonSyntaxException) {
            return emptyList()
        } ?: return emptyList()

        val names = body.accounts.orEmpty()
            .mapNotNull { row -> row.account_id?.let { it to (row.name ?: "Account") } }
            .toMap()
        val liabilities = body.liabilities ?: return emptyList()
        val bills = mutableListOf<Bills.Bill>()

        fun add(
            providerAccountId: String?,
            dueRaw: String?,
            amountCents: Long?,
            minimumCents: Long?,
            category: Category
        ) {
            val providerId = providerAccountId?.takeIf { it.isNotBlank() } ?: return
            val accountId = accountIdFor(providerId) ?: return
            val due = parseDate(dueRaw) ?: return
            // Plaid keeps reporting the last cycle's due date for a while after it passes. A due date
            // more than a cycle old is a stale field rather than a bill somebody is months late on,
            // and publishing it as overdue would be the app crying wolf on its most trusted source.
            if (due.isBefore(today.minusDays(STALE_DUE_DAYS))) return
            val amount = amountCents?.takeIf { it > 0L } ?: minimumCents?.takeIf { it > 0L } ?: return
            bills += Bills.Bill(
                id = "statement:$accountId:$due",
                accountId = accountId,
                payee = names[providerId] ?: "Account",
                dueDate = due,
                amountCents = amount,
                minimumCents = minimumCents?.takeIf { it > 0L },
                source = Bills.Source.STATEMENT,
                category = category
            )
        }

        liabilities.credit.orEmpty().forEach { card ->
            add(
                providerAccountId = card.account_id,
                dueRaw = card.next_payment_due_date,
                // The statement balance, not the minimum — see the note on Bills.Bill.minimumCents.
                amountCents = Endpoints.dollarsToCents(card.last_statement_balance),
                minimumCents = Endpoints.dollarsToCents(card.minimum_payment_amount),
                category = Category.DEBT
            )
        }
        liabilities.mortgage.orEmpty().forEach { loan ->
            add(
                providerAccountId = loan.account_id,
                dueRaw = loan.next_payment_due_date,
                amountCents = Endpoints.dollarsToCents(loan.next_monthly_payment),
                minimumCents = null,
                category = Category.HOUSING
            )
        }
        liabilities.student.orEmpty().forEach { loan ->
            add(
                providerAccountId = loan.account_id,
                dueRaw = loan.next_payment_due_date,
                amountCents = Endpoints.dollarsToCents(loan.minimum_payment_amount),
                minimumCents = Endpoints.dollarsToCents(loan.minimum_payment_amount),
                category = Category.DEBT
            )
        }
        return bills.sortedBy { it.dueDate }
    }

    /** Plaid dates are `YYYY-MM-DD`. A malformed one is dropped rather than defaulted to today. */
    private fun parseDate(raw: String?): LocalDate? =
        raw?.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }

    /** How far past a `next_payment_due_date` may be before it is read as stale rather than late. */
    private const val STALE_DUE_DAYS = 45L

    // --- results ---------------------------------------------------------------------------------

    data class Exchange(val accessToken: String, val itemId: String)

    data class Sync(
        val added: List<Transaction>,
        val modified: List<Transaction>,
        val removedProviderIds: List<String>,
        val nextCursor: String,
        val hasMore: Boolean
    )

    /**
     * A Plaid error, and whether it is the one that means "ask the person to log in again".
     *
     * [reauth] is the distinction the connections screen is built around: an item in
     * `ITEM_LOGIN_REQUIRED` is not broken and its data is not wrong — it is simply not being updated
     * until somebody re-authenticates, which happens routinely and is the bank's decision, not ours.
     */
    data class PlaidError(val code: String, val type: String, val message: String) {
        val reauth: Boolean get() = code == "ITEM_LOGIN_REQUIRED" || code == "PENDING_EXPIRATION"

        /** A rate limit or a provider outage: worth retrying later, not worth telling anyone off about. */
        val transient: Boolean
            get() = type == "RATE_LIMIT_EXCEEDED" || code == "INSTITUTION_DOWN" ||
                code == "INSTITUTION_NOT_RESPONDING" || code == "PRODUCT_NOT_READY"
    }

    // --- wire shapes -----------------------------------------------------------------------------
    //
    // Snake case, exactly as Plaid sends it, so a reader can compare these against Plaid's own docs
    // without a mapping in their head. Everything is nullable because a field this app requires and
    // Plaid omits should produce a dropped row, never an exception thrown out of a parser.

    private data class ErrorBody(
        val error_type: String?,
        val error_code: String?,
        val error_message: String?,
        val display_message: String?
    )

    private data class LinkTokenBody(val link_token: String?)

    private data class ExchangeBody(val access_token: String?, val item_id: String?)

    private data class InstitutionBody(val institution: InstitutionRow?)
    private data class InstitutionRow(val name: String?)

    private data class AccountsBody(val accounts: List<AccountRow>?)

    private data class AccountRow(
        val account_id: String?,
        val name: String?,
        val official_name: String?,
        val mask: String?,
        val type: String?,
        val subtype: String?,
        val balances: BalanceRow?
    )

    private data class BalanceRow(
        val available: Double?,
        val current: Double?,
        val limit: Double?,
        val iso_currency_code: String?
    )

    private data class SyncBody(
        val added: List<TransactionRow>?,
        val modified: List<TransactionRow>?,
        val removed: List<RemovedRow>?,
        val next_cursor: String?,
        val has_more: Boolean?
    )

    private data class RemovedRow(val transaction_id: String?)

    private data class TransactionRow(
        val transaction_id: String?,
        val account_id: String?,
        val amount: Double?,
        val date: String?,
        val authorized_date: String?,
        val name: String?,
        val merchant_name: String?,
        val pending: Boolean?,
        @SerializedName("personal_finance_category")
        val personal_finance_category: FinanceCategoryRow?
    )

    private data class FinanceCategoryRow(val primary: String?, val detailed: String?)

    private data class LiabilitiesBody(
        val accounts: List<AccountRow>?,
        val liabilities: LiabilityGroups?
    )

    private data class LiabilityGroups(
        val credit: List<CreditRow>?,
        val mortgage: List<MortgageRow>?,
        val student: List<StudentRow>?
    )

    private data class CreditRow(
        val account_id: String?,
        val last_statement_balance: Double?,
        val minimum_payment_amount: Double?,
        val next_payment_due_date: String?,
        val is_overdue: Boolean?
    )

    private data class MortgageRow(
        val account_id: String?,
        val next_monthly_payment: Double?,
        val next_payment_due_date: String?
    )

    private data class StudentRow(
        val account_id: String?,
        val minimum_payment_amount: Double?,
        val next_payment_due_date: String?
    )
}
