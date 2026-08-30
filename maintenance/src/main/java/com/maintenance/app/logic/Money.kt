package com.maintenance.app.logic

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Money, in whole cents, formatted and parsed without a locale doing anything surprising.
 *
 * Everything monetary in Maintenance is a `Long` of cents. A mortgage balance in floating point is
 * a mortgage balance that drifts, and the app's whole claim is that these numbers are the ones on
 * your statement.
 *
 * The currency *symbol* is a parameter rather than something this file decides. Resolving it from
 * the device locale is the screen's job (`Locale`-aware, done once); doing it in here would make
 * every one of these functions answer differently on a French phone and every test a small lie.
 */
object Money {

    /** 123456 → "$1,234.56". [cents] false drops the decimals for a figure read at a glance. */
    fun format(amountCents: Long, symbol: String = "$", cents: Boolean = true): String {
        val negative = amountCents < 0
        val magnitude = abs(amountCents)
        val whole = magnitude / 100
        val remainder = magnitude % 100
        val grouped = whole.toString().reversed().chunked(3).joinToString(",").reversed()
        val body = if (cents) "$symbol$grouped.${remainder.toString().padStart(2, '0')}" else "$symbol$grouped"
        return if (negative) "-$body" else body
    }

    /**
     * "1,234.56", "$1,234.56", "1234" → cents; anything else → null.
     *
     * Deliberately forgiving about what people paste out of a bank app (symbols, spaces, grouping
     * commas) and deliberately unforgiving about a third decimal place, which is a typo far more
     * often than it is a fraction of a cent.
     */
    fun parse(raw: String): Long? {
        val cleaned = raw.trim().filterNot { it == ',' || it == ' ' || it == '$' || it == '_' }
        if (cleaned.isEmpty()) return null
        val negative = cleaned.startsWith("-")
        val body = cleaned.removePrefix("-").removePrefix("+")
        if (body.isEmpty() || body.any { !it.isDigit() && it != '.' }) return null
        val parts = body.split(".")
        if (parts.size > 2) return null
        val whole = parts[0].ifEmpty { "0" }.toLongOrNull() ?: return null
        val fraction = if (parts.size == 2) parts[1] else ""
        if (fraction.length > 2) return null
        val cents = when (fraction.length) {
            0 -> 0L
            1 -> fraction.toLong() * 10
            else -> fraction.toLong()
        }
        val total = whole * 100 + cents
        return if (negative) -total else total
    }

    /** Scale an amount without letting the rounding out of this file. */
    fun scale(amountCents: Long, factor: Double): Long = (amountCents * factor).roundToLong()
}
