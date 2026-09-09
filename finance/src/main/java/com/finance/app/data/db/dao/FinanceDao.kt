package com.finance.app.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.finance.app.data.db.entities.AccountEntity
import com.finance.app.data.db.entities.BillEntity
import com.finance.app.data.db.entities.ConnectionEntity
import com.finance.app.data.db.entities.TransactionEntity
import kotlinx.coroutines.flow.Flow

/**
 * Finance's one DAO.
 *
 * One interface rather than four, because every screen crosses tables: the Picture screen needs
 * balances, bills and a year of transactions at once; the Due screen needs bills against the
 * accounts they are paid from. Splitting per table would mean a repository stitching four flows
 * together for every screen anyway — which is what it does, once, below.
 *
 * Two habits worth naming:
 *
 * **Upserts everywhere, never insert-or-ignore.** A refresh brings the current truth: a balance that
 * moved, a pending charge that settled at a different amount, a description the bank tidied up. An
 * insert that ignored conflicts would leave the first version of every row in place forever, which
 * is the worst of both worlds — the app would look like it was refreshing and would not be.
 *
 * **The reads that feed the roll-ups fetch by date range, not by account.** Recurring detection and
 * every cash-flow figure are folds over *all* accounts, and asking per account would be a query per
 * account. The range is what bounds the work, and `dateEpochDay` is indexed for it.
 */
@Dao
interface FinanceDao {

    // --- connections ---

    @Query("SELECT * FROM connections ORDER BY displayName COLLATE NOCASE")
    fun observeConnections(): Flow<List<ConnectionEntity>>

    @Query("SELECT * FROM connections")
    suspend fun connections(): List<ConnectionEntity>

    @Query("SELECT * FROM connections WHERE id = :id")
    suspend fun connection(id: String): ConnectionEntity?

    @Upsert
    suspend fun upsertConnection(connection: ConnectionEntity)

    @Query("UPDATE connections SET lastSyncedAt = :at, needsReauth = 0, lastError = NULL WHERE id = :id")
    suspend fun markSynced(id: String, at: Long)

    @Query("UPDATE connections SET needsReauth = :needsReauth, lastError = :error WHERE id = :id")
    suspend fun markProblem(id: String, needsReauth: Boolean, error: String?)

    /** Cascades to accounts and, through them, to transactions. Bills are deliberately left. */
    @Query("DELETE FROM connections WHERE id = :id")
    suspend fun deleteConnection(id: String)

    // --- accounts ---

    @Query("SELECT * FROM accounts ORDER BY closed, sortOrder, name COLLATE NOCASE")
    fun observeAccounts(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts")
    suspend fun accounts(): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE connectionId = :connectionId")
    suspend fun accountsFor(connectionId: String): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun account(id: String): AccountEntity?

    @Upsert
    suspend fun upsertAccounts(accounts: List<AccountEntity>)

    @Query("UPDATE accounts SET includeInPicture = :include WHERE id = :id")
    suspend fun setIncluded(id: String, include: Boolean)

    /**
     * Mark the accounts a refresh no longer saw as closed, rather than deleting them.
     *
     * Deleting would take their transactions with them — years of history erased because a bank
     * dropped an account from one response, which happens during outages. Closed is recoverable;
     * deleted is not.
     */
    @Query("UPDATE accounts SET closed = 1 WHERE connectionId = :connectionId AND id NOT IN (:seen)")
    suspend fun closeUnseen(connectionId: String, seen: List<String>)

    // --- transactions ---

    @Query(
        """
        SELECT * FROM transactions
        WHERE dateEpochDay BETWEEN :fromDay AND :toDay
        ORDER BY dateEpochDay DESC, id
        """
    )
    fun observeBetween(fromDay: Long, toDay: Long): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE dateEpochDay >= :fromDay ORDER BY dateEpochDay DESC, id")
    suspend fun since(fromDay: Long): List<TransactionEntity>

    @Query(
        """
        SELECT * FROM transactions
        WHERE accountId = :accountId
        ORDER BY dateEpochDay DESC, id
        LIMIT :limit
        """
    )
    fun observeForAccount(accountId: String, limit: Int): Flow<List<TransactionEntity>>

    @Query("SELECT MAX(dateEpochDay) FROM transactions WHERE accountId = :accountId")
    suspend fun latestDay(accountId: String): Long?

    @Upsert
    suspend fun upsertTransactions(transactions: List<TransactionEntity>)

    @Query("DELETE FROM transactions WHERE providerTransactionId IN (:providerIds)")
    suspend fun deleteByProviderIds(providerIds: List<String>)

    // --- bills ---

    @Query("SELECT * FROM bills ORDER BY dueEpochDay, payee COLLATE NOCASE")
    fun observeBills(): Flow<List<BillEntity>>

    @Query("SELECT * FROM bills")
    suspend fun bills(): List<BillEntity>

    @Query("SELECT * FROM bills WHERE id = :id")
    suspend fun bill(id: String): BillEntity?

    @Upsert
    suspend fun upsertBills(bills: List<BillEntity>)

    @Query("UPDATE bills SET paidOnEpochDay = :paidOn WHERE id = :id")
    suspend fun setPaid(id: String, paidOn: Long?)

    @Query("UPDATE bills SET publishToWeek = :publish WHERE id = :id")
    suspend fun setPublishToWeek(id: String, publish: Boolean)

    @Query("UPDATE bills SET lifeOpsTaskId = :taskId, publishedDueEpochDay = :publishedDue WHERE id = :id")
    suspend fun setBillLink(id: String, taskId: String?, publishedDue: Long?)

    /**
     * Delete every occurrence of one typed bill that has not been paid.
     *
     * A repeating manual bill is a *series* of rows sharing an account and a payee, so "stop asking
     * me for this" has to remove the ones still ahead rather than only the one being looked at —
     * otherwise next month's occurrence, already minted, comes straight back.
     *
     * Paid occurrences survive: they are a record of money that actually left, and deleting them
     * would quietly rewrite the household's own history.
     */
    @Query(
        """
        DELETE FROM bills
        WHERE source = 'manual'
          AND accountId = :accountId
          AND merchantKey = :merchantKey
          AND paidOnEpochDay IS NULL
        """
    )
    suspend fun deleteManualSeries(accountId: String, merchantKey: String)

    /** The unpaid occurrences of one typed series, so their week tasks can be withdrawn first. */
    @Query(
        """
        SELECT * FROM bills
        WHERE source = 'manual'
          AND accountId = :accountId
          AND merchantKey = :merchantKey
          AND paidOnEpochDay IS NULL
        """
    )
    suspend fun manualSeries(accountId: String, merchantKey: String): List<BillEntity>

    /**
     * Clear out predictions that a fresh detection run no longer stands behind.
     *
     * Only unpaid, unpublished predictions: one that was paid is a record of something that
     * happened, and one holding a LifeOps task has a claim on somebody's week that has to be
     * withdrawn by the round rather than by a delete under it.
     */
    @Query(
        """
        DELETE FROM bills
        WHERE source = 'predicted'
          AND paidOnEpochDay IS NULL
          AND lifeOpsTaskId IS NULL
          AND id NOT IN (:keep)
        """
    )
    suspend fun pruneStalePredictions(keep: List<String>)

    /**
     * A refresh's writes, in one transaction.
     *
     * Accounts before transactions because of the foreign key, and both before bills because a bill
     * is derived from what the first two now say. Wrapping them means a refresh interrupted halfway
     * leaves the previous picture intact rather than a half-updated one that reconciles against
     * nothing.
     */
    @Transaction
    suspend fun applyRefresh(
        accounts: List<AccountEntity>,
        transactions: List<TransactionEntity>,
        removedProviderIds: List<String>
    ) {
        if (accounts.isNotEmpty()) upsertAccounts(accounts)
        if (removedProviderIds.isNotEmpty()) deleteByProviderIds(removedProviderIds)
        if (transactions.isNotEmpty()) upsertTransactions(transactions)
    }
}
