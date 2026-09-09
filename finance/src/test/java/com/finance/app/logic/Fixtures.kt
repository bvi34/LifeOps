package com.finance.app.logic

import java.time.LocalDate

/**
 * Builders for the tests below, so a test reads as the thing it is asserting rather than as eleven
 * lines of constructor.
 *
 * Amounts are given in **dollars** here and converted, because every one of these tests is checking
 * a rule somebody would state in dollars ("a $15 subscription", "short by $340") and a test that
 * spells out `1549` is a test whose intent has to be decoded before it can be checked.
 */

internal fun dollars(amount: Double): Long = Endpoints.dollarsToCents(amount)!!

internal fun txn(
    date: String,
    amount: Double,
    description: String = "Something",
    accountId: String = "acct",
    merchant: String? = null,
    category: Category = Category.OTHER,
    pending: Boolean = false,
    transfer: Boolean = false,
    id: String = "$accountId:$date:$amount:$description"
) = Transaction(
    id = id,
    accountId = accountId,
    providerTransactionId = id,
    date = LocalDate.parse(date),
    amountCents = dollars(amount),
    description = description,
    merchant = merchant,
    category = category,
    pending = pending,
    transfer = transfer
)

internal fun account(
    id: String = "acct",
    kind: AccountKind = AccountKind.DEPOSITORY,
    current: Double = 0.0,
    available: Double? = null,
    limit: Double? = null,
    name: String = "Checking",
    mask: String? = null,
    closed: Boolean = false,
    included: Boolean = true
) = Accounts.Account(
    id = id,
    connectionId = "conn",
    providerAccountId = "p-$id",
    name = name,
    officialName = null,
    mask = mask,
    kind = kind,
    balance = Accounts.Balance(
        currentCents = dollars(current),
        availableCents = available?.let { dollars(it) },
        limitCents = limit?.let { dollars(it) }
    ),
    includeInPicture = included,
    closed = closed
)

internal fun bill(
    payee: String = "Payee",
    due: String,
    amount: Double,
    source: Bills.Source = Bills.Source.STATEMENT,
    accountId: String = "acct",
    minimum: Double? = null,
    merchantKey: String? = Merchants.key(payee),
    paidOn: String? = null,
    publishToWeek: Boolean = true,
    recurrenceMonths: Int? = null,
    id: String = "$source:$payee:$due"
) = Bills.Bill(
    id = id,
    accountId = accountId,
    payee = payee,
    dueDate = LocalDate.parse(due),
    amountCents = dollars(amount),
    minimumCents = minimum?.let { dollars(it) },
    source = source,
    merchantKey = merchantKey,
    paidOn = paidOn?.let { LocalDate.parse(it) },
    publishToWeek = publishToWeek,
    recurrenceMonths = recurrenceMonths
)

/** A run of charges from one payee, one period apart, for building recurring series in tests. */
internal fun run(
    first: String,
    count: Int,
    amount: Double,
    stepDays: Long,
    description: String,
    accountId: String = "acct",
    category: Category = Category.OTHER
): List<Transaction> {
    var date = LocalDate.parse(first)
    return (0 until count).map { index ->
        val row = txn(
            date = date.toString(),
            amount = amount,
            description = description,
            accountId = accountId,
            category = category,
            id = "$accountId:$description:$index"
        )
        date = date.plusDays(stepDays)
        row
    }
}

/** The same, stepping by calendar months so the day-of-month is held — what a real biller does. */
internal fun monthlyRun(
    first: String,
    count: Int,
    amount: Double,
    description: String,
    accountId: String = "acct",
    category: Category = Category.OTHER
): List<Transaction> {
    val start = LocalDate.parse(first)
    return (0 until count).map { index ->
        txn(
            date = start.plusMonths(index.toLong()).toString(),
            amount = amount,
            description = description,
            accountId = accountId,
            category = category,
            id = "$accountId:$description:$index"
        )
    }
}
