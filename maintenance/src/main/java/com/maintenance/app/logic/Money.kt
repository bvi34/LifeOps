package com.maintenance.app.logic

import com.operations.suitekit.SuiteMoney

/**
 * Maintenance's name for the suite's money arithmetic.
 *
 * The rules — cents as a `Long`, a symbol passed in rather than decided here, a third decimal place
 * refused as the typo it usually is — now live in [SuiteMoney], because the field that collects
 * these amounts is the suite's and had to agree with them. Nothing about the behaviour moved.
 */
object Money {

    fun format(amountCents: Long, symbol: String = "$", cents: Boolean = true): String =
        SuiteMoney.format(amountCents, symbol, cents)

    fun plain(amountCents: Long): String = SuiteMoney.plain(amountCents)

    fun parse(raw: String): Long? = SuiteMoney.parse(raw)

    fun scale(amountCents: Long, factor: Double): Long = SuiteMoney.scale(amountCents, factor)
}
