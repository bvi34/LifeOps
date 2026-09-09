package com.finance.app.logic

import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * Money that moved between two accounts the household already owns.
 *
 * ## The problem this exists for
 *
 * Connect checking and savings, move $500 between them, and two rows arrive: `-500` on one account
 * and `+500` on the other. Both are real, and neither is spending or income — the household has
 * exactly as much money as before. Counted naively, the month shows $500 more spent *and* $500 more
 * earned, nets out correctly, and reports two totals that are both wrong.
 *
 * The same shape, and worse, for a card payment: $600 of groceries on the card is $600 of spending,
 * and the $600 that clears the card is the same money arriving where it was always going.
 *
 * ## Why the category was not enough
 *
 * [Transaction.transfer] was previously set from the provider's own category alone — Plaid's
 * `TRANSFER_IN`/`TRANSFER_OUT`, Mercury's `internalTransfer`. That catches a good share and misses
 * the rest, because a provider labels a transaction by what it *looks like* from one side, with no
 * idea that the other side of it is also an account this household holds. Mercury's labelling is
 * keyword-based on our side, which is weaker still.
 *
 * Pairing is the structural answer: if a debit on one account is matched by a credit of the same
 * size on another account within a few days, the money did not leave the household, whatever either
 * provider chose to call it.
 *
 * ## What keeps it from over-matching
 *
 * Four conditions at once, and a payment is consumed by at most one pairing:
 *
 * - **Opposite signs.** One out, one in.
 * - **The same magnitude, exactly.** Not "close": a transfer is one instruction and both institutions
 *   report the same number. Loosening it to a tolerance would start pairing a $50 shop with an
 *   unrelated $50 refund.
 * - **Different accounts.** Two rows on one account are not two sides of anything.
 * - **Within [WINDOW_DAYS].** An ACH between two banks lands a day or three later; a fortnight apart
 *   is two separate things that happen to be the same size.
 *
 * It will still occasionally be wrong — buy something for $40 on the same day a $40 refund lands
 * elsewhere and the pair looks identical from here. That is a category error on two rows in a list,
 * against fixing a headline total that was wrong every month, and the trade is worth taking.
 */
object Transfers {

    /** How far apart the two halves of one transfer may land. */
    const val WINDOW_DAYS = 3L

    /**
     * Flag both halves of every internal transfer found in [transactions].
     *
     * Rows already marked (by a provider that got there first) are left marked and are not offered
     * for pairing — there is nothing to gain from pairing something already excluded, and it would
     * consume a partner some other row genuinely needs.
     *
     * Pending rows are never paired: their amounts change, so a match found today can stop being one
     * tomorrow, and a flag that flickers is worse than one that is late.
     */
    fun mark(transactions: List<Transaction>): List<Transaction> {
        val paired = pairedIds(transactions)
        if (paired.isEmpty()) return transactions
        return transactions.map { if (it.id in paired) it.copy(transfer = true) else it }
    }

    /**
     * The ids of every row that is one half of an internal transfer.
     *
     * Separated from [mark] because it is the part worth asserting about directly — a test that has
     * to reconstruct which rows got flagged from a list of copies is a test nobody reads twice.
     */
    fun pairedIds(transactions: List<Transaction>): Set<String> {
        val candidates = transactions.filter { !it.pending && !it.transfer && it.amountCents != 0L }
        if (candidates.size < 2) return emptySet()

        val found = mutableSetOf<String>()
        // Grouped by size first: two halves of one transfer agree exactly, so this is the cheap way
        // to avoid comparing every row against every other one.
        candidates.groupBy { abs(it.amountCents) }.forEach { (_, sameSize) ->
            if (sameSize.size < 2) return@forEach
            val outgoing = sameSize.filter { it.outflow }.sortedBy { it.date }
            val incoming = sameSize.filter { it.inflow }.sortedBy { it.date }.toMutableList()

            for (out in outgoing) {
                // The nearest unclaimed credit on another account. Nearest rather than first, so that
                // three transfers of the same size in one week pair up the way they actually
                // happened rather than the way the list happened to be ordered.
                val match = incoming
                    .filter { it.accountId != out.accountId }
                    .filter { abs(ChronoUnit.DAYS.between(out.date, it.date)) <= WINDOW_DAYS }
                    .minByOrNull { abs(ChronoUnit.DAYS.between(out.date, it.date)) }
                    ?: continue
                incoming.remove(match)
                found += out.id
                found += match.id
            }
        }
        return found
    }
}
