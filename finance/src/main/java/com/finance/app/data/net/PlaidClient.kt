package com.finance.app.data.net

import com.finance.app.logic.Accounts
import com.finance.app.logic.Bills
import com.finance.app.logic.Endpoints
import com.finance.app.logic.PlaidJson
import com.finance.app.logic.Transaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

/**
 * The Plaid half of Finance's network surface.
 *
 * Built on `HttpURLConnection` rather than an HTTP library, mirroring LifeOps' `NwsClient`,
 * Logistics' `RecipeFetcher`, Health's `DrugLookupClient` and Maintenance's `VehicleLookupClient` —
 * the suite has deliberately not taken an OkHttp/Retrofit dependency, and five endpoints do not
 * change that.
 *
 * ## Six calls, and the four this app deliberately does not make
 *
 * It makes exactly these: create a link token, exchange a public token for an access token, read
 * account balances, sync transactions, read liabilities, and look up an institution's name. Every
 * one of them returns data about something that already happened.
 *
 * What it does not have, and what no amount of configuration will turn on, is any of Plaid's
 * money-movement surface: nothing under `transfer`, `payment_initiation`, `signal` or
 * `bank_transfer` is reachable from this file. That is what the manifest's "it cannot move money"
 * promise rests on —
 * not a setting, not a scope, but the fact that the code to do it was never written. The same goes
 * for `/auth/get`, which would hand over full account and routing numbers: this app has no use for
 * them, so it does not ask.
 *
 * ## Credentials on a phone
 *
 * Plaid's own guidance is that a client secret belongs on a server, and that guidance is correct for
 * an application with many users. This suite has exactly one user, no server, and no intention of
 * acquiring either — so the household's own Plaid credentials live on the household's own phone,
 * behind the Keystore (see [com.finance.app.data.secure.FinanceSecrets]), and the honest thing is to
 * say plainly that this is a deliberate departure rather than to pretend the question doesn't
 * arise. What it buys is that nothing about this household's money ever passes through a server
 * belonging to whoever wrote this app, which for a single-user offline-first suite is the trade
 * worth making. The Connections screen states the same thing in the app.
 *
 * Every call is on `Dispatchers.IO`, throws [PlaidException] on failure, and happens only because
 * somebody pressed a button or opened the app. Nothing here runs on a timer or wakes the device.
 */
class PlaidClient(
    private val clientId: String,
    private val secret: String,
    private val environment: Endpoints.PlaidEnvironment
) {

    /**
     * Any reason a call didn't produce an answer, with something a person can read.
     *
     * [reauth] carries Plaid's `ITEM_LOGIN_REQUIRED` through to the caller as a distinct state,
     * because a connection that needs re-authorising is not a broken one and must not be presented
     * as an error somebody should report.
     */
    class PlaidException(
        message: String,
        val code: String? = null,
        val reauth: Boolean = false,
        val transient: Boolean = false,
        cause: Throwable? = null
    ) : IOException(message, cause)

    private val base: String get() = environment.baseUrl

    /**
     * Start a Hosted Link session. Returns the token to poll with and the page to open.
     *
     * Two things about the body are the app's promises expressed as a request rather than as prose:
     *
     * **The products are the read-only ones.** An access token minted for `transactions` and
     * `liabilities` cannot move money even by code that wanted to — the capability is not on it.
     *
     * **`hosted_link` means no redirect.** The person signs in on a page Plaid hosts and this app
     * asks [linkResult] afterwards what came of it, over the same authenticated channel as
     * everything else. The alternative — Plaid's Android SDK, or a redirect URI carrying a token
     * back into this process — would mean either a large third-party dependency or an App Link that
     * another installed app could contend for.
     */
    suspend fun startLink(
        userId: String,
        /**
         * The token of a connection being **repaired** rather than added.
         *
         * Passing it puts Plaid into *update mode*: the person re-authenticates the item they
         * already have, and the access token this app holds keeps working afterwards. There is no
         * new item, no new token, and — the part that matters — no second copy of the same bank in
         * the account list, quietly counted twice in net worth.
         */
        accessToken: String? = null
    ): PlaidJson.LinkStart = post(
        path = "/link/token/create",
        body = buildString {
            append("""{"client_id":${clientId.json()},"secret":${secret.json()},""")
            append(""""client_name":"LifeOps Finance","country_codes":["US"],"language":"en",""")
            if (accessToken == null) {
                append(""""products":["transactions"],"optional_products":["liabilities"],""")
            } else {
                // Update mode takes the item instead of a product list, and Plaid rejects the
                // request outright if both are sent — the products are already on the item.
                append(""""access_token":${accessToken.json()},""")
            }
            append(""""hosted_link":{},""")
            append(""""user":{"client_user_id":${userId.json()}}""")
            append("}")
        }
    ).let { body ->
        PlaidJson.linkStart(body) ?: throw PlaidException("Plaid didn't return a link token.")
    }

    /**
     * Whether a connection can be read again — the test for "did the repair work".
     *
     * Deliberately *not* a poll of `/link/token/get`. In update mode Plaid repairs the item in place
     * and there is no public token handed back, so the payload shape that says "finished" is both
     * different from the add case and not worth guessing at. The question this app actually cares
     * about is "can I read this account again", so that is the question asked: a successful balance
     * read is the repair, and a `reauth` error is "not yet". Anything else is a real failure and is
     * allowed to propagate.
     */
    suspend fun canRead(accessToken: String): Boolean = try {
        accounts(accessToken, connectionId = "probe").isNotEmpty()
    } catch (e: PlaidException) {
        if (e.reauth) false else throw e
    }

    /**
     * Ask what came of a Hosted Link session. Null means the person hasn't finished yet.
     *
     * Null is a normal answer and the reason this is a poll rather than a callback: the sign-in
     * happens in a browser this app does not control, and the only honest way to find out whether it
     * worked is to ask the party that would know.
     */
    suspend fun linkResult(linkToken: String): PlaidJson.LinkResult? = PlaidJson.linkResult(
        post(
            "/link/token/get",
            """{"client_id":${clientId.json()},"secret":${secret.json()},"link_token":${linkToken.json()}}"""
        )
    )

    /** Turn the public token Link handed back into the long-lived access token. */
    suspend fun exchange(publicToken: String): PlaidJson.Exchange = post(
        path = "/item/public_token/exchange",
        body = """{"client_id":${clientId.json()},"secret":${secret.json()},"public_token":${publicToken.json()}}"""
    ).let { body ->
        PlaidJson.exchange(body) ?: throw PlaidException("Plaid didn't return an access token.")
    }

    /** The accounts on an item, with their balances as of now. */
    suspend fun accounts(accessToken: String, connectionId: String): List<Accounts.Account> =
        PlaidJson.accounts(
            post("/accounts/balance/get", authed(accessToken)),
            connectionId
        )

    /**
     * One page of `/transactions/sync`.
     *
     * A page rather than the whole history, because Plaid returns up to 500 at a time and the caller
     * has to persist each page's cursor before asking for the next — otherwise a sync interrupted
     * halfway through re-fetches everything from the start on the next run, or worse, advances the
     * cursor past rows that were never written.
     */
    suspend fun sync(
        accessToken: String,
        cursor: String?,
        accountIdFor: (String) -> String?
    ): PlaidJson.Sync {
        val body = buildString {
            append("""{"client_id":${clientId.json()},"secret":${secret.json()},"access_token":${accessToken.json()}""")
            if (!cursor.isNullOrBlank()) append(""","cursor":${cursor.json()}""")
            append(""","count":500}""")
        }
        return PlaidJson.sync(post("/transactions/sync", body), accountIdFor)
            ?: throw PlaidException("Plaid's transaction sync didn't come back in a shape we recognise.")
    }

    /**
     * The bills the institution itself put a date on.
     *
     * An item without the liabilities product answers `PRODUCTS_NOT_SUPPORTED`, which is a normal
     * answer for a plain checking account rather than a failure — so it comes back as an empty list.
     * A household with no credit cards should not see an error on every refresh.
     */
    suspend fun liabilities(
        accessToken: String,
        accountIdFor: (String) -> String?,
        today: LocalDate
    ): List<Bills.Bill> = try {
        PlaidJson.liabilities(post("/liabilities/get", authed(accessToken)), accountIdFor, today)
    } catch (e: PlaidException) {
        if (e.code in NO_LIABILITIES) emptyList() else throw e
    }

    /** What to call the connection. Failure here is cosmetic, so the caller gets null rather than a throw. */
    suspend fun institutionName(institutionId: String): String? = runCatching {
        PlaidJson.institutionName(
            post(
                "/institutions/get_by_id",
                """{"client_id":${clientId.json()},"secret":${secret.json()},"institution_id":${institutionId.json()},"country_codes":["US"]}"""
            )
        )
    }.getOrNull()

    private fun authed(accessToken: String) =
        """{"client_id":${clientId.json()},"secret":${secret.json()},"access_token":${accessToken.json()}}"""

    /**
     * The one HTTP call in this file.
     *
     * Plaid's API is POST-only, including for reads — the credentials go in the body rather than in
     * a header, which is their design and not a choice available here.
     *
     * A non-2xx response is read from the **error** stream and parsed before being thrown, because
     * Plaid puts everything worth knowing in the body: which item, which institution, whether it
     * needs re-authorising, and a sentence written for the person rather than for a log.
     */
    private suspend fun post(path: String, body: String): String = withContext(Dispatchers.IO) {
        val url = "$base$path"
        // The allow-list, checked on every request rather than trusted from the constructor. A URL
        // that fails it is a programming error, and one that would have sent an access token
        // somewhere it should never go.
        require(Endpoints.permits(url)) { "Finance refused to open $path" }

        val connection = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw PlaidException("Couldn't reach Plaid.", cause = e)
        }

        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = connection.responseCode
            if (code in 200..299) {
                return@withContext connection.inputStream.bufferedReader().use { it.readText() }
            }

            val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val parsed = PlaidJson.error(errorBody)
            throw PlaidException(
                message = parsed?.message ?: "Plaid answered $code.",
                code = parsed?.code,
                reauth = parsed?.reauth == true,
                transient = parsed?.transient == true || code == 429 || code >= 500
            )
        } catch (e: PlaidException) {
            throw e
        } catch (e: Exception) {
            throw PlaidException("Couldn't reach Plaid.", cause = e)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * A string as a JSON literal.
     *
     * These bodies are built by hand rather than serialised, which is fine for six fixed shapes and
     * would not be fine for one more — but a token containing a quote or a backslash would produce a
     * malformed body and an error nobody could read, so escaping goes through here rather than
     * through string interpolation.
     */
    private fun String.json(): String {
        val escaped = buildString(length + 2) {
            append('"')
            this@json.forEach { char ->
                when (char) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (char < ' ') append("\\u%04x".format(char.code)) else append(char)
                }
            }
            append('"')
        }
        return escaped
    }

    private companion object {
        const val TIMEOUT_MS = 20_000

        /** An item that simply doesn't have liabilities to report. Not a failure. */
        val NO_LIABILITIES = setOf(
            "PRODUCTS_NOT_SUPPORTED",
            "PRODUCT_NOT_ENABLED",
            "NO_LIABILITY_ACCOUNTS",
            "NO_ACCOUNTS"
        )
    }
}

/** Convenience for the sync loop: every page, with the cursor persisted after each one. */
suspend fun PlaidClient.syncAll(
    accessToken: String,
    startCursor: String?,
    accountIdFor: (String) -> String?,
    /**
     * Called with each page before the next is requested. Persist the rows **and** the cursor in
     * here — the cursor is only safe to advance once the rows it covers are on disk.
     */
    onPage: suspend (added: List<Transaction>, modified: List<Transaction>, removed: List<String>, cursor: String) -> Unit
) {
    var cursor = startCursor
    var pages = 0
    while (pages < MAX_SYNC_PAGES) {
        val page = sync(accessToken, cursor, accountIdFor)
        onPage(page.added, page.modified, page.removedProviderIds, page.nextCursor)
        cursor = page.nextCursor
        pages++
        if (!page.hasMore) return
    }
}

/**
 * Two years of a busy household's transactions at 500 a page, with room to spare.
 *
 * A bound rather than a `while (hasMore)`, because the loop's exit depends on a remote server
 * setting a flag, and a sync that never terminates is a phone that never sleeps.
 */
private const val MAX_SYNC_PAGES = 80
