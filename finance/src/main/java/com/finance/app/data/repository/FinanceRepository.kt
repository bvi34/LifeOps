package com.finance.app.data.repository

import com.finance.app.data.db.dao.FinanceDao
import com.finance.app.data.db.entities.AccountEntity
import com.finance.app.data.db.entities.BillEntity
import com.finance.app.data.db.entities.ConnectionEntity
import com.finance.app.data.db.entities.TransactionEntity
import com.finance.app.logic.AccountKind
import com.finance.app.logic.Accounts
import com.finance.app.logic.BillSnapshot
import com.finance.app.logic.BillStore
import com.finance.app.logic.BillTasks
import com.finance.app.logic.Bills
import com.finance.app.logic.Category
import com.finance.app.logic.Connection
import com.finance.app.logic.Merchants
import com.finance.app.logic.Provider
import com.finance.app.logic.Recurring
import com.finance.app.logic.Transaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.LocalDate

/**
 * The one place that knows both the database's shape and the logic layer's.
 *
 * Everything above this line reasons in [Accounts.Account], [Transaction] and [Bills.Bill] and
 * knows nothing about Room; everything below is entities and epoch days. The mapping is boring on
 * purpose and lives in one file so a column added to an entity has exactly one place to be wired
 * through.
 *
 * It also implements [BillStore], which is how the LifeOps publishing round reads and writes bills
 * without knowing what a database is.
 */
class FinanceRepository(private val dao: FinanceDao) : BillStore {

    // --- reads ------------------------------------------------------------------------------------

    fun observeConnections(): Flow<List<Connection>> =
        dao.observeConnections().map { rows -> rows.map { it.toModel() } }

    fun observeAccounts(): Flow<List<Accounts.Account>> =
        dao.observeAccounts().map { rows -> rows.map { it.toModel() } }

    fun observeBills(): Flow<List<Bills.Bill>> =
        dao.observeBills().map { rows -> rows.map { it.toModel() } }

    fun observeTransactionsBetween(from: LocalDate, to: LocalDate): Flow<List<Transaction>> =
        dao.observeBetween(from.toEpochDay(), to.toEpochDay()).map { rows -> rows.map { it.toModel() } }

    fun observeAccountActivity(accountId: String, limit: Int = 200): Flow<List<Transaction>> =
        dao.observeForAccount(accountId, limit).map { rows -> rows.map { it.toModel() } }

    /**
     * Accounts and the bills against them, together.
     *
     * A [combine] rather than two collections in the ViewModel, because a screen that renders a bill
     * before the account it names has arrived shows "Account" for a heartbeat and then the real
     * name, and that flicker is the kind of thing nobody files a bug about and everybody notices.
     */
    fun observePicture(from: LocalDate, to: LocalDate): Flow<Picture> =
        combine(
            dao.observeAccounts(),
            dao.observeBills(),
            dao.observeBetween(from.toEpochDay(), to.toEpochDay())
        ) { accounts, bills, transactions ->
            Picture(
                accounts = accounts.map { it.toModel() },
                bills = bills.map { it.toModel() },
                transactions = transactions.map { it.toModel() }
            )
        }

    /** Everything a summary screen needs, arriving in one piece. */
    data class Picture(
        val accounts: List<Accounts.Account>,
        val bills: List<Bills.Bill>,
        val transactions: List<Transaction>
    ) {
        fun netPosition(): Accounts.NetPosition = Accounts.netPosition(accounts)
        fun accountName(accountId: String): String? =
            accounts.firstOrNull { it.id == accountId }?.displayName()
    }

    suspend fun connections(): List<Connection> = dao.connections().map { it.toModel() }

    suspend fun connection(id: String): Connection? = dao.connection(id)?.toModel()

    suspend fun accounts(): List<Accounts.Account> = dao.accounts().map { it.toModel() }

    suspend fun accountsFor(connectionId: String): List<Accounts.Account> =
        dao.accountsFor(connectionId).map { it.toModel() }

    suspend fun transactionsSince(from: LocalDate): List<Transaction> =
        dao.since(from.toEpochDay()).map { it.toModel() }

    suspend fun bills(): List<Bills.Bill> = dao.bills().map { it.toModel() }

    /**
     * The day to ask a provider for transactions from.
     *
     * A fortnight of overlap behind the newest row we hold, rather than the newest row itself.
     * Transactions arrive late — a card charge can post four days after it happened and lands
     * *behind* rows already stored — so asking from the high-water mark silently loses them. The
     * overlap re-fetches rows we already have, which upserts harmlessly, and that is much the
     * cheaper mistake.
     */
    suspend fun syncFrom(accountId: String, firstRun: LocalDate): LocalDate {
        val latest = dao.latestDay(accountId) ?: return firstRun
        return LocalDate.ofEpochDay(latest).minusDays(OVERLAP_DAYS).coerceAtLeast(firstRun)
    }

    // --- writes -----------------------------------------------------------------------------------

    suspend fun upsertConnection(connection: Connection) = dao.upsertConnection(connection.toEntity())

    suspend fun markSynced(connectionId: String, at: Long) = dao.markSynced(connectionId, at)

    suspend fun markProblem(connectionId: String, needsReauth: Boolean, error: String?) =
        dao.markProblem(connectionId, needsReauth, error)

    suspend fun deleteConnection(connectionId: String) = dao.deleteConnection(connectionId)

    suspend fun setAccountIncluded(accountId: String, include: Boolean) =
        dao.setIncluded(accountId, include)

    suspend fun setBillPublishToWeek(billId: String, publish: Boolean) =
        dao.setPublishToWeek(billId, publish)

    /**
     * Add (or correct) a typed bill.
     *
     * The id is derived from the account, the payee and the date ([Bills.manualId]), which is what
     * lets somebody re-enter the same bill without producing a second copy of it — and what lets
     * [Bills.rollForward] mint next month's occurrence idempotently.
     *
     * Editing a repeating bill is deliberately *not* a separate operation: entering it again with a
     * new amount overwrites that occurrence, and the ones ahead are re-minted from it on the next
     * rebuild. Rent going up is a new figure from a date, not a retrospective correction to the
     * months already paid at the old one.
     */
    suspend fun addManualBill(
        accountId: String,
        payee: String,
        due: LocalDate,
        amountCents: Long,
        recurrenceMonths: Int?,
        category: Category = Category.OTHER,
        publishToWeek: Boolean = true
    ): Bills.Bill {
        val merchantKey = Merchants.key(payee)
        val bill = Bills.Bill(
            id = Bills.manualId(accountId, merchantKey, due),
            accountId = accountId,
            payee = payee.trim(),
            dueDate = due,
            amountCents = amountCents,
            source = Bills.Source.MANUAL,
            category = category,
            merchantKey = merchantKey,
            publishToWeek = publishToWeek,
            recurrenceMonths = recurrenceMonths
        )
        // Carry any link the same occurrence already had, so re-entering a bill that is already on
        // the week corrects it in place rather than orphaning the task.
        val previous = dao.bill(bill.id)
        dao.upsertBills(
            listOf(
                bill.toEntity(
                    lifeOpsTaskId = previous?.lifeOpsTaskId,
                    publishedDueEpochDay = previous?.publishedDueEpochDay
                )
            )
        )
        return bill
    }

    /**
     * Stop a typed bill, and hand back the LifeOps task ids that need withdrawing.
     *
     * Two halves, because only one of them belongs to this class. Removing the rows is a database
     * question; taking their tasks off somebody's week is the publisher's, and it has to happen
     * behind the same gate a round runs under — so the ids come back rather than being acted on
     * here. The caller pairs them (see the Due screen's view model).
     *
     * Unpaid occurrences only. A paid one is a record of money that actually left, and deleting it
     * would quietly rewrite the household's own history.
     */
    suspend fun deleteManualSeries(accountId: String, merchantKey: String): List<String> {
        val doomed = dao.manualSeries(accountId, merchantKey)
        dao.deleteManualSeries(accountId, merchantKey)
        return doomed.mapNotNull { it.lifeOpsTaskId }
    }

    suspend fun upsertBills(bills: List<Bills.Bill>) = dao.upsertBills(bills.map { it.toEntity() })

    /**
     * Write one refresh's worth of provider data.
     *
     * Accounts that this refresh did not see are closed rather than deleted — see
     * [FinanceDao.closeUnseen] for why that distinction is not pedantry.
     */
    suspend fun applyRefresh(
        connectionId: String,
        accounts: List<Accounts.Account>,
        added: List<Transaction>,
        modified: List<Transaction>,
        removedProviderIds: List<String>
    ) {
        dao.applyRefresh(
            accounts = accounts.map { it.toEntity() },
            transactions = (added + modified).map { it.toEntity() },
            removedProviderIds = removedProviderIds
        )
        if (accounts.isNotEmpty()) dao.closeUnseen(connectionId, accounts.map { it.id })
    }

    /**
     * Re-derive the bill list from what is now known, and write the difference.
     *
     * The order is the whole of it, and each step depends on the one before:
     *
     * 1. **Statement bills win.** They are written first so predictions can be tested against them.
     * 2. **Predictions fill the gaps**, skipping any obligation a statement already covers.
     * 3. **Payments settle whatever they match**, statement and predicted alike.
     * 4. **Stale predictions are pruned** — but only ones that are unpaid and hold no task, because a
     *    paid bill is a record and a published one has a claim on somebody's week that the round has
     *    to withdraw rather than have deleted under it.
     *
     * Existing rows' `paidOn` and their LifeOps links are carried across by id, so re-deriving does
     * not un-pay a bill or orphan a task.
     */
    suspend fun rebuildBills(today: LocalDate, statements: List<Bills.Bill>, horizonDays: Long = 45L) {
        val existing = dao.bills().associateBy { it.id }
        val transactions = dao.since(today.minusDays(HISTORY_DAYS).toEpochDay()).map { it.toModel() }

        val typed = existing.values.filter { Bills.Source.fromKey(it.source) == Bills.Source.MANUAL }
            .map { it.toModel() }
        // Repeating typed bills mint their own next occurrences, and they do it *before* prediction
        // runs so that a predicted bill can be dropped against next month's rent as well as this
        // month's. Rolling afterwards would show both for a fortnight, every month.
        val minted = Bills.rollForward(typed, today, horizonDays)
        val manual = typed + minted
        val predicted = Bills.predict(
            series = Recurring.detect(transactions),
            today = today,
            horizonDays = horizonDays,
            existing = statements + manual
        )

        val fresh = (statements + predicted).map { bill ->
            // Carry across what the household and the week already know about this row.
            val previous = existing[bill.id] ?: return@map bill
            bill.copy(
                paidOn = previous.paidOnEpochDay?.let { LocalDate.ofEpochDay(it) },
                publishToWeek = previous.publishToWeek,
                autopay = previous.autopay,
                recurrenceMonths = previous.recurrenceMonths
            )
        }

        val settled = Bills.settle(fresh + manual, transactions)
        dao.upsertBills(settled.map { bill ->
            // upsert would drop the link columns, which live on the entity and not on the model.
            val previous = existing[bill.id]
            bill.toEntity(
                lifeOpsTaskId = previous?.lifeOpsTaskId,
                publishedDueEpochDay = previous?.publishedDueEpochDay
            )
        })
        dao.pruneStalePredictions(keep = settled.map { it.id })
    }

    // --- BillStore, for the LifeOps round ---------------------------------------------------------

    override suspend fun billSnapshots(now: Long): List<BillSnapshot> {
        val names = dao.accounts().associate { it.id to it.name }
        return dao.bills().map { row ->
            BillSnapshot(
                bill = row.toModel(),
                accountName = names[row.accountId] ?: row.payee,
                link = BillTasks.TaskLink(
                    taskId = row.lifeOpsTaskId,
                    publishedDue = row.publishedDueEpochDay?.let { LocalDate.ofEpochDay(it) }
                )
            )
        }
    }

    override suspend fun setBillLink(billId: String, taskId: String?, publishedDue: LocalDate?) {
        dao.setBillLink(billId, taskId, publishedDue?.toEpochDay())
    }

    override suspend fun markPaidFromWeek(billId: String, paidOn: LocalDate): Boolean {
        val bill = dao.bill(billId) ?: return false
        if (bill.paidOnEpochDay != null) return false
        dao.setPaid(billId, paidOn.toEpochDay())
        return true
    }

    // --- mapping ----------------------------------------------------------------------------------

    private fun ConnectionEntity.toModel() = Connection(
        id = id,
        provider = Provider.fromKey(provider),
        displayName = displayName,
        institutionId = institutionId,
        itemId = itemId,
        addedAt = addedAt,
        lastSyncedAt = lastSyncedAt,
        needsReauth = needsReauth,
        lastError = lastError
    )

    private fun Connection.toEntity() = ConnectionEntity(
        id = id,
        provider = provider.key,
        displayName = displayName,
        institutionId = institutionId,
        itemId = itemId,
        addedAt = addedAt,
        lastSyncedAt = lastSyncedAt,
        needsReauth = needsReauth,
        lastError = lastError
    )

    private fun AccountEntity.toModel() = Accounts.Account(
        id = id,
        connectionId = connectionId,
        providerAccountId = providerAccountId,
        name = name,
        officialName = officialName,
        mask = mask,
        kind = AccountKind.fromKey(kind),
        balance = Accounts.Balance(
            currentCents = currentCents,
            availableCents = availableCents,
            limitCents = limitCents
        ),
        currency = currency,
        includeInPicture = includeInPicture,
        closed = closed
    )

    private fun Accounts.Account.toEntity() = AccountEntity(
        id = id,
        connectionId = connectionId,
        providerAccountId = providerAccountId,
        name = name,
        officialName = officialName,
        mask = mask,
        kind = kind.key,
        currentCents = balance.currentCents,
        availableCents = balance.availableCents,
        limitCents = balance.limitCents,
        currency = currency,
        includeInPicture = includeInPicture,
        closed = closed
    )

    private fun TransactionEntity.toModel() = Transaction(
        id = id,
        accountId = accountId,
        providerTransactionId = providerTransactionId,
        date = LocalDate.ofEpochDay(dateEpochDay),
        amountCents = amountCents,
        description = description,
        merchant = merchant,
        category = Category.fromKey(category),
        pending = pending,
        transfer = transfer
    )

    private fun Transaction.toEntity() = TransactionEntity(
        id = id,
        accountId = accountId,
        providerTransactionId = providerTransactionId,
        dateEpochDay = date.toEpochDay(),
        amountCents = amountCents,
        description = description,
        merchant = merchant,
        category = category.key,
        merchantKey = Merchants.key(label()),
        pending = pending,
        transfer = transfer
    )

    private fun BillEntity.toModel() = Bills.Bill(
        id = id,
        accountId = accountId,
        payee = payee,
        dueDate = LocalDate.ofEpochDay(dueEpochDay),
        amountCents = amountCents,
        minimumCents = minimumCents,
        source = Bills.Source.fromKey(source),
        category = Category.fromKey(category),
        merchantKey = merchantKey,
        paidOn = paidOnEpochDay?.let { LocalDate.ofEpochDay(it) },
        autopay = autopay,
        publishToWeek = publishToWeek,
        recurrenceMonths = recurrenceMonths
    )

    private fun Bills.Bill.toEntity(
        lifeOpsTaskId: String? = null,
        publishedDueEpochDay: Long? = null
    ) = BillEntity(
        id = id,
        accountId = accountId,
        payee = payee,
        dueEpochDay = dueDate.toEpochDay(),
        amountCents = amountCents,
        minimumCents = minimumCents,
        source = source.key,
        category = category.key,
        merchantKey = merchantKey,
        paidOnEpochDay = paidOn?.toEpochDay(),
        autopay = autopay,
        publishToWeek = publishToWeek,
        lifeOpsTaskId = lifeOpsTaskId,
        publishedDueEpochDay = publishedDueEpochDay,
        recurrenceMonths = recurrenceMonths
    )

    private companion object {
        /** How far behind our newest row a refresh starts. See [syncFrom]. */
        const val OVERLAP_DAYS = 14L

        /**
         * How much history the recurring detector is given.
         *
         * Fifteen months, so an annual premium has two occurrences in range and a quarterly one has
         * five. Three years would find more and would also mean folding over three years of rows on
         * every refresh; a yearly bill needs three occurrences to be detected at all, which is more
         * history than most households have connected.
         */
        const val HISTORY_DAYS = 460L
    }
}
