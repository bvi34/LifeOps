package com.project.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * How a record's file drawer reads on the household's shelf.
 *
 * Small, and worth pinning: the shelf lists everything the household has ever been handed, across
 * every app, so a drawer called "The docks" sitting beside a mortgage statement and a boiler manual
 * is a question rather than an answer. Repository holds this as a *label* rather than a foreign key
 * — which is what lets it show a project's paperwork without knowing what a project is — so this
 * string is the whole of what somebody has to recognise it by.
 */
class AttachmentsTest {

    @Test
    fun `a record's drawer leads with its project`() {
        assertEquals(
            "The Kestrel — The docks",
            Attachments.shelfLabel("The Kestrel", "The docks")
        )
    }

    @Test
    fun `the project's own drawer is just the project`() {
        assertEquals("The Kestrel", Attachments.shelfLabel("The Kestrel", null))
        assertEquals("The Kestrel", Attachments.shelfLabel("The Kestrel", "   "))
    }

    @Test
    fun `names are trimmed, so a stray space does not reach the shelf`() {
        assertEquals(
            "The Kestrel — The docks",
            Attachments.shelfLabel("  The Kestrel  ", "  The docks  ")
        )
    }

    @Test
    fun `a project with no name still says something`() {
        // An untitled project is possible, and a drawer labelled "" or " — The docks" is a drawer
        // nobody can pick out of a list.
        assertEquals("Project — The docks", Attachments.shelfLabel("", "The docks"))
        assertEquals("Project", Attachments.shelfLabel("   ", null))
    }

    @Test
    fun `a kind survives the round trip through a link, and nonsense does not`() {
        AttachKind.entries.forEach { kind ->
            assertEquals(kind, AttachKind.fromKey(kind.key))
        }
        // Null rather than a default, so the route can decide what an unrecognised kind means
        // instead of silently landing somebody on the wrong drawer.
        assertNull(AttachKind.fromKey("scene"))
        assertNull(AttachKind.fromKey(null))
        assertNull(AttachKind.fromKey(""))
    }
}
