package com.finance.app.data.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Finance's tables. Four of them, and the shape is almost entirely determined by two decisions.
 *
 * **Nothing here is a secret.** No access token, no API key, no full account number, no routing
 * number. This file is what the sandbox backup copies into a zip, so anything in it is something the
 * household is content to have on a laptop — which is why the tokens live in
 * [com.finance.app.data.secure.FinanceSecrets] behind the Keystore and a `connections` row holds
 * only a name. It also means [AccountEntity.mask] is four digits by construction: it is written from
 * [com.finance.app.logic.Masking.truncate] at the parser, and there is no column for the rest.
 *
 * **Ids are derived, not minted.** An account's id is `connectionId:providerAccountId` and a
 * transaction's is `accountId:providerTransactionId`, computed in the parsers. A refresh therefore
 * lands on the same rows by construction rather than by a matching pass that could get it wrong —
 * which matters because the alternative failure is silent: fresh ids on every refresh would orphan
 * every bill link and quietly double every balance.
 *
 * Money is cents everywhere, and dates are epoch days. A balance in floating point is a balance that
 * drifts, and the app's whole claim is that these numbers are the ones on your statement.
 *
 * Nothing here is synced. Finance is not a peer on the suite's sync spine and no other app writes to
 * a balance, so there is no `syncVersion` column in this file.
 */

/**
 * One institution this household connected, and how.
 *
 * [provider] decides which client reads it and what it can offer: a Plaid connection can report
 * statement due dates, a Mercury one cannot. [needsReauth] is Plaid's `ITEM_LOGIN_REQUIRED` made
 * durable — the bank wants the person to sign in again, which happens every few months and is not
 * an error, so it is a state the Connections screen shows rather than a failure it reports.
 */
@Entity(
    tableName = "connections",
    indices = [Index("provider")]
)
data class ConnectionEntity(
    @PrimaryKey val id: String,
    /** `logic/Provider.key` — "plaid" or "mercury". */
    val provider: String,
    /** What to call it: "USAA", "Mercury". Typed or read from the provider, never a secret. */
    val displayName: String,
    /** Plaid's institution id, for looking the name up again. Null for Mercury. */
    val institutionId: String?,
    /** Plaid's item id, so a webhook or a support question can name the thing. Null for Mercury. */
    val itemId: String?,
    val addedAt: Long,
    val lastSyncedAt: Long?,
    @ColumnInfo(defaultValue = "0")
    val needsReauth: Boolean = false,
    /** The last thing that went wrong, for the Connections screen. Cleared by a good refresh. */
    val lastError: String? = null
)

/**
 * One account at one institution.
 *
 * [currentCents] is the institution's own number with no sign applied — see
 * [com.finance.app.logic.Accounts] for why the direction lives on the kind instead.
 *
 * [includeInPicture] is the household's decision, not the bank's: a custodial account for a child,
 * or a business account that shouldn't be mixed into personal net worth, still refreshes and still
 * shows on its own page — it just stops counting towards the figure on the front screen.
 */
@Entity(
    tableName = "accounts",
    foreignKeys = [
        ForeignKey(
            entity = ConnectionEntity::class,
            parentColumns = ["id"],
            childColumns = ["connectionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("connectionId"), Index("kind"), Index("closed")]
)
data class AccountEntity(
    @PrimaryKey val id: String,
    val connectionId: String,
    val providerAccountId: String,
    val name: String,
    val officialName: String?,
    /** Four digits, or null. There is no column for more than four — see the file note. */
    val mask: String?,
    /** `logic/AccountKind.key`. */
    val kind: String,
    val currentCents: Long,
    /** Null where the institution doesn't report one, which is different from zero. */
    val availableCents: Long?,
    /** The credit line, for a card. Null for everything else. */
    val limitCents: Long?,
    val currency: String,
    @ColumnInfo(defaultValue = "1")
    val includeInPicture: Boolean = true,
    @ColumnInfo(defaultValue = "0")
    val closed: Boolean = false,
    val sortOrder: Int = 0
)

/**
 * One movement of money.
 *
 * [amountCents] is negative for money out, everywhere, whichever provider it came from — the flip
 * happens once, in the parser (see [com.finance.app.logic.Transaction]).
 *
 * [dateEpochDay] is the *posted* day. Indexed because every roll-up in the app is a range scan over
 * it, and a household with two years of history has tens of thousands of rows.
 */
@Entity(
    tableName = "transactions",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("accountId"), Index("dateEpochDay"), Index("category"), Index("merchantKey")]
)
data class TransactionEntity(
    @PrimaryKey val id: String,
    val accountId: String,
    val providerTransactionId: String,
    val dateEpochDay: Long,
    val amountCents: Long,
    val description: String,
    val merchant: String?,
    /** `logic/Category.key`. */
    val category: String,
    /**
     * [com.finance.app.logic.Merchants.key] of the label, stored rather than computed on read.
     *
     * It is a pure function of the row, so this is denormalisation — and it earns it: recurring
     * detection groups by it over every transaction in the database, and computing it in Kotlin for
     * forty thousand rows on every refresh is the difference between a screen that opens and one
     * that stutters.
     */
    val merchantKey: String,
    @ColumnInfo(defaultValue = "0")
    val pending: Boolean = false,
    @ColumnInfo(defaultValue = "0")
    val transfer: Boolean = false
)

/**
 * One obligation with a date on it.
 *
 * [source] is load bearing rather than decorative: a statement bill is the institution's word, a
 * predicted one is this app's arithmetic, and showing them in the same typeface would be the app
 * lending its own guesses the bank's credibility.
 *
 * [lifeOpsTaskId] and [publishedDueEpochDay] are the seam onto the LifeOps week
 * ([com.finance.app.logic.BillTasks]). The second outlives the first on purpose: it is what
 * remembers that a task was published for this date, and therefore what stops the app putting back
 * a task somebody deleted.
 *
 * There is deliberately **no foreign key onto `accounts`**, unlike everything else here. A bill can
 * outlive the account it was read from — a card closed and re-linked under a new id, a connection
 * removed and added back — and a cascade would take a genuinely-owed obligation with it. Rows whose
 * account has gone are shown without one rather than deleted.
 */
@Entity(
    tableName = "bills",
    indices = [Index("accountId"), Index("dueEpochDay"), Index("paidOnEpochDay"), Index("merchantKey")]
)
data class BillEntity(
    @PrimaryKey val id: String,
    val accountId: String,
    val payee: String,
    val dueEpochDay: Long,
    val amountCents: Long,
    val minimumCents: Long?,
    /** `logic/Bills.Source.key`. */
    val source: String,
    /** `logic/Category.key`. */
    val category: String,
    val merchantKey: String?,
    /** Null while outstanding. Set by a matched payment or by a tick on the week. */
    val paidOnEpochDay: Long?,
    @ColumnInfo(defaultValue = "0")
    val autopay: Boolean = false,
    @ColumnInfo(defaultValue = "1")
    val publishToWeek: Boolean = true,
    val lifeOpsTaskId: String? = null,
    val publishedDueEpochDay: Long? = null
)
