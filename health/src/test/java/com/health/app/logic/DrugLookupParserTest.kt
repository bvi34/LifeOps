package com.health.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two drug references' JSON, parsed against captured response shapes.
 *
 * The payloads below are trimmed copies of what RxNorm and openFDA actually return, kept short
 * enough to read. What is being proved is mostly what happens to the *awkward* ones: a response with
 * a branch missing, a suppressed concept, a label with three of its sixteen sections, and outright
 * malformed JSON — because a lookup is a convenience layered over a medicine the user could always
 * type in by hand, and it must never be able to take the screen down with it.
 */
class DrugLookupParserTest {

    // --- RxNorm ------------------------------------------------------------------------------------

    private val drugsJson = """
        {"drugGroup":{"name":"ibuprofen","conceptGroup":[
          {"tty":"BPCK"},
          {"tty":"IN","conceptProperties":[
            {"rxcui":"5640","name":"ibuprofen","synonym":"","tty":"IN","language":"ENG","suppress":"N"}
          ]},
          {"tty":"SBD","conceptProperties":[
            {"rxcui":"1234","name":"ibuprofen 100 MG/5 ML Oral Suspension [Children's Motrin]",
             "synonym":"Children's Motrin 100 MG/5 ML","tty":"SBD","language":"ENG","suppress":"N"}
          ]}
        ]}}
    """.trimIndent()

    @Test
    fun `products come back before bare ingredients`() {
        val results = RxNormParser.parseDrugs(drugsJson)
        assertEquals(2, results.size)
        // The branded product has a strength, and therefore a label openFDA can find.
        assertEquals("1234", results[0].rxcui)
        assertEquals(DrugConceptKind.BRANDED_PRODUCT, results[0].kind)
        assertEquals("5640", results[1].rxcui)
        assertEquals(DrugConceptKind.INGREDIENT, results[1].kind)
    }

    @Test
    fun `a group with no concepts at all is skipped, not crashed on`() {
        // RxNorm returns a bare {"tty":"BPCK"} for a term type it has nothing under.
        assertTrue(RxNormParser.parseDrugs(drugsJson).none { it.tty == "BPCK" })
    }

    @Test
    fun `suppressed and non-English concepts are dropped`() {
        val json = """
            {"drugGroup":{"conceptGroup":[{"tty":"SCD","conceptProperties":[
              {"rxcui":"1","name":"kept","tty":"SCD","language":"ENG","suppress":"N"},
              {"rxcui":"2","name":"suppressed","tty":"SCD","language":"ENG","suppress":"Y"},
              {"rxcui":"3","name":"otra cosa","tty":"SCD","language":"SPA","suppress":"N"}
            ]}]}}
        """.trimIndent()
        val results = RxNormParser.parseDrugs(json)
        assertEquals(listOf("1"), results.map { it.rxcui })
    }

    @Test
    fun `the synonym is the subtitle when there is one`() {
        val results = RxNormParser.parseDrugs(drugsJson)
        assertEquals("Children's Motrin 100 MG/5 ML", results[0].subtitle)
        // With no synonym, the term type explains what kind of hit it is.
        assertEquals(DrugConceptKind.INGREDIENT.label, results[1].subtitle)
    }

    @Test
    fun `the approximate search keeps the best score per concept`() {
        val json = """
            {"approximateGroup":{"inputTerm":"tylonol","candidate":[
              {"rxcui":"202433","rxaui":"1","score":"55","rank":"2"},
              {"rxcui":"202433","rxaui":"2","score":"75","rank":"1"},
              {"rxcui":"161","rxaui":"3","score":"60","rank":"1","name":"acetaminophen","tty":"IN"}
            ]}}
        """.trimIndent()
        val results = RxNormParser.parseApproximate(json)
        assertEquals(listOf("202433", "161"), results.map { it.rxcui })
        assertEquals(75, results[0].score)
        // The candidate rows need not carry a name; the client resolves those separately.
        assertEquals("", results[0].name)
        assertEquals("acetaminophen", results[1].name)
    }

    @Test
    fun `a concept's own properties fill in a nameless candidate`() {
        val json = """
            {"properties":{"rxcui":"202433","name":"Tylenol","synonym":"","tty":"BN",
             "language":"ENG","suppress":"N"}}
        """.trimIndent()
        val resolved = RxNormParser.parseProperties(json)
        assertEquals("Tylenol", resolved?.name)
        assertEquals(DrugConceptKind.BRAND, resolved?.kind)
    }

    @Test
    fun `attributes yield the strengths, and a numeric schedule is written out`() {
        val json = """
            {"propConceptGroup":{"propConcept":[
              {"propCategory":"ATTRIBUTES","propName":"AVAILABLE_STRENGTH","propValue":"160 mg/5 mL"},
              {"propCategory":"ATTRIBUTES","propName":"SCHEDULE","propValue":"2"},
              {"propCategory":"ATTRIBUTES","propName":"HUMAN_DRUG","propValue":"US"}
            ]}}
        """.trimIndent()
        val attributes = RxNormParser.parseAttributes(json)
        assertEquals(listOf("160 mg/5 mL"), attributes.availableStrengths)
        assertEquals("Schedule II (controlled substance)", attributes.schedule)
    }

    @Test
    fun `an unscheduled product says nothing rather than reassuring anybody`() {
        val json = """
            {"propConceptGroup":{"propConcept":[
              {"propCategory":"ATTRIBUTES","propName":"SCHEDULE","propValue":"0"}
            ]}}
        """.trimIndent()
        assertNull(RxNormParser.parseAttributes(json).schedule)
    }

    @Test
    fun `related concepts give the ingredient, the brand and the form`() {
        val json = """
            {"relatedGroup":{"rxcui":"1234","conceptGroup":[
              {"tty":"IN","conceptProperties":[{"rxcui":"5640","name":"ibuprofen","tty":"IN"}]},
              {"tty":"BN","conceptProperties":[{"rxcui":"9","name":"Motrin","tty":"BN"}]},
              {"tty":"DF","conceptProperties":[{"rxcui":"7","name":"Oral Suspension","tty":"DF"}]}
            ]}}
        """.trimIndent()
        val related = RxNormParser.parseRelated(json)
        assertEquals(listOf("ibuprofen"), related.ingredients)
        assertEquals(listOf("Motrin"), related.brandNames)
        assertEquals(listOf("Oral Suspension"), related.doseForms)
    }

    @Test
    fun `a precise ingredient wins over the general one`() {
        val json = """
            {"relatedGroup":{"conceptGroup":[
              {"tty":"IN","conceptProperties":[{"rxcui":"1","name":"ibuprofen","tty":"IN"}]},
              {"tty":"PIN","conceptProperties":[{"rxcui":"2","name":"ibuprofen lysine","tty":"PIN"}]}
            ]}}
        """.trimIndent()
        assertEquals(listOf("ibuprofen lysine"), RxNormParser.parseRelated(json).ingredients)
    }

    @Test
    fun `malformed JSON parses to nothing rather than throwing`() {
        assertEquals(emptyList<DrugCandidate>(), RxNormParser.parseDrugs("not json at all"))
        assertEquals(emptyList<DrugCandidate>(), RxNormParser.parseApproximate("{"))
        assertNull(RxNormParser.parseProperties("{}"))
        assertEquals(RxNormParser.Attributes(), RxNormParser.parseAttributes("[]"))
        assertEquals(RxNormParser.Related(), RxNormParser.parseRelated("{\"relatedGroup\":null}"))
    }

    // --- openFDA -----------------------------------------------------------------------------------

    private val base = DrugMonograph(
        rxcui = "1234",
        name = "ibuprofen 100 MG/5 ML Oral Suspension",
        ingredients = listOf("ibuprofen"),
        sources = listOf(DrugSources.RXNORM)
    )

    private val labelJson = """
        {"results":[{
          "set_id":"abc-123",
          "effective_time":"20190417",
          "purpose":["Purpose  Pain reliever/fever\nreducer"],
          "dosage_and_administration":["Directions: shake well before use"],
          "do_not_use":["Do not use if you have ever had an allergic reaction"],
          "clinical_pharmacology":["not something anybody reads at a cupboard"],
          "openfda":{
            "brand_name":["Children's Motrin"],
            "generic_name":["IBUPROFEN"],
            "manufacturer_name":["Johnson & Johnson"],
            "product_type":["HUMAN OTC DRUG LABEL"],
            "route":["ORAL"],
            "substance_name":["IBUPROFEN"]
          }
        }]}
    """.trimIndent()

    @Test
    fun `label sections arrive in reading order, not in the label's order`() {
        val monograph = OpenFdaParser.parseLabel(labelJson, base)
        assertEquals(
            listOf(OpenFdaParser.PURPOSE, OpenFdaParser.DOSAGE, OpenFdaParser.DO_NOT_USE),
            monograph.sections.map { it.key }
        )
        assertEquals("Do not use", monograph.section(OpenFdaParser.DO_NOT_USE)?.title)
    }

    @Test
    fun `a section nobody reads at a cupboard is not kept`() {
        val monograph = OpenFdaParser.parseLabel(labelJson, base)
        assertTrue(monograph.sections.none { it.key == "clinical_pharmacology" })
    }

    @Test
    fun `the label's own line breaks are collapsed but its words are not touched`() {
        val monograph = OpenFdaParser.parseLabel(labelJson, base)
        assertEquals("Purpose Pain reliever/fever reducer", monograph.section(OpenFdaParser.PURPOSE)?.text)
        assertEquals("Directions: shake well before use", monograph.dosageText)
    }

    @Test
    fun `RxNorm keeps identity and openFDA fills in what it has no opinion about`() {
        val monograph = OpenFdaParser.parseLabel(labelJson, base)
        // The name the user picked came from RxNorm and stays.
        assertEquals("ibuprofen 100 MG/5 ML Oral Suspension", monograph.name)
        assertEquals(listOf("ibuprofen"), monograph.ingredients)
        // These, RxNorm has nothing to say about.
        assertEquals("Johnson & Johnson", monograph.manufacturer)
        assertEquals("HUMAN OTC DRUG LABEL", monograph.productType)
        assertEquals("Children's Motrin", monograph.brandName)
        assertEquals("abc-123", monograph.labelSetId)
        assertTrue(monograph.sources.contains(DrugSources.OPENFDA))
        assertTrue(monograph.sources.contains(DrugSources.RXNORM))
    }

    @Test
    fun `no label leaves the RxNorm half intact`() {
        val monograph = OpenFdaParser.parseLabel("""{"results":[]}""", base)
        assertEquals(base, monograph)
        assertTrue(!monograph.hasLabel)
    }

    @Test
    fun `an effective time reads as a date`() {
        assertEquals("17 Apr 2019", OpenFdaParser.formatEffectiveTime("20190417"))
        // Anything that isn't one is passed through — a date Health can't parse is still a date.
        assertEquals("spring 2019", OpenFdaParser.formatEffectiveTime("spring 2019"))
        assertNull(OpenFdaParser.formatEffectiveTime(" "))
    }

    @Test
    fun `a display name pairs the generic with the brand only when they differ`() {
        assertEquals(
            "acetaminophen (Tylenol)",
            DrugMonograph(rxcui = "1", name = "acetaminophen", genericName = "acetaminophen", brandName = "Tylenol")
                .displayName
        )
        // The brand is already in the name — saying it twice reads as a bug.
        assertEquals(
            "Children's Tylenol Oral Suspension",
            DrugMonograph(rxcui = "1", name = "Children's Tylenol Oral Suspension", brandName = "Tylenol")
                .displayName
        )
    }
}
