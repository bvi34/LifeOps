package com.health.app.connect

import com.health.app.logic.ConnectKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The code's list of what Health reads and the manifest's list of what it may ask for have to
 * agree. Health Connect only offers a permission the manifest declared, so a kind that is in the
 * code and not the manifest is a kind that can never be imported — and nothing would say so.
 */
class ConnectTypesTest {

    @Test
    fun `every kind is read from exactly one place`() {
        ConnectKind.entries.forEach { kind ->
            val sources = listOf(kind in ConnectTypes.RECORDS, kind in ConnectTypes.MEDICAL).count { it }
            assertEquals("$kind", 1, sources)
            assertEquals("$kind", kind.isMedical, kind in ConnectTypes.MEDICAL)
        }
    }

    @Test
    fun `no two kinds are the same record type`() {
        assertEquals(ConnectTypes.RECORDS.size, ConnectTypes.RECORDS.values.toSet().size)
        assertEquals(ConnectTypes.MEDICAL.size, ConnectTypes.MEDICAL.values.map { it.resourceType }.toSet().size)
    }

    @Test
    fun `every permission asked for is declared in the manifest`() {
        // Unit tests run from the module directory.
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val declared = Regex("""android:name="(android\.permission\.health\.[A-Z0-9_]+)"""")
            .findAll(manifest).map { it.groupValues[1] }.toSet()
        val missing = ConnectTypes.ALL_PERMISSIONS - declared
        assertTrue("not declared: $missing", missing.isEmpty())
        val unused = declared - ConnectTypes.ALL_PERMISSIONS
        assertTrue("declared but never asked for: $unused", unused.isEmpty())
    }
}
