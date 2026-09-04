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
        assertEquals(ObjectType.IDEA, KnowledgeFacets.objectTypeOf(doc(SourceApp.LIFEOPS, "idea", "")))
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
        assertNull(KnowledgeFacets.stateOf(doc(SourceApp.LIFEOPS, "idea", "Future operation idea: X (active)")))
    }

    @Test
    fun classifies_health_records() {
        assertEquals(ObjectType.PROFILE, KnowledgeFacets.objectTypeOf(doc(SourceApp.HEALTH, "person", "")))
        assertEquals(ObjectType.HEALTH_RECORD, KnowledgeFacets.objectTypeOf(doc(SourceApp.HEALTH, "temperature", "")))
        assertEquals(ObjectType.HEALTH_RECORD, KnowledgeFacets.objectTypeOf(doc(SourceApp.HEALTH, "symptom", "")))
        assertEquals(ObjectType.HEALTH_RECORD, KnowledgeFacets.objectTypeOf(doc(SourceApp.HEALTH, "illness", "")))
        assertEquals(ObjectType.MEDICATION, KnowledgeFacets.objectTypeOf(doc(SourceApp.HEALTH, "medication", "")))
        assertEquals(ObjectType.MEDICATION, KnowledgeFacets.objectTypeOf(doc(SourceApp.HEALTH, "dose", "")))
        assertEquals(ObjectType.NOTE, KnowledgeFacets.objectTypeOf(doc(SourceApp.HEALTH, "care", "")))
    }

    @Test
    fun classifies_the_household_directory() {
        assertEquals(ObjectType.PROFILE, KnowledgeFacets.objectTypeOf(doc(SourceApp.PEOPLE, "person", "")))
        assertEquals(ObjectType.DATE, KnowledgeFacets.objectTypeOf(doc(SourceApp.PEOPLE, "date", "")))
        assertEquals(ObjectType.NOTE, KnowledgeFacets.objectTypeOf(doc(SourceApp.PEOPLE, "person-note", "")))
    }

    @Test
    fun a_health_reading_or_a_birthday_has_no_state_to_disagree_about() {
        // A temperature and a birthday are facts with a timestamp; neither moves through states.
        assertNull(KnowledgeFacets.stateOf(doc(SourceApp.HEALTH, "temperature", "Temperature: 38.2C")))
        assertNull(KnowledgeFacets.stateOf(doc(SourceApp.PEOPLE, "date", "Birthday: 3 March")))
    }

    @Test
    fun classifies_the_project_shelf() {
        assertEquals(ObjectType.PROJECT, KnowledgeFacets.objectTypeOf(doc(SourceApp.PROJECT, "project", "")))
        assertEquals(ObjectType.OUTLINE_PIECE, KnowledgeFacets.objectTypeOf(doc(SourceApp.PROJECT, "outline", "")))
        assertEquals(ObjectType.LORE_ENTRY, KnowledgeFacets.objectTypeOf(doc(SourceApp.PROJECT, "lore", "")))
        assertEquals(ObjectType.TIMELINE_EVENT, KnowledgeFacets.objectTypeOf(doc(SourceApp.PROJECT, "timeline", "")))
        assertEquals(ObjectType.BOARD_CARD, KnowledgeFacets.objectTypeOf(doc(SourceApp.PROJECT, "card", "")))
    }

    @Test
    fun a_written_document_is_the_same_kind_of_thing_wherever_it_lives() {
        // A project's own doc and a document filed on Repository's shelf answer the same question
        // ("which document says…"), so a question about documents must accept both.
        assertEquals(ObjectType.DOCUMENT, KnowledgeFacets.objectTypeOf(doc(SourceApp.PROJECT, "project-doc", "")))
        assertEquals(ObjectType.DOCUMENT, KnowledgeFacets.objectTypeOf(doc(SourceApp.REPOSITORY, "document", "")))
    }

    @Test
    fun classifies_the_maintenance_register() {
        assertEquals(ObjectType.ASSET, KnowledgeFacets.objectTypeOf(doc(SourceApp.MAINTENANCE, "asset", "")))
        assertEquals(ObjectType.UPKEEP_PLAN, KnowledgeFacets.objectTypeOf(doc(SourceApp.MAINTENANCE, "upkeep", "")))
        assertEquals(ObjectType.SERVICE_RECORD, KnowledgeFacets.objectTypeOf(doc(SourceApp.MAINTENANCE, "service", "")))
        assertEquals(ObjectType.COVERAGE, KnowledgeFacets.objectTypeOf(doc(SourceApp.MAINTENANCE, "coverage", "")))
        assertEquals(ObjectType.LOAN, KnowledgeFacets.objectTypeOf(doc(SourceApp.MAINTENANCE, "loan", "")))
        assertEquals(ObjectType.RECALL, KnowledgeFacets.objectTypeOf(doc(SourceApp.MAINTENANCE, "recall", "")))
    }

    @Test
    fun a_board_card_uses_the_task_states() {
        assertEquals(
            KnowledgeFacets.DONE,
            KnowledgeFacets.stateOf(doc(SourceApp.PROJECT, "card", "Board card in N: X. Status: done. Column: Shipped"))
        )
        assertEquals(
            KnowledgeFacets.TODO,
            KnowledgeFacets.stateOf(doc(SourceApp.PROJECT, "card", "Board card in N: X. Status: todo. Column: Next"))
        )
    }

    @Test
    fun the_drafting_ladder_is_not_the_task_ladder() {
        // "Drafted" is its own answer: a drafted scene is written, and is not a finished one.
        assertEquals(
            KnowledgeFacets.DRAFTED,
            KnowledgeFacets.stateOf(doc(SourceApp.PROJECT, "outline", "Scene in N: X. Status: drafted"))
        )
        assertEquals(
            KnowledgeFacets.DONE,
            KnowledgeFacets.stateOf(doc(SourceApp.PROJECT, "outline", "Scene in N: X. Status: revised"))
        )
        assertEquals(
            KnowledgeFacets.TODO,
            KnowledgeFacets.stateOf(doc(SourceApp.PROJECT, "outline", "Scene in N: X. Status: outlined"))
        )
        // Cut material is kept in the outline and is deliberately neither done nor to-do.
        assertEquals(
            KnowledgeFacets.CUT,
            KnowledgeFacets.stateOf(doc(SourceApp.PROJECT, "outline", "Scene in N: X. Status: cut. Cut — kept"))
        )
    }

    @Test
    fun upkeep_and_cover_report_maintenances_own_verdict() {
        assertEquals(
            KnowledgeFacets.OVERDUE,
            KnowledgeFacets.stateOf(doc(SourceApp.MAINTENANCE, "upkeep", "Upkeep for Jeep: Oil. Status: overdue. Due 3 weeks ago"))
        )
        assertEquals(
            KnowledgeFacets.DUE_SOON,
            KnowledgeFacets.stateOf(doc(SourceApp.MAINTENANCE, "coverage", "Insurance on Jeep with X. Status: due soon. Renews in 2 weeks"))
        )
        assertEquals(
            KnowledgeFacets.DORMANT,
            KnowledgeFacets.stateOf(doc(SourceApp.MAINTENANCE, "upkeep", "Upkeep for Jeep: Shutters. Status: dormant. Paused"))
        )
    }

    @Test
    fun a_recall_is_owed_until_it_is_acknowledged() {
        assertEquals(
            KnowledgeFacets.OUTSTANDING,
            KnowledgeFacets.stateOf(doc(SourceApp.MAINTENANCE, "recall", "Recall on Jeep (19V680000): Seat belts. Status: outstanding"))
        )
        assertEquals(
            KnowledgeFacets.ACKNOWLEDGED,
            KnowledgeFacets.stateOf(doc(SourceApp.MAINTENANCE, "recall", "Recall on Jeep (19V680000): Seat belts. Status: acknowledged"))
        )
    }

    @Test
    fun an_asset_a_document_and_a_loan_have_no_state_to_disagree_about() {
        // A house, a filed deed and a mortgage are facts about what the household has; none of them
        // moves through a lifecycle a question could ask to match.
        assertNull(KnowledgeFacets.stateOf(doc(SourceApp.MAINTENANCE, "asset", "Asset: Jeep (vehicle). Status: nonsense")))
        assertNull(KnowledgeFacets.stateOf(doc(SourceApp.REPOSITORY, "document", "Filed document: Deed (title or deed)")))
        assertNull(KnowledgeFacets.stateOf(doc(SourceApp.MAINTENANCE, "loan", "Loan on Home: Mortgage. Balance: 180,000.00")))
    }
}
