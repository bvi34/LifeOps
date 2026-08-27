package com.health.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The allergy check. Every case here is one a household actually hits at a cupboard — a label that
 * spells the ingredient out in full, a brand that advertises what it *doesn't* contain, and the two
 * drug names that look alike and aren't.
 *
 * The most important tests in this file are the ones that assert Health says **nothing**.
 */
class AllergiesTest {

    private fun drug(
        substance: String,
        severity: AllergySeverity = AllergySeverity.MODERATE,
        rxcui: String? = null
    ) = AllergyFacts("a-$substance", substance, AllergyKind.DRUG, severity, rxcui)

    @Test
    fun `an ingredient spelled out in full still matches the name that was written down`() {
        val warnings = Allergies.check(
            listOf(drug("penicillin")),
            MedicineFacts(name = "Pen-V", ingredients = listOf("penicillin G potassium"))
        )
        assertEquals(1, warnings.size)
        assertEquals(AllergyMatch.INGREDIENT, warnings[0].match)
        // The evidence is quoted from the label, not paraphrased back as the word we searched for.
        assertEquals("penicillin G potassium", warnings[0].matchedOn)
    }

    @Test
    fun `a related drug is never inferred — penicillin does not warn about amoxicillin`() {
        // The whole line this file refuses to cross. They are relatives, cross-reactivity is real,
        // and Health is not qualified to say so. A pharmacist is.
        val warnings = Allergies.check(
            listOf(drug("penicillin")),
            MedicineFacts(name = "Amoxil", ingredients = listOf("amoxicillin trihydrate"))
        )
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `a substring is not a match — codeine does not fire on hydrocodone`() {
        val warnings = Allergies.check(
            listOf(drug("codeine")),
            MedicineFacts(name = "Hycodan", ingredients = listOf("hydrocodone bitartrate"))
        )
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `a product that advertises being free of it does not warn about it`() {
        // The most embarrassing failure available to this feature: firing on the words printed to
        // reassure the exact person being warned.
        val warnings = Allergies.check(
            listOf(drug("aspirin")),
            MedicineFacts(name = "Aspirin-Free Pain Relief", ingredients = listOf("acetaminophen"))
        )
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `an un-negated mention still warns even when the box also says free somewhere`() {
        val warnings = Allergies.check(
            listOf(drug("aspirin")),
            MedicineFacts(name = "Sugar Free Aspirin", ingredients = listOf("aspirin"))
        )
        assertEquals(1, warnings.size)
        assertEquals(AllergyMatch.INGREDIENT, warnings[0].match)
    }

    @Test
    fun `the same concept id is certainty, whatever the names say`() {
        val warnings = Allergies.check(
            listOf(drug("Amoxicillin 500mg capsule", rxcui = "723")),
            MedicineFacts(rxcui = "723", name = "Something else entirely")
        )
        assertEquals(1, warnings.size)
        assertEquals(AllergyMatch.CONCEPT, warnings[0].match)
    }

    @Test
    fun `an ingredient is preferred over a name when both would match`() {
        val warnings = Allergies.check(
            listOf(drug("ibuprofen")),
            MedicineFacts(name = "Ibuprofen Suspension", ingredients = listOf("ibuprofen"))
        )
        assertEquals(AllergyMatch.INGREDIENT, warnings[0].match)
    }

    @Test
    fun `a name-only match says it is only a name`() {
        val warnings = Allergies.check(
            listOf(drug("Calpol")),
            MedicineFacts(name = "Calpol Infant", ingredients = listOf("paracetamol"))
        )
        assertEquals(AllergyMatch.NAME, warnings[0].match)
    }

    @Test
    fun `a food allergy is not checked against a medicine`() {
        // Health holds a label's active ingredients, not its excipients — so it cannot tell whether
        // a grape-flavoured suspension is a problem, and does not pretend to have looked.
        val warnings = Allergies.check(
            listOf(AllergyFacts("f1", "grape", AllergyKind.FOOD, AllergySeverity.SEVERE)),
            MedicineFacts(name = "Grape Flavour Children's Suspension")
        )
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `the worst reaction is read first`() {
        val warnings = Allergies.check(
            listOf(
                drug("ibuprofen", AllergySeverity.MILD),
                drug("penicillin", AllergySeverity.ANAPHYLAXIS)
            ),
            MedicineFacts(name = "Compound", ingredients = listOf("ibuprofen", "penicillin"))
        )
        assertEquals(2, warnings.size)
        assertEquals("penicillin", warnings[0].allergy.substance)
    }

    @Test
    fun `a blank substance matches nothing rather than everything`() {
        val warnings = Allergies.check(
            listOf(drug("   ")),
            MedicineFacts(name = "Anything at all", ingredients = listOf("acetaminophen"))
        )
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `a severity nobody recorded is not promoted to a severe one`() {
        assertEquals(
            AllergySeverity.UNKNOWN,
            Allergies.worstSeverity(listOf(drug("x", AllergySeverity.UNKNOWN)))
        )
        assertEquals(
            AllergySeverity.ANAPHYLAXIS,
            Allergies.worstSeverity(
                listOf(drug("x", AllergySeverity.UNKNOWN), drug("y", AllergySeverity.ANAPHYLAXIS))
            )
        )
        assertNull(Allergies.worstSeverity(emptyList()))
    }

    @Test
    fun `the headline names the reaction and the detail names the evidence`() {
        val warning = Allergies.check(
            listOf(drug("penicillin", AllergySeverity.ANAPHYLAXIS)),
            MedicineFacts(name = "Pen-V", ingredients = listOf("penicillin G potassium"))
        ).single()
        assertEquals("penicillin — anaphylaxis", warning.headline)
        assertTrue(warning.detail.contains("penicillin G potassium"))
    }
}
