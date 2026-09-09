package com.finance.app.logic

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * Pure parsing of Mercury's API responses. No network and no Android; the HTTP hop lives in
 * `data/net/MercuryClient`.
 *
 * Mercury is the easier of the two providers by some distance, and the reasons are worth knowing
 * because they shape what this app can offer for a Mercury account versus a Plaid one:
 *
 * - **There is no aggregator in the middle.** A Mercury API token is issued by Mercury to its own
 *   customer and read straight from Mercury. Nothing to link, nothing to re-authenticate every
 *   ninety days, no item to go into `ITEM_LOGIN_REQUIRED`.
 * - **Read-only is a real setting.** Mercury issues tokens with read-only scope, which the
 *   Connections screen tells you to use. Plaid's read-only-ness is a property of which products
 *   were requested; Mercury's is a property of the token, which is stronger.
 * - **The sign convention already matches.** Mercury reports a debit as negative, which is what this
 *   app stores. There is no flip in this file, and its absence is deliberate rather than an
 *   oversight — see [Transaction].
 *
 * What Mercury does **not** have is liabilities. It is a business bank with deposit accounts, so
 * every bill from a Mercury connection is a predicted one; there is no statement to read a due date
 * off. That is a real limitation and the Due screen says which bills came from where.
 */
object MercuryJson {

    private val gson = Gson()

    /** A Mercury error body, parsed down to something worth showing. Null when it isn't one. */
    fun error(json: String): String? {
        val body = runCatching { gson.fromJson(json, ErrorBody::class.java) }.getOrNull() ?: return null
        return body.errors?.message?.takeIf { it.isNotBlank() }
            ?: body.error?.takeIf { it.isNotBlank() }
            ?: body.message?.takeIf { it.isNotBlank() }
    }

    /**
     * `GET /accounts` → the accounts on the token.
     *
     * Mercury returns a full account number on every row and this app has no use for one, so
     * [Masking.truncate] reduces it to four digits here — at the parser, before the value can be
     * returned to anything that might write it down. The routing number is read and discarded
     * entirely; it is not a field on [Accounts.Account] and there is nowhere for it to go.
     */
    fun accounts(json: String, connectionId: String): List<Accounts.Account> {
        val body = try {
            gson.fromJson(json, AccountsBody::class.java)
        } catch (_: JsonSyntaxException) {
            return emptyList()
        } ?: return emptyList()

        return body.accounts.orEmpty().mapNotNull { row ->
            val providerId = row.id?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val current = Endpoints.dollarsToCents(row.currentBalance) ?: 0L
            Accounts.Account(
                id = "$connectionId:$providerId",
                connectionId = connectionId,
                providerAccountId = providerId,
                name = row.nickname?.takeIf { it.isNotBlank() }
                    ?: row.name?.takeIf { it.isNotBlank() }
                    ?: "Mercury account",
                officialName = row.name?.takeIf { it.isNotBlank() },
                mask = Masking.truncate(row.accountNumber),
                // Mercury's `kind` is "checking", "savings" or "creditCard" (its charge card). Its
                // `type` field is the product, not the shape of the money, so the kind is what maps.
                kind = AccountKind.fromProviderType(
                    type = if (row.kind.equals("creditCard", ignoreCase = true)) "credit" else "depository",
                    subtype = row.kind
                ),
                balance = Accounts.Balance(
                    currentCents = current,
                    availableCents = Endpoints.dollarsToCents(row.availableBalance)
                ),
                closed = row.status?.equals("archived", ignoreCase = true) == true
            )
        }
    }

    /**
     * `GET /account/{id}/transactions` → that account's activity.
     *
     * [accountId] is ours, passed in, because Mercury's transaction rows carry no account id at all —
     * the account is in the URL. That is a small API design difference with a real consequence: a
     * Mercury refresh has to walk accounts one at a time, where Plaid's sync returns everything at
     * once and sorts itself out by `account_id`.
     */
    fun transactions(json: String, accountId: String, zone: ZoneId = ZoneId.systemDefault()): List<Transaction> {
        val body = try {
            gson.fromJson(json, TransactionsBody::class.java)
        } catch (_: JsonSyntaxException) {
            return emptyList()
        } ?: return emptyList()

        return body.transactions.orEmpty().mapNotNull { row ->
            val providerId = row.id?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val cents = Endpoints.dollarsToCents(row.amount) ?: return@mapNotNull null
            // A failed transaction is money that never moved. Mercury keeps the row so you can see
            // what happened; counting it would take money out of a balance that still has it.
            if (row.status.equals("failed", ignoreCase = true) || row.failedAt != null) return@mapNotNull null

            val posted = timestamp(row.postedAt, zone)
            val created = timestamp(row.createdAt, zone)
            val date = posted ?: created ?: return@mapNotNull null

            val description = Merchants.clean(
                row.counterpartyName?.takeIf { it.isNotBlank() }
                    ?: row.bankDescription?.takeIf { it.isNotBlank() }
                    ?: row.externalMemo.orEmpty()
            )

            Transaction(
                id = "$accountId:$providerId",
                accountId = accountId,
                providerTransactionId = providerId,
                date = date,
                // No sign flip: Mercury already reports a debit as negative. See the class note.
                amountCents = cents,
                description = description.ifBlank { "Transaction" },
                merchant = row.counterpartyName?.takeIf { it.isNotBlank() }?.let { Merchants.clean(it) },
                category = Categories.fromMercuryDescription(description, row.kind),
                // Not posted yet is pending, whatever Mercury calls the state it is in.
                pending = posted == null,
                transfer = row.kind.equals("internalTransfer", ignoreCase = true)
            )
        }
    }

    /**
     * Mercury timestamps are ISO-8601 with an offset (`2023-01-05T14:22:03.123Z`).
     *
     * Converted to a local date through [zone] rather than taken as a UTC date, because a payment
     * made at 8pm Central is the 5th to the person who made it and the 6th in UTC — and this app's
     * whole job is to agree with what somebody remembers doing.
     */
    private fun timestamp(raw: String?, zone: ZoneId): LocalDate? {
        val text = raw?.takeIf { it.isNotBlank() } ?: return null
        runCatching { return OffsetDateTime.parse(text).atZoneSameInstant(zone).toLocalDate() }
        // Some fields come back as a bare date. Try that before giving up.
        return runCatching { LocalDate.parse(text.take(10)) }.getOrNull()
    }

    // --- wire shapes -----------------------------------------------------------------------------
    //
    // camelCase, exactly as Mercury sends it. Nullable throughout for the reason PlaidJson gives: a
    // missing field should drop a row, never throw out of a parser.

    private data class ErrorBody(val errors: ErrorDetail?, val error: String?, val message: String?)
    private data class ErrorDetail(val message: String?)

    private data class AccountsBody(val accounts: List<AccountRow>?)

    private data class AccountRow(
        val id: String?,
        val name: String?,
        val nickname: String?,
        val accountNumber: String?,
        val routingNumber: String?,
        val kind: String?,
        val status: String?,
        val currentBalance: Double?,
        val availableBalance: Double?
    )

    private data class TransactionsBody(val total: Int?, val transactions: List<TransactionRow>?)

    private data class TransactionRow(
        val id: String?,
        val amount: Double?,
        val bankDescription: String?,
        val counterpartyName: String?,
        val externalMemo: String?,
        val kind: String?,
        val status: String?,
        val createdAt: String?,
        val postedAt: String?,
        val failedAt: String?
    )
}
