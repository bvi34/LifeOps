package com.finance.app.data.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.core.app.ApplicationProvider
import com.finance.app.data.db.entities.AccountEntity
import com.finance.app.data.db.entities.BillEntity
import com.finance.app.data.db.entities.ConnectionEntity
import com.finance.app.data.db.entities.TransactionEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The schema itself, asked about the things only SQLite can answer.
 *
 * There are no migrations here yet — nothing has shipped `:finance`, so version 1 is amended in
 * place rather than migrated from. When that stops being true these tests get company, in the shape
 * `MaintenanceMigrationTest` already has: `internal` migration objects driven directly, so the test
 * runs the very objects that ship rather than a copy that could drift.
 *
 * What is worth asserting today is that the **cascade** is really wired the way the entity comments
 * claim, in both directions: that it runs connection → account → transaction, and that it stops
 * before bills. That second half is a design decision expressed as the *absence* of a foreign key,
 * and an absence is exactly the kind of thing a later refactor adds back by accident while tidying.
 */
@RunWith(RobolectricTestRunner::class)
class FinanceDatabaseTest {

    private lateinit var db: FinanceDatabase

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, FinanceDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    private fun connection(id: String = "conn") = ConnectionEntity(
        id = id,
        provider = "plaid",
        displayName = "USAA",
        institutionId = null,
        itemId = null,
        addedAt = 0L,
        lastSyncedAt = null
    )

    private fun account(id: String = "conn:chk", connectionId: String = "conn") = AccountEntity(
        id = id,
        connectionId = connectionId,
        providerAccountId = "chk",
        name = "Checking",
        officialName = null,
        mask = "0123",
        kind = "depository",
        currentCents = 240_000L,
        availableCents = 240_000L,
        limitCents = null,
        currency = "USD"
    )

    private fun transaction(id: String, accountId: String = "conn:chk") = TransactionEntity(
        id = id,
        accountId = accountId,
        providerTransactionId = id,
        dateEpochDay = 20_700L,
        amountCents = -4_000L,
        description = "SAFEWAY",
        merchant = null,
        category = "groceries",
        merchantKey = "SAFEWAY"
    )

    private fun bill(id: String, accountId: String = "conn:chk") = BillEntity(
        id = id,
        accountId = accountId,
        payee = "Landlord",
        dueEpochDay = 20_710L,
        amountCents = 140_000L,
        minimumCents = null,
        source = "manual",
        category = "housing",
        merchantKey = "LANDLORD",
        paidOnEpochDay = null
    )

    @Test
    fun `the database opens at the version the backup manifest reports`() {
        // The contributor reads FINANCE_DB_VERSION rather than repeating a number, so this is the
        // assertion that the two cannot drift apart.
        assertEquals(FINANCE_DB_VERSION, db.openHelper.readableDatabase.version)
    }

    @Test
    fun `deleting a connection cascades to its accounts and their transactions`() = runTest {
        val dao = db.financeDao()
        dao.upsertConnection(connection())
        dao.upsertAccounts(listOf(account()))
        dao.upsertTransactions(listOf(transaction("t1"), transaction("t2")))

        dao.deleteConnection("conn")

        assertTrue(dao.accounts().isEmpty())
        assertTrue(dao.since(0L).isEmpty())
    }

    @Test
    fun `bills are outside the cascade, deliberately`() = runTest {
        val dao = db.financeDao()
        dao.upsertConnection(connection())
        dao.upsertAccounts(listOf(account()))
        dao.upsertBills(listOf(bill("b1")))

        dao.deleteConnection("conn")

        // The bill names an account that no longer exists and that is the intended state: a card
        // closed and re-linked, or a connection removed and added back, must not silently delete an
        // obligation the household still owes.
        assertEquals(1, dao.bills().size)
        assertEquals("conn:chk", dao.bills().single().accountId)
    }

    @Test
    fun `foreign keys are actually enforced, not merely declared`() = runTest {
        val dao = db.financeDao()
        // Room turns foreign keys on; if that ever stopped being true the cascade tests above would
        // pass for the wrong reason, so this asks SQLite directly.
        db.query(SimpleSQLiteQuery("PRAGMA foreign_keys")).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("foreign keys must be on, or the cascades above prove nothing", 1, cursor.getInt(0))
        }

        // And an account whose connection does not exist is refused rather than orphaned.
        val refused = runCatching { dao.upsertAccounts(listOf(account(connectionId = "nobody"))) }
        assertTrue("an orphan account should not be writable", refused.isFailure)
    }

    @Test
    fun `a transaction is upserted by id, so a re-fetched page does not double it`() = runTest {
        val dao = db.financeDao()
        dao.upsertConnection(connection())
        dao.upsertAccounts(listOf(account()))

        // The overlap window deliberately re-fetches rows already held; this is why that is safe.
        dao.upsertTransactions(listOf(transaction("t1")))
        dao.upsertTransactions(listOf(transaction("t1")))

        assertEquals(1, dao.since(0L).size)
    }
}
