package com.logistics.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The extracted-text shape and specific rows here are taken verbatim from a real Walmart
 * "Order details" PDF, so this suite guards the exact edge cases that file exercises — most
 * importantly the header running straight into the first product name
 * ("…51271198Malt-O-Meal…") and sizes like "47 oz" that must survive name cleaning.
 */
class WalmartOrderParserTest {

    // Mirrors PDF text extraction: rows are run together, each product ending in a `Qty n$price`
    // stamp, wrapped by the invoice header and footer.
    private val sample = buildString {
        append("InvoiceJul 31, 2026 orderOrder# 2000151-51271198")
        append("Malt-O-Meal S'mores Breakfast Cereal, Graham, Chocolate & Marshmallow, 47 oz BagQty 1\$7.83")
        append("Hefty Press to Close Extra Large Sandwich Bags, 100 CountQty 2\$6.56")
        append("Great Value Boneless Skinless Diced Chicken Breast, 2 lb (Frozen)Qty 2\$15.88")
        append("Fresh Banana, EachQty 12\$2.40")
        append("A&W Root Beer Soda Pop, 12 fl oz, 12 Pack CansQty 2\$16.84")
        append("Subtotal\$451.43Savings-\$11.06\$440.37Tax\$30.00Total\$470.37Order# 2000151-51271198")
    }

    @Test
    fun recoversOrderNumberAndItemCount() {
        val order = WalmartOrderParser.parse(sample)
        assertEquals("2000151-51271198", order.orderNumber)
        // Five products; the Subtotal/Tax/Total footer carries no Qty stamp, so it's excluded.
        assertEquals(5, order.lines.size)
    }

    @Test
    fun firstItemKeepsBrandAndSizeDespiteRunTogetherHeader() {
        val first = WalmartOrderParser.parse(sample).lines.first()
        assertEquals(
            "Malt-O-Meal S'mores Breakfast Cereal, Graham, Chocolate & Marshmallow, 47 oz Bag",
            first.rawName
        )
        assertEquals(1, first.quantity)
        assertEquals("Bag", first.unit)
        assertEquals(783, first.priceCents)
    }

    @Test
    fun parsesQuantityAndPrice() {
        val lines = WalmartOrderParser.parse(sample).lines
        val banana = lines.first { it.rawName.contains("Banana") }
        assertEquals(12, banana.quantity)
        assertEquals(240, banana.priceCents)
        assertEquals("each", banana.unit)
    }

    @Test
    fun ignoresFooterTotalsAsItems() {
        val lines = WalmartOrderParser.parse(sample).lines
        assertTrue(lines.none { it.rawName.contains("Subtotal", ignoreCase = true) })
        assertTrue(lines.none { it.rawName.contains("Total", ignoreCase = true) })
    }

    @Test
    fun infersPackagingUnitAndCategory() {
        val lines = WalmartOrderParser.parse(sample).lines
        val bags = lines.first { it.rawName.contains("Sandwich Bags") }
        assertEquals("Count", bags.unit)
        assertEquals("Household", bags.category)

        val soda = lines.first { it.rawName.contains("Root Beer") }
        assertEquals("Beverages", soda.category)
    }

    @Test
    fun returnsEmptyForNonOrderText() {
        val order = WalmartOrderParser.parse("Just some random text with no items.")
        assertNull(order.orderNumber)
        assertTrue(order.lines.isEmpty())
    }
}
