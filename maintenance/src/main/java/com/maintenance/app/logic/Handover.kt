package com.maintenance.app.logic

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One line of a history, as it goes into the file. */
data class HandoverRow(
    val performedAt: Long,
    val title: String,
    val vendor: String? = null,
    val meterValue: Long? = null,
    val costCents: Long = 0L,
    val notes: String? = null
)

/**
 * A service history, as a file you can hand to somebody else.
 *
 * This exists for one day: the day you sell the thing. A car's history is the most useful thing you
 * own about it right up until then, and on that day it is worth money — and the app's backup is no
 * use at all, because it is a whole-suite restore into an app the buyer does not have. So the
 * history leaves as **CSV**, which every spreadsheet and every stranger can open.
 *
 * Three rules, all of them about the file being read by something other than this app:
 *
 * - **Money is plain.** `1234.56`, no symbol and no thousands comma, so a column of it adds up.
 * - **Dates are ISO.** `2024-03-05` sorts correctly as text and is unambiguous in every country,
 *   which `03/05/2024` is not.
 * - **The meter column is named.** "Odometer" or "Hour meter" rather than a bare number, because
 *   the buyer of a mower and the buyer of a truck are reading different things.
 *
 * It is deliberately only the history. What the thing *is* goes in the file name, where it labels
 * the file in somebody's downloads folder; putting a preamble above the header would make the file
 * unopenable as a table, which is the one job it has.
 */
object Handover {

    /**
     * `2018-jeep-wrangler-service-history-2026-09-02.csv`.
     *
     * The date is in it because these get sent more than once — a buyer asks in March and again
     * after the service in May — and two files called the same thing in a downloads folder is how
     * the wrong one gets sent.
     */
    fun fileName(descriptor: String, on: LocalDate): String {
        val stem = descriptor.lowercase()
            .map { if (it.isLetterOrDigit()) it else '-' }
            .joinToString("")
            .split('-')
            .filter { it.isNotEmpty() }
            .joinToString("-")
            .ifEmpty { "asset" }
        return "$stem-service-history-$on.csv"
    }

    /**
     * The history as CSV, newest work first — the order it is read in, and the same order the app
     * shows it.
     *
     * A history with nothing in it still returns its header row: an empty file looks like a failed
     * export, and a header with no rows says plainly that nothing was ever logged.
     */
    fun csv(rows: List<HandoverRow>, meterUnit: MeterUnit?, zone: ZoneId = ZoneId.systemDefault()): String {
        val meterHeader = meterUnit?.reading ?: "Meter"
        val header = listOf("Date", "What", "Vendor", meterHeader, "Cost", "Notes")
        val body = rows.sortedByDescending { it.performedAt }.map { row ->
            listOf(
                day(row.performedAt, zone).toString(),
                row.title,
                row.vendor.orEmpty(),
                row.meterValue?.toString().orEmpty(),
                Money.plain(row.costCents),
                row.notes.orEmpty()
            )
        }
        return (listOf(header) + body).joinToString("\n") { fields -> fields.joinToString(",") { escape(it) } }
    }

    /**
     * RFC 4180: a field is quoted when it contains a comma, a quote or a newline, and a quote
     * inside it is doubled.
     *
     * Notes are the field that makes this matter — they are free text, and *"replaced belt, £40
     * cheaper than the dealer"* would otherwise become two columns and shift every later field by
     * one, silently, in a file somebody is reading to decide what your car is worth.
     */
    private fun escape(value: String): String {
        val needsQuotes = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        val body = value.replace("\"", "\"\"")
        return if (needsQuotes) "\"$body\"" else body
    }

    private fun day(millis: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
}
