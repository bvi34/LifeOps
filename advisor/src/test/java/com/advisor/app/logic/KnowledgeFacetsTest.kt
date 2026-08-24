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

    @Test
    fun classifies_the_lifeops_collection() {
        assertEquals(ObjectType.BOOK, KnowledgeFacets.objectTypeOf(doc(SourceApp.LIFEOPS, "book", "")))
        assertEquals(ObjectType.NOTE, KnowledgeFacets.objectTypeOf(doc(SourceApp.LIFEOPS, "note", "")))
        assertEquals(ObjectType.RECIPE, KnowledgeFacets.objectTypeOf(doc(SourceApp.LIFEOPS, "recipe", "")))
        assertEquals(ObjectType.IDEA, KnowledgeFacets.objectTypeOf(doc(SourceApp.LIFEOPS, "futureProject", "")))
    }

    @Test
    fun a_lifeops_book_reports_the_same_reading_state_as_a_citation_one() {
        assertEquals(
            KnowledgeFacets.READING,
            KnowledgeFacets.stateOf(doc(SourceApp.LIFEOPS, "book", "Book: X. Source: LifeOps. Reading state: READING"))
        )
    }

    @Test
    fun a_recipe_or_an_idea_has_no_state_to_disagree_about() {
        // "Serves 2" and "(active)" are not lifecycle states, and must never be read as one.
        assertNull(KnowledgeFacets.stateOf(doc(SourceApp.LIFEOPS, "recipe", "Recipe: Stew. Serves 2.")))
        assertNull(KnowledgeFacets.stateOf(doc(SourceApp.LIFEOPS, "futureProject", "Future project idea: X (active)")))
    }
}
