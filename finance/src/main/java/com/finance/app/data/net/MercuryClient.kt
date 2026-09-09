package com.finance.app.data.net

import com.finance.app.logic.Accounts
import com.finance.app.logic.Endpoints
import com.finance.app.logic.MercuryJson
import com.finance.app.logic.Transaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

/**
 * The Mercury half of Finance's network surface: two GETs behind a bearer token.
 *
 * Simpler than Plaid in every way that matters, and the reasons are structural rather than
 * incidental:
 *
 *  - **No aggregator.** The token is issued by Mercury to its own customer and the requests go
 *    straight to Mercury. Nothing to link, nothing that expires every ninety days, no item that can
 *    fall into `ITEM_LOGIN_REQUIRED` while you are not looking.
 *  - **Read-only is a property of the token.** Mercury issues genuinely read-only tokens, and the
 *    Connections screen tells you to make one. Plaid's read-only-ness is a property of which
 *    products were requested, which is weaker — so this is the one connection in the app where the
 *    "cannot move money" promise is enforced by the other end as well as by this one.
 *
 * What Mercury does not have is liabilities: it is a business bank with deposit accounts, so there
 * is no statement to read a due date off and every bill from a Mercury connection is predicted from
 * its transactions. The Due screen says so per bill rather than leaving it to be inferred.
 */
class MercuryClient(private val apiToken: String, private val baseUrl: String = Endpoints.MERCURY_BASE) {

    /** Any reason a call didn't produce an answer, with something a person can read. */
    class MercuryException(
        message: String,
        /** True when Mercury rejected the token itself: revoked, mistyped, or expired. */
        val badToken: Boolean = false,
        val transient: Boolean = false,
        cause: Throwable? = null
    ) : IOException(message, cause)

    /** The accounts the token can see. */
    suspend fun accounts(connectionId: String): List<Accounts.Account> =
        MercuryJson.accounts(get("/accounts"), connectionId)

    /**
     * One account's activity since [since].
     *
     * Mercury pages with `limit` and `offset` rather than a cursor, and its rows carry no account id
     * — the account is in the URL — so a refresh walks accounts one at a time. That is the one place
     * Mercury is more work than Plaid, whose sync returns everything at once.
     */
    suspend fun transactions(
        providerAccountId: String,
        ourAccountId: String,
        since: LocalDate,
        limit: Int = PAGE_SIZE
    ): List<Transaction> {
        val rows = mutableListOf<Transaction>()
        var offset = 0
        var pages = 0
        while (pages < MAX_PAGES) {
            val body = get("/account/$providerAccountId/transactions?limit=$limit&offset=$offset&start=$since")
            val page = MercuryJson.transactions(body, ourAccountId)
            rows += page
            // Mercury's `total` is the count matching the query rather than the count returned, so
            // the honest end-of-pages signal is a short page — which is also what happens when the
            // account has fewer transactions than one page holds.
            if (page.size < limit) return rows
            offset += limit
            pages++
        }
        return rows
    }

    /** The one HTTP call in this file. */
    private suspend fun get(path: String): String = withContext(Dispatchers.IO) {
        val url = "$baseUrl$path"
        // The allow-list, checked on every request. A URL that fails it is a programming error, and
        // one that would have sent a bank token somewhere it should never go.
        require(Endpoints.permits(url)) { "Finance refused to open $path" }

        val connection = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw MercuryException("Couldn't reach Mercury.", cause = e)
        }

        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Authorization", "Bearer $apiToken")
            connection.setRequestProperty("Accept", "application/json")

            val code = connection.responseCode
            if (code in 200..299) {
                return@withContext connection.inputStream.bufferedReader().use { it.readText() }
            }

            val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            throw MercuryException(
                message = MercuryJson.error(errorBody)
                    ?: when (code) {
                        401, 403 -> "Mercury wouldn't accept that API token."
                        else -> "Mercury answered $code."
                    },
                badToken = code == 401 || code == 403,
                transient = code == 429 || code >= 500
            )
        } catch (e: MercuryException) {
            throw e
        } catch (e: Exception) {
            throw MercuryException("Couldn't reach Mercury.", cause = e)
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 20_000
        const val PAGE_SIZE = 500

        /** Enough for years of a business account, and a bound so a paging loop cannot run away. */
        const val MAX_PAGES = 40
    }
}
