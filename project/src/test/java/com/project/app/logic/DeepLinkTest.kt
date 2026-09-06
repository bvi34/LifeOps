package com.project.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The addresses that say where in Project to open.
 *
 * Two properties matter, and neither is obvious enough to leave to inspection.
 *
 * **Nothing throws, and anything unrecognised is `null`.** A link is a hint from somewhere else —
 * from Advisor, from the app's own memory of where you were — and a hint written by an older or a
 * newer build must not be able to stop Project opening. Every malformed case below is a case that
 * would otherwise reach a `split` and an index.
 *
 * **An address means one thing.** These carry row ids, so an address that parsed back as a
 * *different* destination would open somebody's writing at the wrong place, which is the one
 * failure a linking scheme must not have.
 */
class DeepLinkTest {

    private fun roundTrip(destination: ProjectDestination): ProjectDestination? =
        ProjectLinks.parse(ProjectLinks.format(destination))

    // ------------------------------------------------------------------ there and back

    @Test
    fun `every kind of destination survives being written down and read back`() {
        val destinations = listOf(
            ProjectDestination.Shelf,
            ProjectDestination.Workspace("p1"),
            ProjectDestination.Workspace("p1", SearchSection.LORE),
            ProjectDestination.Document("p1", "d1")
        )

        destinations.forEach { assertEquals(it, roundTrip(it)) }
    }

    @Test
    fun `every section can be linked to, so adding one cannot quietly become unreachable`() {
        SearchSection.entries.forEach { section ->
            assertEquals(
                ProjectDestination.Workspace("p1", section),
                roundTrip(ProjectDestination.Workspace("p1", section))
            )
        }
    }

    @Test
    fun `the addresses read as the app's own routes`() {
        assertEquals("shelf", ProjectLinks.format(ProjectDestination.Shelf))
        assertEquals("project/p1", ProjectLinks.format(ProjectDestination.Workspace("p1")))
        assertEquals(
            "project/p1/lore",
            ProjectLinks.format(ProjectDestination.Workspace("p1", SearchSection.LORE))
        )
        assertEquals(
            "project/p1/doc/d1",
            ProjectLinks.format(ProjectDestination.Document("p1", "d1"))
        )
    }

    // ------------------------------------------------------------------ what will not be written

    @Test
    fun `an id that would split the address is refused rather than mangled`() {
        // The app's ids are UUIDs so this should not arise — but "project/a/b" built from the id
        // "a/b" would parse back as a *section* named b, opening a real project on the wrong screen,
        // or worse. Refusing is the only answer that cannot silently mean something else.
        assertNull(ProjectLinks.format(ProjectDestination.Workspace("a/b")))
        assertNull(ProjectLinks.format(ProjectDestination.Document("a/b", "d1")))
        assertNull(ProjectLinks.format(ProjectDestination.Document("p1", "d/1")))
        assertNull(ProjectLinks.format(ProjectDestination.Workspace("")))
        assertNull(ProjectLinks.format(ProjectDestination.Workspace("   ")))
    }

    // ------------------------------------------------------------------ what will not be read

    @Test
    fun `nothing, and nonsense, mean open normally`() {
        listOf(
            null, "", "   ", "/", "///",
            "shelf/extra", "outline", "p1",
            "project", "project/", "project/p1/doc",
            "project/p1/doc/d1/more", "projects/p1", "PROJECT/p1",
            "doc/d1"
        ).forEach { assertNull("`$it` parsed as something", ProjectLinks.parse(it)) }
    }

    @Test
    fun `an unknown section is refused rather than read as the outline`() {
        // Landing somewhere plausible is how a caller's typo survives to ship: the link appears to
        // work and silently shows the wrong section every time.
        assertNull(ProjectLinks.parse("project/p1/notasection"))
        assertNull(ProjectLinks.parse("project/p1/OUTLINE"))
    }

    @Test
    fun `surrounding and repeated slashes are tolerated`() {
        // Addresses get concatenated by callers, and a stray slash is a typo rather than an attempt
        // to name something else — there is only one thing "/project/p1/" can mean.
        assertEquals(ProjectDestination.Workspace("p1"), ProjectLinks.parse("/project/p1"))
        assertEquals(ProjectDestination.Workspace("p1"), ProjectLinks.parse("project/p1/"))
        assertEquals(ProjectDestination.Workspace("p1"), ProjectLinks.parse("  project//p1  "))
        assertEquals(ProjectDestination.Shelf, ProjectLinks.parse(" shelf "))
    }
}
