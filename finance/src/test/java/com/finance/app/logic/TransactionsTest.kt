package com.finance.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The merchant key is what recurring detection stands on, so it is tested against the shapes real
 * bank descriptions actually take rather than against tidy strings.
 */
class MerchantsTest {

    @Test
    fun `the same monthly bill keys the same despite its changing reference number`() {
        val april = Merchants.key("CITY UTILITIES 0423 AUTOPAY")
        val may = Merchants.key("CITY UTILITIES 0524 AUTOPAY")
        assertEquals(april, may)
        assertEquals("CITY UTILITIES AUTOPAY", april)
    }

    @Test
    fun `payment-network routing is not who was paid`() {
        assertEquals(Merchants.key("SQ *BLUE BOTTLE"), Merchants.key("BLUE BOTTLE"))
        assertEquals(Merchants.key("POS DEBIT SAFEWAY 1042"), Merchants.key("SAFEWAY"))
        assertEquals(Merchants.key("ACH DEBIT USAA INSURANCE"), Merchants.key("USAA INSURANCE"))
    }

    @Test
    fun `two names for one shop meet in the middle at three words`() {
        assertEquals(
            Merchants.key("AMAZON MKTPLACE PMTS AMZN COM BILL WA"),
            Merchants.key("AMAZON MKTPLACE PMTS")
        )
    }

    @Test
    fun `different payees keep different keys`() {
        assertNotEquals(Merchants.key("NETFLIX.COM"), Merchants.key("SPOTIFY USA"))
        assertNotEquals(Merchants.key("USAA INSURANCE"), Merchants.key("USAA MORTGAGE"))
    }

    @Test
    fun `cleaning tidies without renaming`() {
        assertEquals("SAFEWAY 1042", Merchants.clean("  SAFEWAY   1042  "))
        assertEquals("BLUE BOTTLE", Merchants.clean("BLUE BOTTLE *"))
        // A description that is only noise is left as it was rather than blanked, because a blank
        // row is a row nobody can identify later.
        assertEquals("***", Merchants.clean("***"))
    }
}

class CategoriesTest {

    @Test
    fun `plaid primaries map down to the sixteen this app shows`() {
        assertEquals(Category.INCOME, Categories.fromPlaid("INCOME", "INCOME_WAGES"))
        assertEquals(Category.TRANSFER, Categories.fromPlaid("TRANSFER_OUT", "TRANSFER_OUT_ACCOUNT_TRANSFER"))
        assertEquals(Category.MEDICAL, Categories.fromPlaid("MEDICAL", "MEDICAL_PRIMARY_CARE"))
        assertEquals(Category.SHOPPING, Categories.fromPlaid("GENERAL_MERCHANDISE", null))
    }

    @Test
    fun `groceries and eating out are the same plaid primary and must not be merged`() {
        assertEquals(Category.GROCERIES, Categories.fromPlaid("FOOD_AND_DRINK", "FOOD_AND_DRINK_GROCERIES"))
        assertEquals(Category.DINING, Categories.fromPlaid("FOOD_AND_DRINK", "FOOD_AND_DRINK_RESTAURANT"))
    }

    @Test
    fun `rent and utilities are the same plaid primary and must not be merged either`() {
        assertEquals(Category.HOUSING, Categories.fromPlaid("RENT_AND_UTILITIES", "RENT_AND_UTILITIES_RENT"))
        assertEquals(Category.UTILITIES, Categories.fromPlaid("RENT_AND_UTILITIES", "RENT_AND_UTILITIES_GAS_AND_ELECTRICITY"))
    }

    @Test
    fun `only the suffix of the detailed string carries information`() {
        // Plaid's detailed category repeats its primary, so "RENT_AND_UTILITIES_GAS_AND_ELECTRICITY"
        // contains the word RENT and "TRANSPORTATION_TAXIS_AND_RIDE_SHARES" contains no fuel at all.
        // Matching the whole string filed the heating bill under rent.
        assertEquals(Category.UTILITIES, Categories.fromPlaid("RENT_AND_UTILITIES", "RENT_AND_UTILITIES_WATER"))
        assertEquals(Category.UTILITIES, Categories.fromPlaid("RENT_AND_UTILITIES", "RENT_AND_UTILITIES_GAS_AND_ELECTRICITY"))
        assertEquals(Category.HOUSING, Categories.fromPlaid("RENT_AND_UTILITIES", "RENT_AND_UTILITIES_RENT"))
    }

    @Test
    fun `fuel is reached through the detailed string because plaid has no fuel primary`() {
        assertEquals(Category.FUEL, Categories.fromPlaid("TRANSPORTATION", "TRANSPORTATION_GAS"))
        assertEquals(Category.TRANSPORT, Categories.fromPlaid("TRANSPORTATION", "TRANSPORTATION_PARKING"))
    }

    @Test
    fun `progress and leakage are kept apart`() {
        assertEquals(Category.DEBT, Categories.fromPlaid("LOAN_PAYMENTS", "LOAN_PAYMENTS_CAR_PAYMENT"))
        assertEquals(Category.FEES, Categories.fromPlaid("BANK_FEES", "BANK_FEES_OVERDRAFT_FEES"))
    }

    @Test
    fun `an unknown primary is other rather than a guess`() {
        assertEquals(Category.OTHER, Categories.fromPlaid("SOMETHING_NEW", null))
        assertEquals(Category.OTHER, Categories.fromPlaid(null, null))
    }

    @Test
    fun `mercury is read from its kind first and its description second`() {
        assertEquals(Category.TRANSFER, Categories.fromMercuryDescription("Transfer to savings", "internalTransfer"))
        assertEquals(Category.FEES, Categories.fromMercuryDescription("Wire fee", "fee"))
        assertEquals(Category.INCOME, Categories.fromMercuryDescription("Gusto payroll", "incomingDomesticWire"))
        assertEquals(Category.OTHER, Categories.fromMercuryDescription("Some vendor", "card"))
    }
}

class TransactionSignTest {

    @Test
    fun `negative is money out, positive is money in - everywhere below the parsers`() {
        val shop = txn("2026-03-04", -40.0)
        val pay = txn("2026-03-05", 2_400.0)
        assertTrue(shop.outflow)
        assertTrue(pay.inflow)
        assertEquals(-4_000L, shop.amountCents)
    }

    @Test
    fun `the label prefers the merchant the provider identified`() {
        assertEquals("Netflix", txn("2026-03-04", -15.49, "NETFLIX.COM 8667169929", merchant = "Netflix").label())
        assertEquals("NETFLIX.COM", txn("2026-03-04", -15.49, "NETFLIX.COM").label())
    }
}
