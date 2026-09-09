package com.finance.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.finance.app.data.db.FinanceDatabase
import com.finance.app.logic.AccountKind
import com.finance.app.logic.Accounts
import com.finance.app.logic.Bills
import com.finance.app.logic.Category
import com.finance.app.logic.Connection
import com.finance.app.logic.Merchants
import com.finance.app.logic.Provider
import com.finance.app.logic.Transaction
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

/**
 * The store's own rules, against a real database.
 *
 * Everything in `logic/` is pure and tested by reasoning about it. These are the rules only SQLite
 * can be asked about, and each one here is a claim the module's documentation makes out loud:
 *
 * - that removing a connection takes its accounts and their history with it, **and leaves its bills
 *   standing** — the one deliberate exception to the cascade, because an obligation can outlive the
 *   account it was read from;
 * - that a refresh which no longer sees an account **closes** it rather than deleting it, so a
 *   bank's outage cannot erase years of transactions;
 * - that `rebuildBills` — by some distance the most intricate method in the module — carries a
 *   bill's paid state and its LifeOps task link across a rebuild, drops predictions a statement
 *   covers, and prunes only what it is allowed to.
 *
 * A fake DAO would answer every one of those with whatever this test assumed, which is why there
 * isn't one.
 */
@RunWith(RobolectricTestRunner::class)
class FinanceRepositoryTest {

    private lateinit var db: FinanceDatabase
    private lateinit var repo: FinanceRepository

    private val today: LocalDate = LocalDate.parse("2026-09-09")

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        // In memory, but a real Room database: the same entities, the same generated SQL, and — the
        // point of the whole file — the same foreign keys actually enforced.
        db = Room.inMemoryDatabaseBuilder(context, FinanceDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = FinanceRepository(db.financeDao())
    }

    @After
    fun tearDown() = db.close()

    // --- helpers ----------------------------------------------------------------------------------

    private suspend fun connection(id: String = "conn", name: String = "USAA"): Connection {
        val row = Connection(
            id = id,
            provider = Provider.PLAID,
            displayName = name,
            addedAt = 1_700_000_000_000L
        )
        repo.upsertConnection(row)
        return row
    }

    private fun account(
        id: String = "conn:chk",
        connectionId: String = "conn",
        kind: AccountKind = AccountKind.DEPOSITORY,
        current: Long = 240_000L,
        name: String = "Checking"
    ) = Accounts.Account(
        id = id,
        connectionId = connectionId,
        providerAccountId = id.substringAfter(':'),
        name = name,
        officialName = null,
        mask = "0123",
        kind = kind,
        balance = Accounts.Balance(currentCents = current, availableCents = current)
    )

    private fun txn(
        date: String,
        amountCents: Long,
        description: String,
        accountId: String = "conn:chk",
        category: Category = Category.OTHER,
        id: String = "$accountId:$date:$description"
    ) = Transaction(
        id = id,
        accountId = accountId,
        providerTransactionId = id,
        date = LocalDate.parse(date),
        amountCents = amountCents,
        description = description,
        merchant = null,
        category = category
    )

    private fun statement(
        payee: String,
        due: String,
        amountCents: Long,
        accountId: String = "conn:visa"
    ) = Bills.Bill(
        id = "statement:$accountId:$due",
        accountId = accountId,
        payee = payee,
        dueDate = LocalDate.parse(due),
        amountCents = amountCents,
        source = Bills.Source.STATEMENT,
        category = Category.DEBT
    )

    // --- the cascade, and its one exception -------------------------------------------------------

    @Test
    fun `removing a connection takes its accounts and their history`() = runTest {
        connection()
        repo.applyRefresh(
            connectionId = "conn",
            accounts = listOf(account()),
            added = listOf(txn("2026-09-01", -4_000L, "SAFEWAY")),
            modified = emptyList(),
            removedProviderIds = emptyList()
        )
        assertEquals(1, repo.accounts().size)
        assertEquals(1, repo.transactionsSince(today.minusMonths(1)).size)

        repo.deleteConnection("conn")

        assertTrue(repo.accounts().isEmpty())
        assertTrue("transactions cascade through the account", repo.transactionsSince(today.minusMonths(1)).isEmpty())
    }

    @Test
    fun `but its bills survive it, because an obligation outlives the account it was read from`() = runTest {
        connection()
        repo.applyRefresh("conn", listOf(account(id = "conn:visa", kind = AccountKind.CREDIT)), emptyList(), emptyList(), emptyList())
        repo.upsertBills(listOf(statement("USAA Visa", "2026-09-20", 124_000L)))

        repo.deleteConnection("conn")

        // A cascade here would delete a bill that is still genuinely owed, which is why `bills` hangs
        // off no foreign key. The row keeps naming an account nobody holds; the screen renders it
        // without one.
        assertEquals(1, repo.bills().size)
        assertEquals("USAA Visa", repo.bills().single().payee)
    }

    // --- refreshes ---------------------------------------------------------------------------------

    @Test
    fun `an account a refresh stops seeing is closed, never deleted`() = runTest {
        connection()
        repo.applyRefresh(
            "conn",
            listOf(account(id = "conn:chk"), account(id = "conn:sav", name = "Savings")),
            listOf(txn("2026-09-01", -4_000L, "SAFEWAY", accountId = "conn:sav")),
            emptyList(),
            emptyList()
        )

        // The next refresh reports only one of them — which is what a bank outage looks like.
        repo.applyRefresh("conn", listOf(account(id = "conn:chk")), emptyList(), emptyList(), emptyList())

        val savings = repo.accounts().first { it.id == "conn:sav" }
        assertTrue("closed, so it stops counting", savings.closed)
        assertEquals(
            "and its history is still here — deleting would erase years over one bad response",
            1,
            repo.transactionsSince(today.minusMonths(1)).count { it.accountId == "conn:sav" }
        )
    }

    @Test
    fun `a refresh upserts rather than ignoring, so a settled charge overwrites its pending self`() = runTest {
        connection()
        val pending = txn("2026-09-01", -4_000L, "RESTAURANT").copy(pending = true)
        repo.applyRefresh("conn", listOf(account()), listOf(pending), emptyList(), emptyList())

        // Same provider id, settled, and a tip added — the case an insert-or-ignore would silently
        // freeze at the first version forever.
        val settled = pending.copy(amountCents = -4_800L, pending = false)
        repo.applyRefresh("conn", emptyList(), emptyList(), listOf(settled), emptyList())

        val row = repo.transactionsSince(today.minusMonths(1)).single()
        assertEquals(-4_800L, row.amountCents)
        assertFalse(row.pending)
    }

    @Test
    fun `a removal from the provider really removes the row`() = runTest {
        connection()
        val row = txn("2026-09-01", -4_000L, "ABANDONED")
        repo.applyRefresh("conn", listOf(account()), listOf(row), emptyList(), emptyList())

        // Banks do un-post transactions; an app that never heard about it would show a phantom.
        repo.applyRefresh("conn", emptyList(), emptyList(), emptyList(), listOf(row.providerTransactionId))

        assertTrue(repo.transactionsSince(today.minusMonths(1)).isEmpty())
    }

    @Test
    fun `the sync start overlaps the newest row, because transactions post late`() = runTest {
        connection()
        repo.applyRefresh(
            "conn",
            listOf(account()),
            listOf(txn("2026-09-01", -4_000L, "SAFEWAY")),
            emptyList(),
            emptyList()
        )
        // A fortnight behind the high-water mark. Asking from the newest row itself would silently
        // lose a card charge that posts four days after it happened.
        assertEquals(
            LocalDate.parse("2026-08-18"),
            repo.syncFrom("conn:chk", firstRun = today.minusMonths(12))
        )
        // An account with nothing in it starts at the first-run date rather than at the epoch.
        assertEquals(
            today.minusMonths(12),
            repo.syncFrom("conn:unknown", firstRun = today.minusMonths(12))
        )
    }

    // --- rebuildBills ------------------------------------------------------------------------------

    @Test
    fun `a rebuild keeps a bill paid, and keeps its place on the week`() = runTest {
        connection()
        repo.applyRefresh("conn", listOf(account(id = "conn:visa", kind = AccountKind.CREDIT)), emptyList(), emptyList(), emptyList())

        val bill = statement("USAA Visa", "2026-09-20", 124_000L)
        repo.upsertBills(listOf(bill))
        repo.setBillLink(bill.id, taskId = "task-1", publishedDue = bill.dueDate)
        repo.markPaidFromWeek(bill.id, LocalDate.parse("2026-09-18"))

        // The same statement comes back on the next refresh, as it would.
        repo.rebuildBills(today, statements = listOf(bill))

        val after = repo.bills().single { it.id == bill.id }
        assertEquals("a rebuild must not un-pay a bill", LocalDate.parse("2026-09-18"), after.paidOn)

        // And the link survives, or the round would publish a second task for the same obligation.
        val snapshot = repo.billSnapshots(now = 0L).single { it.bill.id == bill.id }
        assertEquals("task-1", snapshot.link.taskId)
        assertEquals(bill.dueDate, snapshot.link.publishedDue)
    }

    @Test
    fun `a rebuild keeps the week switch you set by hand`() = runTest {
        connection()
        val bill = statement("USAA Visa", "2026-09-20", 124_000L)
        repo.upsertBills(listOf(bill))
        repo.setBillPublishToWeek(bill.id, false)

        repo.rebuildBills(today, statements = listOf(bill))

        assertFalse(
            "turning a bill off is a preference, not something a refresh gets to reverse",
            repo.bills().single { it.id == bill.id }.publishToWeek
        )
    }

    @Test
    fun `a rebuild drops a prediction a statement already covers`() = runTest {
        connection()
        repo.applyRefresh("conn", listOf(account(id = "conn:visa", kind = AccountKind.CREDIT)), emptyList(), emptyList(), emptyList())

        // Four monthly charges to the same payee: enough for a series, so this would predict.
        val history = (0..3).map { index ->
            val date = LocalDate.parse("2026-05-20").plusMonths(index.toLong())
            txn(date.toString(), -124_000L, "USAA CARD PAYMENT", accountId = "conn:visa", category = Category.DEBT, id = "h$index")
        }
        repo.applyRefresh("conn", emptyList(), history, emptyList(), emptyList())

        val fromStatement = statement("USAA CARD PAYMENT", "2026-09-20", 124_000L)
        repo.rebuildBills(today, statements = listOf(fromStatement))

        val bills = repo.bills()
        assertEquals("one obligation, one row", 1, bills.size)
        assertEquals(Bills.Source.STATEMENT, bills.single().source)
    }

    @Test
    fun `a rebuild prunes a prediction it no longer stands behind, but not a published one`() = runTest {
        connection()
        repo.applyRefresh("conn", listOf(account()), emptyList(), emptyList(), emptyList())

        val stale = Bills.Bill(
            id = "predicted:conn:chk:GONE:2026-09-20",
            accountId = "conn:chk",
            payee = "Gone",
            dueDate = LocalDate.parse("2026-09-20"),
            amountCents = 5_000L,
            source = Bills.Source.PREDICTED,
            merchantKey = "GONE"
        )
        val published = stale.copy(id = "predicted:conn:chk:HELD:2026-09-21", payee = "Held", merchantKey = "HELD")
        repo.upsertBills(listOf(stale, published))
        repo.setBillLink(published.id, taskId = "task-9", publishedDue = published.dueDate)

        // Nothing in the transactions supports either any more.
        repo.rebuildBills(today, statements = emptyList())

        val ids = repo.bills().map { it.id }
        assertFalse("a prediction nothing supports goes", ids.contains(stale.id))
        assertTrue(
            "one holding a task has a claim on somebody's week — the round withdraws it, not a delete",
            ids.contains(published.id)
        )
    }

    @Test
    fun `a rebuild settles a bill from a payment that actually landed`() = runTest {
        connection()
        repo.applyRefresh(
            "conn",
            listOf(account()),
            listOf(txn("2026-09-19", -18_000L, "CITY UTILITIES 0919")),
            emptyList(),
            emptyList()
        )
        val bill = Bills.Bill(
            id = "statement:conn:chk:2026-09-20",
            accountId = "conn:chk",
            payee = "CITY UTILITIES",
            dueDate = LocalDate.parse("2026-09-20"),
            amountCents = 18_000L,
            source = Bills.Source.STATEMENT,
            merchantKey = Merchants.key("CITY UTILITIES")
        )

        repo.rebuildBills(today, statements = listOf(bill))

        assertEquals(LocalDate.parse("2026-09-19"), repo.bills().single().paidOn)
    }

    // --- typed bills -------------------------------------------------------------------------------

    @Test
    fun `a typed bill is written under a derived id, so entering it twice corrects it`() = runTest {
        connection()
        repo.applyRefresh("conn", listOf(account()), emptyList(), emptyList(), emptyList())

        repo.addManualBill("conn:chk", "Landlord", LocalDate.parse("2026-10-01"), 140_000L, recurrenceMonths = 1)
        repo.addManualBill("conn:chk", "Landlord", LocalDate.parse("2026-10-01"), 145_000L, recurrenceMonths = 1)

        val bills = repo.bills()
        assertEquals("the same occurrence is the same row", 1, bills.size)
        assertEquals("and the second entry is the correction", 145_000L, bills.single().amountCents)
    }

    @Test
    fun `re-entering a typed bill keeps the task it already put on the week`() = runTest {
        connection()
        val first = repo.addManualBill("conn:chk", "Landlord", LocalDate.parse("2026-10-01"), 140_000L, recurrenceMonths = 1)
        repo.setBillLink(first.id, taskId = "task-3", publishedDue = first.dueDate)

        repo.addManualBill("conn:chk", "Landlord", LocalDate.parse("2026-10-01"), 145_000L, recurrenceMonths = 1)

        val snapshot = repo.billSnapshots(now = 0L).single()
        assertEquals("correcting the figure must not orphan the task", "task-3", snapshot.link.taskId)
    }

    @Test
    fun `a rebuild mints the repeats a typed bill owes`() = runTest {
        connection()
        repo.addManualBill("conn:chk", "Landlord", LocalDate.parse("2026-09-01"), 140_000L, recurrenceMonths = 1)

        repo.rebuildBills(today, statements = emptyList(), horizonDays = 100L)

        val dates = repo.bills().map { it.dueDate }.sorted()
        assertEquals(
            listOf("2026-09-01", "2026-10-01", "2026-11-01", "2026-12-01").map(LocalDate::parse),
            dates
        )
        // Running it again is a no-op, which is what makes the roll safe to do on every refresh.
        repo.rebuildBills(today, statements = emptyList(), horizonDays = 100L)
        assertEquals(4, repo.bills().size)
    }

    @Test
    fun `removing a typed bill takes the repeats ahead and hands back their tasks`() = runTest {
        connection()
        val first = repo.addManualBill("conn:chk", "Landlord", LocalDate.parse("2026-09-01"), 140_000L, recurrenceMonths = 1)
        repo.rebuildBills(today, statements = emptyList(), horizonDays = 100L)

        // One occurrence is paid, and one is on the week.
        repo.markPaidFromWeek(first.id, LocalDate.parse("2026-09-02"))
        val ahead = repo.bills().first { it.dueDate == LocalDate.parse("2026-10-01") }
        repo.setBillLink(ahead.id, taskId = "task-7", publishedDue = ahead.dueDate)

        val orphaned = repo.deleteManualSeries("conn:chk", Merchants.key("Landlord"))

        assertEquals("the caller has to withdraw this from the week", listOf("task-7"), orphaned)
        val left = repo.bills()
        assertEquals("only the paid one survives", 1, left.size)
        assertNotNull("because it is a record of money that actually left", left.single().paidOn)
    }

    // --- the BillStore contract the round runs against ---------------------------------------------

    @Test
    fun `a snapshot carries the account's name, so the week's task can be worded`() = runTest {
        connection()
        repo.applyRefresh("conn", listOf(account(name = "USAA Classic Checking")), emptyList(), emptyList(), emptyList())
        repo.addManualBill("conn:chk", "Landlord", LocalDate.parse("2026-09-12"), 140_000L, recurrenceMonths = null)

        assertEquals("USAA Classic Checking", repo.billSnapshots(now = 0L).single().accountName)
    }

    @Test
    fun `marking paid from the week is refused when a payment already settled it`() = runTest {
        connection()
        val bill = repo.addManualBill("conn:chk", "Landlord", LocalDate.parse("2026-09-12"), 140_000L, recurrenceMonths = null)
        assertTrue(repo.markPaidFromWeek(bill.id, LocalDate.parse("2026-09-11")))

        // The bank's word came first and stands; a later tick must not move the date it was paid on.
        assertFalse(repo.markPaidFromWeek(bill.id, LocalDate.parse("2026-09-13")))
        assertEquals(LocalDate.parse("2026-09-11"), repo.bills().single().paidOn)
    }

    @Test
    fun `a link can be dropped without forgetting the date it was published for`() = runTest {
        connection()
        val bill = repo.addManualBill("conn:chk", "Landlord", LocalDate.parse("2026-09-12"), 140_000L, recurrenceMonths = null)
        repo.setBillLink(bill.id, taskId = "task-1", publishedDue = bill.dueDate)

        // What the round does when it finds the task gone: forget the id, remember the date. That
        // date is the whole mechanism stopping a deleted task being put straight back.
        repo.setBillLink(bill.id, taskId = null, publishedDue = bill.dueDate)

        val link = repo.billSnapshots(now = 0L).single().link
        assertNull(link.taskId)
        assertEquals(bill.dueDate, link.publishedDue)
    }
}
