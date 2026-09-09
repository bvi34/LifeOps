package com.finance.app.logic

/**
 * What Finance is allowed to hold and show of an account number, and what it must not.
 *
 * The rule is one line: **the last four digits, and never more.** Everything in this module that
 * touches an account number goes through here, so the rule is a tested function rather than a habit
 * nine call sites are each expected to remember.
 *
 * The reason is not paranoia about the screen — it is about what is *stored*. Full account and
 * routing numbers are the two strings that let somebody move money, and this app is a read-only
 * picture: it never needs them, so it never keeps them. Plaid will hand over a full account number
 * through its `auth` product and Mercury returns one on every account; both are reduced to four
 * digits by [truncate] at the point the payload is parsed, before anything is written to the
 * database. A backup of this app therefore cannot leak an account number, because the number was
 * never in it.
 */
object Masking {

    /** How many trailing digits are kept. Four is what a statement prints and what a person checks. */
    const val KEPT = 4

    /**
     * Reduce anything account-number-shaped to the last four digits, or null when there aren't four.
     *
     * Non-digits are dropped first, so this handles the several shapes providers use for the same
     * value — `"000123456789"`, `"1234-5678-9012-3456"`, `"••••3456"` — and returns `"3456"` for the
     * last of those rather than treating the bullets as characters worth counting.
     *
     * A value with fewer than four digits is refused rather than padded: `"12"` masked to `"0012"`
     * would be a number this app invented, and the honest answer to "what are the last four" when
     * there are only two is that we don't have them.
     */
    fun truncate(raw: String?): String? {
        val digits = raw?.filter { it.isDigit() } ?: return null
        if (digits.length < KEPT) return null
        return digits.takeLast(KEPT)
    }

    /** `"3456"` → `"••3456"`. The dots say "there was more of this" without pretending to a length. */
    fun render(mask: String?): String? = truncate(mask)?.let { "••$it" }

    /** `"Classic Checking"` + `"3456"` → `"Classic Checking ••3456"`. The name alone if there's no mask. */
    fun label(name: String, mask: String?): String {
        val rendered = render(mask) ?: return name
        return "$name $rendered"
    }

    /**
     * True when [value] looks like a full account, card or routing number — something this module
     * must never write down.
     *
     * Used as an assertion at the parser boundary rather than as a filter: nothing here strips a
     * number out of a field it found one in, because a merchant name that trips this is a merchant
     * name we should look at, not one we should silently rewrite. Seven digits is the floor because
     * an ABA routing number is nine and the shortest real account numbers are eight; four-digit
     * masks and years must not trip it.
     */
    fun looksLikeFullNumber(value: String?): Boolean {
        val digits = value?.filter { it.isDigit() } ?: return false
        return digits.length >= 7
    }
}
