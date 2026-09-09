package com.finance.app.data.repository

import android.util.Log
import com.finance.app.data.net.MercuryClient
import com.finance.app.data.net.PlaidClient
import com.finance.app.data.net.syncAll
import com.finance.app.data.secure.FinanceSecrets
import com.finance.app.logic.Bills
import com.finance.app.logic.Connection
import com.finance.app.logic.Provider
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate

/**
 * One refresh: ask each connection what it knows, write it down, re-derive the bills.
 *
 * This is the only class in Finance that both talks to a provider and writes to the database, and it
 * is deliberately dull — the interesting decisions are all one level down, in the parsers and in
 * `logic/`. What it owns is the *order*, the *error handling*, and the promise that a bad connection
 * does not take the others with it.
 *
 * ## A failure is per-connection, never global
 *
 * A household with USAA and Mercury connected should not lose its Mercury picture because USAA's
 * aggregator is having an afternoon. So each connection is refreshed inside its own try, its failure
 * is recorded on its own row ([FinanceRepository.markProblem]), and the loop carries on. The
 * Connections screen shows which one is unhappy; every other screen shows the data that is still
 * perfectly good.
 *
 * The one failure that is *not* an error is Plaid's `ITEM_LOGIN_REQUIRED`, which means the bank
 * wants the person to sign in again. It sets [Connection.needsReauth] rather than
 * [Connection.lastError], because presenting a routine event as a fault teaches people to ignore
 * faults.
 *
 * ## What runs, and when
 *
 * Only when somebody opens the app or presses Refresh, and never more than once at a time
 * ([gate]). There is no periodic worker, no push, and no wake-up. A finance app that phones a bank
 * on a schedule is a finance app that has to justify a background network permission and a battery
 * cost, and the value of a balance that is four hours old rather than fresh is close to zero.
 */
class FinanceSync(
    private val repository: FinanceRepository,
    private val secrets: FinanceSecrets,
    private val publisher: BillPublisher,
    private val today: () -> LocalDate = { LocalDate.now() }
) {

    /** What a refresh did, for the screen that asked for it. */
    data class Report(
        val refreshed: Int = 0,
        val failed: Int = 0,
        val needReauth: Int = 0,
        /** The first thing that went wrong, for a one-line message. */
        val firstError: String? = null
    ) {
        val ok: Boolean get() = failed == 0 && needReauth == 0
    }

    /**
     * Refresh every connection, then re-derive the bills and reconcile the week.
     *
     * Bills are rebuilt **once, after every connection has been read**, rather than per connection.
     * A prediction is dropped when a statement covers the same obligation, and rebuilding after the
     * first of two connections would mean predicting against half the statements — producing a
     * duplicate bill that the second pass would then have to take back off somebody's week.
     */
    suspend fun refreshAll(): Report = gate.withLock {
        val connections = repository.connections()
        if (connections.isEmpty()) return@withLock Report()

        var report = Report()
        val statements = mutableListOf<Bills.Bill>()

        for (connection in connections) {
            try {
                statements += refresh(connection)
                repository.markSynced(connection.id, System.currentTimeMillis())
                report = report.copy(refreshed = report.refreshed + 1)
            } catch (e: PlaidClient.PlaidException) {
                Log.w(TAG, "Plaid refresh failed for ${connection.displayName}", e)
                repository.markProblem(
                    connectionId = connection.id,
                    needsReauth = e.reauth,
                    // A connection that needs signing in again is a state, not a fault; it must not
                    // also carry an error, or the screen shows both and says two different things.
                    error = if (e.reauth) null else e.message
                )
                report = if (e.reauth) {
                    report.copy(needReauth = report.needReauth + 1)
                } else {
                    report.copy(failed = report.failed + 1, firstError = report.firstError ?: e.message)
                }
            } catch (e: MercuryClient.MercuryException) {
                Log.w(TAG, "Mercury refresh failed for ${connection.displayName}", e)
                repository.markProblem(connection.id, needsReauth = false, error = e.message)
                report = report.copy(failed = report.failed + 1, firstError = report.firstError ?: e.message)
            } catch (e: Exception) {
                Log.w(TAG, "Refresh failed for ${connection.displayName}", e)
                repository.markProblem(connection.id, needsReauth = false, error = "Something went wrong refreshing this connection.")
                report = report.copy(failed = report.failed + 1)
            }
        }

        // Once, with everything known. See the note above.
        repository.rebuildBills(today(), statements)
        publisher.round()
        report
    }

    /** Refresh one connection and return whatever statement bills it reported. */
    private suspend fun refresh(connection: Connection): List<Bills.Bill> = when (connection.provider) {
        Provider.PLAID -> refreshPlaid(connection)
        Provider.MERCURY -> refreshMercury(connection)
    }

    private suspend fun refreshPlaid(connection: Connection): List<Bills.Bill> {
        val keys = secrets.plaidKeys ?: throw PlaidClient.PlaidException("Add your Plaid keys in Connections first.")
        val token = secrets.token(connection.id)
            ?: throw PlaidClient.PlaidException("This connection has no access token — reconnect it.")
        val client = PlaidClient(keys.clientId, keys.secret, keys.environment)

        // Balances first: they define which accounts exist, and a transaction on an account we do
        // not hold is dropped by the parser rather than orphaned.
        val accounts = client.accounts(token, connection.id)
        repository.applyRefresh(connection.id, accounts, emptyList(), emptyList(), emptyList())

        val byProviderId = accounts.associate { it.providerAccountId to it.id }
        val resolve: (String) -> String? = { byProviderId[it] }

        client.syncAll(
            accessToken = token,
            startCursor = secrets.cursor(connection.id),
            accountIdFor = resolve
        ) { added, modified, removed, cursor ->
            // Rows first, then the cursor. Advancing the cursor before the rows are on disk is how a
            // sync interrupted between the two silently loses a page forever.
            repository.applyRefresh(connection.id, emptyList(), added, modified, removed)
            secrets.setCursor(connection.id, cursor)
        }

        return client.liabilities(token, resolve, today())
    }

    private suspend fun refreshMercury(connection: Connection): List<Bills.Bill> {
        val token = secrets.token(connection.id)
            ?: throw MercuryClient.MercuryException("This connection has no API token — add one in Connections.")
        val client = MercuryClient(token)

        val accounts = client.accounts(connection.id)
        repository.applyRefresh(connection.id, accounts, emptyList(), emptyList(), emptyList())

        val firstRun = today().minusDays(FIRST_RUN_DAYS)
        for (account in accounts) {
            if (account.closed) continue
            val rows = client.transactions(
                providerAccountId = account.providerAccountId,
                ourAccountId = account.id,
                since = repository.syncFrom(account.id, firstRun)
            )
            repository.applyRefresh(connection.id, emptyList(), rows, emptyList(), emptyList())
        }

        // Mercury has no liabilities endpoint — see logic/Provider. Every Mercury bill is predicted.
        return emptyList()
    }

    private val gate = Mutex()

    private companion object {
        const val TAG = "FinanceSync"

        /**
         * How much history a brand-new Mercury connection pulls.
         *
         * Fifteen months, matching the window the recurring detector reads, so a connection is
         * useful the moment it is added rather than after three months of watching. Plaid needs no
         * equivalent: its sync cursor starts at the beginning of the item's history by itself.
         */
        const val FIRST_RUN_DAYS = 460L
    }
}
