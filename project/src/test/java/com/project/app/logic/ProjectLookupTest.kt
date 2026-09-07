package com.project.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The one lookup decision that is Project's own.
 *
 * Resolving a name to a row moved to `com.operations.connectkit.NameLookup` when Repository became
 * the third app to need it — see `NameLookupTest`. Where a card lands when nobody said is about a
 * board, and stays here.
 */
class ProjectLookupTest {

    private data class Column(val name: String, val done: Boolean)

    @Test
    fun `a card with no column named starts where work starts`() {
        val columns = listOf(Column("Backlog", false), Column("Doing", false), Column("Shipped", true))

        assertEquals(Column("Backlog", false), ProjectLookup.defaultColumn(columns) { it.done })
    }

    @Test
    fun `a board of nothing but finished columns gets no default`() {
        // Falling back to the finished column would file brand-new work as already done.
        assertNull(ProjectLookup.defaultColumn(listOf(Column("Done", true))) { it.done })
        assertNull(ProjectLookup.defaultColumn(emptyList<Column>()) { it.done })
    }
}
