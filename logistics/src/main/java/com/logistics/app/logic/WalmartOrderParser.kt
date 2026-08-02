package com.logistics.app.logic

import com.logistics.app.data.model.ParsedOrder
import com.logistics.app.data.model.ParsedOrderLine

/**
 * Turns the text extracted from a Walmart "Order details / Invoice" PDF (or pasted order text) into
 * structured pantry lines. Framework-free and deterministic so it's fully unit-tested on the JVM;
 * the Android side only supplies the raw text (see [com.logistics.app.net.PdfTextExtractor]).
 *
 * The extracted text runs the item rows together with no line breaks — each product is followed by a
 * `Qty <n>$<price>` stamp, e.g.
 *
 * ```
 * …Order# 2000151-51271198Malt-O-Meal S'mores…, 47 oz BagQty 1$7.83Hefty…Qty 2$6.56…
 * ```
 *
 * so the reliable delimiter is that `Qty n$price` stamp: the product name is everything since the
 * previous stamp (or since the order-number header for the first item). The invoice footer
 * (`Subtotal$… Tax$… Total$…`) carries no `Qty` stamp, so it's naturally excluded.
 */
object WalmartOrderParser {

    // "Order# 2000151-51271198". The number is digit/hyphen shaped (digit groups joined by hyphens),
    // so it stops cleanly at the product name that immediately follows it in the run-together text
    // ("…51271198Malt-O-Meal…") and never swallows letters.
    private val ORDER_LABEL = Regex("""Order#\s*(\d+(?:-\d+)*)""")

    // Qty <n> $ <price>. Price allows thousands commas and requires cents, so "Qty 1$7.83" and
    // "Qty 12$2.40" both match while stray dollar amounts in a name (there are none) would not.
    private val ITEM_STAMP = Regex("""Qty\s*(\d+)\s*\$\s*(\d[\d,]*\.\d{2})""")

    // Header noise ("Invoice Jul 31, 2026 order") that can lead the first item's name when the text
    // has no "Order#" label to anchor the header end (e.g. pasted text).
    private val HEADER_JUNK = Regex("""Invoice.*?order""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    // Non-breaking space; PDF text extraction often emits these between words.
    private const val NBSP = ' '

    fun parse(text: String): ParsedOrder {
        val normalized = text.replace(NBSP, ' ').replace('\r', ' ')
        val orderNumber = ORDER_LABEL.find(normalized)?.groupValues?.get(1)

        val stamps = ITEM_STAMP.findAll(normalized).toList()
        if (stamps.isEmpty()) return ParsedOrder(orderNumber, emptyList())

        // Names begin after the header. If an "Order# <num>" label sits before the first stamp, the
        // first product name starts right after it; otherwise start at the top of the text.
        val firstStamp = stamps.first().range.first
        val headerEnd = ORDER_LABEL.findAll(normalized)
            .map { it.range.last + 1 }
            .filter { it <= firstStamp }
            .lastOrNull() ?: 0

        val lines = mutableListOf<ParsedOrderLine>()
        var cursor = headerEnd
        for (stamp in stamps) {
            val rawName = normalized.substring(cursor, stamp.range.first)
            cursor = stamp.range.last + 1

            val name = cleanName(rawName)
            if (name.isBlank()) continue

            val qty = stamp.groupValues[1].toIntOrNull() ?: 1
            val priceCents = parsePriceCents(stamp.groupValues[2])
            lines.add(
                ParsedOrderLine(
                    rawName = name,
                    quantity = qty,
                    unit = PantryUnits.guessUnit(name),
                    category = PantryUnits.guessCategory(name),
                    priceCents = priceCents
                )
            )
        }
        return ParsedOrder(orderNumber, lines)
    }

    private fun cleanName(raw: String): String {
        // Drop any header fragment that may lead the first name when there was no "Order#" label to
        // anchor on. We deliberately do NOT strip bare numbers here — sizes like "47 oz" are part of
        // the product name; the header is already excluded by [headerEnd] when a label is present.
        var s = HEADER_JUNK.replace(raw, " ")
        s = s.replace(Regex("""\s+"""), " ").trim()
        s = s.trim(' ', ',', '-')
        return s.trim()
    }

    private fun parsePriceCents(price: String): Int? {
        val cleaned = price.replace(",", "")
        val dot = cleaned.indexOf('.')
        if (dot < 0) return cleaned.toIntOrNull()?.let { it * 100 }
        val dollars = cleaned.substring(0, dot).toIntOrNull() ?: return null
        val cents = cleaned.substring(dot + 1).padEnd(2, '0').take(2).toIntOrNull() ?: return null
        return dollars * 100 + cents
    }
}
