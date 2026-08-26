package com.health.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A payer's FHIR directory, parsed. The payloads below are trimmed versions of what Plan-Net servers
 * actually return, including the ways they disappoint: a marketing page where the API should be, a
 * bundle with the network reference but no display name, a practitioner with no NPI at all.
 *
 * The rule the whole file is written to: a parse failure must read as "there is no directory here",
 * never as "your doctor isn't in it".
 */
class FhirDirectoryParserTest {

    private val capability = """
        {
          "resourceType": "CapabilityStatement",
          "status": "active",
          "fhirVersion": "4.0.1",
          "software": { "name": "Plan-Net Reference Server", "version": "2.1" },
          "rest": [{
            "mode": "server",
            "resource": [
              { "type": "Practitioner", "interaction": [{ "code": "search-type" }] },
              { "type": "PractitionerRole" },
              { "type": "Organization" },
              { "type": "InsurancePlan" }
            ]
          }]
        }
    """.trimIndent()

    @Test
    fun `a capability statement says what can be searched`() {
        val probe = FhirDirectoryParser.parseCapability(capability, "https://api.payer.example/fhir")
        assertEquals(DirectoryOutcome.REACHABLE, probe.outcome)
        assertEquals("4.0.1", probe.fhirVersion)
        assertEquals("Plan-Net Reference Server", probe.softwareName)
        assertTrue(probe.resources.contains("PractitionerRole"))
        assertTrue(probe.searchable)
    }

    @Test
    fun `a marketing page is no directory, not an empty one`() {
        val probe = FhirDirectoryParser.parseCapability("<html><body>Find a doctor</body></html>", "https://payer.example")
        assertEquals(DirectoryOutcome.NOT_A_DIRECTORY, probe.outcome)
        assertFalse(probe.searchable)
        // The distinction is the whole point of asking: "there is no directory here" and "the
        // directory says your doctor isn't in it" must never be reported as the same thing.
        assertTrue(probe.detail!!.contains("isn't FHIR"))
    }

    @Test
    fun `a FHIR server that answered with something else is named as such`() {
        val probe = FhirDirectoryParser.parseCapability(
            """{"resourceType":"OperationOutcome","issue":[]}""",
            "https://api.payer.example/fhir"
        )
        assertEquals(DirectoryOutcome.NOT_A_DIRECTORY, probe.outcome)
        assertTrue(probe.detail!!.contains("OperationOutcome"))
    }

    @Test
    fun `a practitioner bundle carries the name and the NPI`() {
        val bundle = """
            {
              "resourceType": "Bundle",
              "total": 1,
              "entry": [{
                "resource": {
                  "resourceType": "Practitioner",
                  "id": "pract-1",
                  "active": true,
                  "identifier": [
                    { "system": "http://hl7.org/fhir/sid/us-npi", "value": "1234567893" },
                    { "system": "http://payer.example/internal", "value": "X-991" }
                  ],
                  "name": [{ "family": "Okafor", "given": ["Jane", "A"], "prefix": ["Dr"] }]
                }
              }]
            }
        """.trimIndent()
        val found = FhirDirectoryParser.parsePractitioners(bundle)
        assertEquals(1, found.size)
        assertEquals("Jane A Okafor", found.first().name)
        assertEquals("1234567893", found.first().npi)
        assertEquals(true, found.first().active)
    }

    @Test
    fun `the server's own rendering of a name wins over assembling one`() {
        val bundle = """
            {"resourceType":"Bundle","entry":[{"resource":{
              "resourceType":"Practitioner","id":"p2",
              "name":[{"text":"Okafor, Jane A., MD","family":"Okafor","given":["Jane"]}]
            }}]}
        """.trimIndent()
        assertEquals("Okafor, Jane A., MD", FhirDirectoryParser.parsePractitioners(bundle).first().name)
    }

    @Test
    fun `entries of other types in the bundle are skipped rather than mangled`() {
        val bundle = """
            {"resourceType":"Bundle","entry":[
              {"resource":{"resourceType":"OperationOutcome","id":"warn"}},
              {"resource":{"resourceType":"Organization","id":"org-1","name":[]}},
              {"resource":{"resourceType":"Practitioner","id":"p3","name":[{"family":"Ito"}]}}
            ]}
        """.trimIndent()
        val found = FhirDirectoryParser.parsePractitioners(bundle)
        assertEquals(listOf("p3"), found.map { it.id })
    }

    @Test
    fun `a role bundle carries the networks, which is the fact that matters`() {
        val roles = """
            {"resourceType":"Bundle","entry":[{"resource":{
              "resourceType":"PractitionerRole","id":"role-1","active":true,
              "practitioner":{"reference":"Practitioner/pract-1","display":"Jane Okafor"},
              "organization":{"reference":"Organization/riverside","display":"Riverside Family Practice"},
              "specialty":[{"coding":[{"code":"208D00000X","display":"General Practice"}]}],
              "network":[
                {"reference":"Organization/choice-plus","display":"Choice Plus PPO"},
                {"reference":"Organization/select-hmo"}
              ]
            }}]}
        """.trimIndent()
        val parsed = FhirDirectoryParser.parseRoles(roles)
        assertEquals(1, parsed.size)
        // A display where there is one; the id at the end of the reference where there isn't —
        // "select-hmo" in front of somebody beats a blank where a network name should be.
        assertEquals(listOf("Choice Plus PPO", "select-hmo"), parsed.first().networks)
        assertEquals(listOf("General Practice"), parsed.first().specialties)
        assertEquals(listOf("Riverside Family Practice"), parsed.first().organizations)
    }

    @Test
    fun `roles fold into the practitioner they reference and no further`() {
        val practitioners = listOf(
            DirectoryPractitioner(id = "pract-1", name = "Jane Okafor", npi = null, active = true),
            DirectoryPractitioner(id = "pract-2", name = "Peter Ito", npi = null, active = true)
        )
        val roles = listOf(
            DirectoryRole(
                practitionerRef = "Practitioner/pract-1",
                active = true,
                specialties = listOf("Paediatrics"),
                networks = listOf("Choice Plus PPO")
            ),
            DirectoryRole(practitionerRef = "Practitioner/nobody", active = true, networks = listOf("Ghost Net"))
        )
        val merged = FhirDirectoryParser.merge(practitioners, roles)
        assertEquals(listOf("Choice Plus PPO"), merged.first { it.id == "pract-1" }.networks)
        // A role that references nobody in the bundle is dropped, never attached to whoever is first.
        assertTrue(merged.first { it.id == "pract-2" }.networks.isEmpty())
    }

    @Test
    fun `an operation outcome is read for what the user can act on`() {
        val outcome = """
            {"resourceType":"OperationOutcome","issue":[
              {"severity":"error","code":"not-supported","diagnostics":"Unknown search parameter 'name'"}
            ]}
        """.trimIndent()
        assertEquals("Unknown search parameter 'name'", FhirDirectoryParser.parseOperationOutcome(outcome))
        // Reporting that as "couldn't reach the directory" would send somebody looking for a network
        // problem they don't have.
        assertNull(FhirDirectoryParser.parseOperationOutcome("""{"resourceType":"Bundle"}"""))
    }

    @Test
    fun `malformed JSON parses to nothing rather than throwing`() {
        assertEquals(emptyList<DirectoryPractitioner>(), FhirDirectoryParser.parsePractitioners("{not json"))
        assertEquals(emptyList<DirectoryRole>(), FhirDirectoryParser.parseRoles("[]"))
        assertNull(FhirDirectoryParser.parseOperationOutcome("<html>"))
        assertEquals(
            DirectoryOutcome.NOT_A_DIRECTORY,
            FhirDirectoryParser.parseCapability("", "https://api.payer.example/fhir").outcome
        )
    }

    @Test
    fun `a practitioner with no NPI is still a practitioner`() {
        val bundle = """
            {"resourceType":"Bundle","entry":[{"resource":{
              "resourceType":"Practitioner","id":"p4","name":[{"family":"Ito","given":["Peter"]}]
            }}]}
        """.trimIndent()
        val found = FhirDirectoryParser.parsePractitioners(bundle).first()
        assertNull(found.npi)
        assertNull(found.active)
        assertEquals("Peter Ito", found.name)
    }
}
