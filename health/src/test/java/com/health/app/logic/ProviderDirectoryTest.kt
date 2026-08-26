package com.health.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deciding what to ask a payer's directory, and what the answer means.
 *
 * The cases that matter are the ones where being wrong is expensive: a URL guessed into the wrong
 * shape, a name that a directory spells differently from the household, and — the one that would do
 * real damage — a directory entry with the right name and somebody else's NPI.
 */
class ProviderDirectoryTest {

    @Test
    fun `a pasted address is taken at its word and tried where FHIR servers live`() {
        val candidates = ProviderDirectory.candidateBases("payer.example.com/developers")
        assertEquals("https://payer.example.com/developers", candidates.first())
        assertTrue(candidates.contains("https://payer.example.com/developers/fhir"))
        // Five requests find an endpoint the user pasted one level above; fifteen look like a scan.
        assertTrue(candidates.size <= 5)
    }

    @Test
    fun `an address that already names FHIR is tried alone`() {
        assertEquals(
            listOf("https://api.payer.example/plan-net/fhir"),
            ProviderDirectory.candidateBases("https://api.payer.example/plan-net/fhir/")
        )
        assertEquals(
            listOf("https://api.payer.example/fhir/R4"),
            ProviderDirectory.candidateBases("https://api.payer.example/fhir/R4")
        )
    }

    @Test
    fun `a capability URL is not asked for twice`() {
        // People copy the /metadata URL as often as the base; asking for /metadata/metadata fails in
        // a way that reads as "this directory is broken".
        assertEquals("https://api.payer.example/fhir", ProviderDirectory.normalizeBase("https://api.payer.example/fhir/metadata"))
        assertEquals(
            "https://api.payer.example/fhir/metadata",
            ProviderDirectory.metadataUrl("https://api.payer.example/fhir/")
        )
    }

    @Test
    fun `query strings, fragments and a missing scheme are all tidied away`() {
        assertEquals("https://api.payer.example/fhir", ProviderDirectory.normalizeBase("  api.payer.example/fhir?x=1#top "))
        assertEquals("http://localhost:8080/fhir", ProviderDirectory.normalizeBase("http://localhost:8080/fhir/"))
        assertNull(ProviderDirectory.normalizeBase("   "))
        assertNull(ProviderDirectory.normalizeBase("https://"))
        assertEquals(emptyList<String>(), ProviderDirectory.candidateBases(null))
    }

    @Test
    fun `only the surname is sent, because that is what FHIR name search matches`() {
        assertEquals("okafor", ProviderDirectory.searchTerm("Dr. Jane A. Okafor, MD"))
        assertEquals("okafor", ProviderDirectory.searchTerm("Okafor"))
    }

    @Test
    fun `titles and credentials never take part in a comparison`() {
        assertEquals(listOf("jane", "a", "okafor"), ProviderDirectory.nameTokens("Dr. Jane A. Okafor, MD"))
        assertEquals(listOf("okafor", "jane"), ProviderDirectory.nameTokens("Okafor, Jane (PA-C)"))
    }

    @Test
    fun `a credential's stray letter goes with it, an honorific's initial does not`() {
        // "PA-C" survives the punctuation strip as pa + c; keeping the c would leave a stray initial
        // that then matches somebody's middle name.
        assertEquals(listOf("okafor", "jane"), ProviderDirectory.nameTokens("Okafor, Jane PA-C"))
        // "Dr. J Okafor" is a name with an initial in it, and that initial may be the only first
        // name the directory has.
        assertEquals(listOf("j", "okafor"), ProviderDirectory.nameTokens("Dr. J Okafor"))
    }

    @Test
    fun `a directory that files a name backwards still matches`() {
        assertEquals(
            MatchStrength.NAME,
            ProviderDirectory.compareNames("Dr. Jane Okafor", "Okafor, Jane")
        )
        assertEquals(
            MatchStrength.NAME,
            ProviderDirectory.compareNames("Jane Okafor", "Jane A Okafor")
        )
    }

    @Test
    fun `a surname and an initial is worth showing and never worth concluding`() {
        assertEquals(MatchStrength.WEAK, ProviderDirectory.compareNames("Jane Okafor", "J Okafor"))
        assertEquals(MatchStrength.NONE, ProviderDirectory.compareNames("Jane Okafor", "Peter Okafor"))
        assertEquals(MatchStrength.NONE, ProviderDirectory.compareNames("Jane Okafor", ""))
    }

    @Test
    fun `matching NPIs settle it and conflicting ones settle it the other way`() {
        val entry = DirectoryPractitioner(id = "1", name = "Jane Okafor", npi = "1234567893", active = true)
        assertEquals(MatchStrength.NPI, ProviderDirectory.assess("Jane Okafor", "1234567893", entry))
        // The dangerous case: the right name, somebody else's number. Two clinicians share a name far
        // more often than they share an identifier, and calling this a match is how a household gets
        // told their doctor is in network when he is not.
        assertEquals(MatchStrength.NONE, ProviderDirectory.assess("Jane Okafor", "1932128587", entry))
    }

    @Test
    fun `without an NPI on either side the names have to do`() {
        val entry = DirectoryPractitioner(id = "1", name = "Okafor, Jane", npi = null, active = true)
        assertEquals(MatchStrength.NAME, ProviderDirectory.assess("Dr Jane Okafor", null, entry))
        assertEquals(MatchStrength.NAME, ProviderDirectory.assess("Dr Jane Okafor", "1234567893", entry))
    }

    @Test
    fun `a well-formed NPI passes its own check digit and a mistyped one does not`() {
        assertTrue(ProviderDirectory.isValidNpi("1234567893"))
        assertTrue(ProviderDirectory.isValidNpi("1234-567-893"))
        // One digit out — indistinguishable from a doctor who left the network unless it is caught
        // before the request is made, because both come back as no results.
        assertFalse(ProviderDirectory.isValidNpi("1234567892"))
        assertFalse(ProviderDirectory.isValidNpi("123456789"))
        assertFalse(ProviderDirectory.isValidNpi(null))
    }

    @Test
    fun `a search by NPI carries the identifier system so the match is exact`() {
        val url = ProviderDirectory.npiSearchUrl("https://api.payer.example/fhir", "1234567893")
        assertTrue(url.startsWith("https://api.payer.example/fhir/Practitioner?identifier="))
        assertTrue(url.contains(ProviderDirectory.encodeQuery("${ProviderDirectory.NPI_SYSTEM}|1234567893")))
        // What must never appear: anything about the person asking.
        assertFalse(url.contains("member"))
    }

    @Test
    fun `an HTTP status is read as what it says about the endpoint, not as a failure`() {
        assertEquals(DirectoryOutcome.REACHABLE, ProviderDirectory.classify(200))
        // Plan-Net is meant to be public, so a challenge means the URL is the member portal.
        assertEquals(DirectoryOutcome.NEEDS_SIGN_IN, ProviderDirectory.classify(401))
        assertEquals(DirectoryOutcome.NOT_PUBLISHED, ProviderDirectory.classify(404))
        assertEquals(DirectoryOutcome.RATE_LIMITED, ProviderDirectory.classify(429))
        assertEquals(DirectoryOutcome.NOT_A_DIRECTORY, ProviderDirectory.classify(400))
        assertEquals(DirectoryOutcome.UNREACHABLE, ProviderDirectory.classify(503))
    }

    @Test
    fun `a reachable endpoint that cannot be searched says so rather than answering`() {
        val formulary = DirectoryProbe(
            outcome = DirectoryOutcome.REACHABLE,
            baseUrl = "https://api.payer.example/fhir",
            resources = listOf("InsurancePlan", "MedicationKnowledge")
        )
        assertFalse(formulary.searchable)
        assertTrue(formulary.copy(resources = listOf("Practitioner")).searchable)
    }
}

/**
 * Turning a list of strangers with similar names into a verdict. The generous direction is the
 * dangerous one — a wrong yes sends somebody to an appointment they will be billed out-of-network
 * for — so every case here checks that Health declines to guess where a person would have to.
 */
class DirectoryVerdictTest {

    private fun entry(
        id: String,
        name: String,
        npi: String? = null,
        active: Boolean? = true,
        specialties: List<String> = emptyList()
    ) = DirectoryPractitioner(id, name, npi, active, specialties = specialties)

    @Test
    fun `an empty answer is a real no`() {
        val verdict = ProviderDirectory.judge("Jane Okafor", "1234567893", emptyList(), byIdentifier = true)
        assertEquals(CheckOutcome.NOT_LISTED, verdict.outcome)
        assertEquals(0, verdict.matchCount)
        assertTrue(verdict.detail!!.contains("NPI"))
    }

    @Test
    fun `matching NPIs are a yes even among a crowd`() {
        val verdict = ProviderDirectory.judge(
            "Jane Okafor",
            "1234567893",
            listOf(
                entry("a", "Peter Okafor", npi = "1932128587"),
                entry("b", "J A Okafor", npi = "1234567893", specialties = listOf("Paediatrics"))
            ),
            byIdentifier = false
        )
        assertEquals(CheckOutcome.LISTED, verdict.outcome)
        assertEquals("b", verdict.matched!!.id)
    }

    @Test
    fun `exactly one matching name is a yes`() {
        val verdict = ProviderDirectory.judge(
            "Jane Okafor",
            null,
            listOf(entry("a", "Okafor, Jane"), entry("b", "Peter Ito")),
            byIdentifier = false
        )
        assertEquals(CheckOutcome.LISTED, verdict.outcome)
        assertEquals("a", verdict.matched!!.id)
    }

    @Test
    fun `two people with the same name is never the first one`() {
        val verdict = ProviderDirectory.judge(
            "Jane Okafor",
            null,
            listOf(entry("a", "Jane Okafor"), entry("b", "Jane Okafor")),
            byIdentifier = false
        )
        assertEquals(CheckOutcome.AMBIGUOUS, verdict.outcome)
        assertEquals(2, verdict.matchCount)
        assertNull(verdict.matched)
        // The whole reason a provider has an NPI field is to settle this.
        assertTrue(verdict.detail!!.contains("NPI"))
    }

    @Test
    fun `a surname and an initial is not enough to conclude`() {
        val verdict = ProviderDirectory.judge(
            "Jane Okafor",
            null,
            listOf(entry("a", "J Okafor")),
            byIdentifier = false
        )
        assertEquals(CheckOutcome.AMBIGUOUS, verdict.outcome)
        assertTrue(verdict.detail!!.contains("surname and an initial"))
    }

    @Test
    fun `a directory full of other people has answered the question`() {
        val verdict = ProviderDirectory.judge(
            "Jane Okafor",
            null,
            listOf(entry("a", "Peter Ito"), entry("b", "Ada Nwosu")),
            byIdentifier = false
        )
        assertEquals(CheckOutcome.NOT_LISTED, verdict.outcome)
        assertTrue(verdict.detail!!.contains("none of them is them"))
    }

    @Test
    fun `a listing marked inactive is not a yes`() {
        val verdict = ProviderDirectory.judge(
            "Jane Okafor",
            "1234567893",
            listOf(entry("a", "Jane Okafor", npi = "1234567893", active = false)),
            byIdentifier = true
        )
        // Reading `active = false` as a yes is what sends somebody to a closed office.
        assertEquals(CheckOutcome.NOT_LISTED, verdict.outcome)
        assertEquals("a", verdict.matched!!.id)
        assertTrue(verdict.detail!!.contains("no longer active"))
    }
}
