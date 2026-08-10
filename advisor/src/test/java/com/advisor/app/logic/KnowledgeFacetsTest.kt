package com.advisor.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KnowledgeFacetsTest {

    private fun doc(source: SourceApp, kind: String, body: String) =
        KnowledgeDocument("$source:$kind:x", source, kind, "T", body)

    @Test
    fun classifies_object_types_from_source_and_kind() {
        assertEquals(ObjectType.BOOK, KnowledgeFacets.objectTypeOf(doc(SourceApp.CITATION, "book", "")))
        assertEquals(ObjectType.NOTE, KnowledgeFacets.objectTypeOf(doc(SourceApp.CITATION, "note", "")))
        assertEquals(ObjectType.TASK, KnowledgeFacets.objectTypeOf(doc(SourceApp.LIFEOPS, "task", "")))
        assertEquals(ObjectType.PANTRY_ITEM, KnowledgeFacets.objectTypeOf(doc(SourceApp.LOGISTICS, "pantry", "")))
        assertEquals(ObjectType.GROCERY_ITEM, KnowledgeFacets.objectTypeOf(doc(SourceApp.LOGISTICS, "grocery", "")))
    }

    @Test
    fun reads_book_reading_state() {
        assertEquals(KnowledgeFacets.READING,
            KnowledgeFacets.stateOf(doc(SourceApp.CITATION, "book", "Book: X. Reading state: READING")))
        assertEquals(KnowledgeFacets.TO_READ,
            KnowledgeFacets.stateOf(doc(SourceApp.CITATION, "book", "Book: X. Reading state: TO_READ")))
        assertEquals(KnowledgeFacets.DONE,
            KnowledgeFacets.stateOf(doc(SourceApp.CITATION, "book", "Book: X. Reading state: DONE")))
    }

    @Test
    fun normalizes_task_status_to_the_shared_vocabulary() {
        assertEquals(KnowledgeFacets.DONE,
            KnowledgeFacets.stateOf(doc(SourceApp.LIFEOPS, "task", "Task: X. Status: completed")))
        assertEquals(KnowledgeFacets.TODO,
            KnowledgeFacets.stateOf(doc(SourceApp.LIFEOPS, "task", "Task: X. Status: pending")))
    }

    @Test
    fun pantry_state_reflects_low_stock() {
        assertEquals(KnowledgeFacets.LOW,
            KnowledgeFacets.stateOf(doc(SourceApp.LOGISTICS, "pantry", "Pantry item: X. In stock: 0. Running low.")))
        assertEquals(KnowledgeFacets.STOCKED,
            KnowledgeFacets.stateOf(doc(SourceApp.LOGISTICS, "pantry", "Pantry item: X. In stock: 5 kg")))
    }

    @Test
    fun a_typeless_record_has_no_state() {
        assertNull(KnowledgeFacets.stateOf(doc(SourceApp.LIFEOPS, "aspect", "Aspect (life area): Body")))
    }
}
