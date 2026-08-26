package com.advisor.app.logic

/**
 * The **object type** of a retrieved record — the "what kind of thing is this" facet the relevance
 * layer reasons over. It's derived from the record's source app and [KnowledgeDocument.kind], so a
 * candidate can be judged against what the question is actually asking for ("a *book*", "a *task*") and
 * an off-type hit ("Miracle Whip" for "what am I reading?") named for what it is: an object-type
 * mismatch, not a silent row in a dump.
 */
enum class ObjectType(val label: String) {
    BOOK("book"),
    NOTE("note"),
    TASK("task"),
    PROJECT("project"),
    MILESTONE("milestone"),
    ASPECT("life area"),
    RECIPE("recipe"),
    IDEA("shelved idea"),
    PANTRY_ITEM("pantry item"),
    GROCERY_ITEM("grocery item"),
    MEMORY("memory"),
    PROFILE("profile"),
    HEALTH_RECORD("health record"),
    MEDICATION("medication"),
    DATE("date"),
    UNKNOWN("item")
}

/**
 * Reads the two comparable **facets** — object type and lifecycle state — back out of a
 * [KnowledgeDocument]. These are what [RelevanceEngine] compares a candidate against the question's
 * expected facets, so the grounding decision ("relevant", "state mismatch", …) is made over typed
 * values rather than surface words. State is a small normalized vocabulary shared with [QueryFacets] so
 * the two sides compare cleanly:
 *
 *  - books: `reading`, `to_read`, `done`
 *  - tasks/projects: `done`, `doing`, `todo`
 *  - pantry: `low`, `stocked`  ·  grocery: `needed`, `bought`
 *  - recipes and shelved ideas: none — a recipe has no lifecycle, and an idea's active/archived
 *    split is a shelf, not a state a question ever asks to match
 *  - health records, medications, people and their dates: none — a temperature reading or a birthday
 *    is a fact with a timestamp, not something that moves through states
 *
 * A record with no meaningful state (an aspect, a note) reports `null`, which the engine treats as "no
 * state to disagree about" — it never manufactures a state mismatch out of thin air.
 */
object KnowledgeFacets {

    fun objectTypeOf(doc: KnowledgeDocument): ObjectType = when (doc.source) {
        SourceApp.CITATION -> when (doc.kind) {
            "book" -> ObjectType.BOOK
            "note" -> ObjectType.NOTE
            else -> ObjectType.UNKNOWN
        }
        SourceApp.LIFEOPS -> when (doc.kind) {
            "task" -> ObjectType.TASK
            "project" -> ObjectType.PROJECT
            "milestone" -> ObjectType.MILESTONE
            "aspect" -> ObjectType.ASPECT
            // The Collection. A LifeOps book is the same kind of thing as a Citation one — same
            // facet, same "Reading state:" phrasing — so a reading question judges both alike.
            "book" -> ObjectType.BOOK
            "note" -> ObjectType.NOTE
            "recipe" -> ObjectType.RECIPE
            "idea" -> ObjectType.IDEA
            else -> ObjectType.UNKNOWN
        }
        SourceApp.LOGISTICS -> when (doc.kind) {
            "pantry" -> ObjectType.PANTRY_ITEM
            "grocery" -> ObjectType.GROCERY_ITEM
            else -> ObjectType.UNKNOWN
        }
        SourceApp.HEALTH -> when (doc.kind) {
            // A health profile is the same kind of thing as a People directory entry — same facet,
            // so a "who" question judges both alike (as with a book from either library).
            "person" -> ObjectType.PROFILE
            // A dose is a medication taken; both answer "what is she on, and when".
            "medication", "dose" -> ObjectType.MEDICATION
            "temperature", "temperature-history", "reading", "symptom", "illness" -> ObjectType.HEALTH_RECORD
            "care" -> ObjectType.NOTE
            else -> ObjectType.UNKNOWN
        }
        SourceApp.PEOPLE -> when (doc.kind) {
            "person" -> ObjectType.PROFILE
            "date" -> ObjectType.DATE
            "person-note" -> ObjectType.NOTE
            else -> ObjectType.UNKNOWN
        }
    }

    /** The record's normalized lifecycle state, or null when the object type doesn't carry one. */
    fun stateOf(doc: KnowledgeDocument): String? = when (objectTypeOf(doc)) {
        ObjectType.BOOK -> when (DocumentFacts.readingState(doc)) {
            "READING" -> READING
            "TO_READ" -> TO_READ
            "DONE" -> DONE
            else -> null
        }
        ObjectType.TASK, ObjectType.PROJECT -> normalizeTaskState(DocumentFacts.status(doc))
        ObjectType.PANTRY_ITEM -> if (DocumentFacts.isLowStock(doc)) LOW else STOCKED
        ObjectType.GROCERY_ITEM -> if (DocumentFacts.groceryNeeded(doc)) NEEDED else BOUGHT
        else -> null
    }

    private fun normalizeTaskState(status: String?): String? {
        val s = status?.lowercase()?.trim() ?: return null
        return when {
            s.contains("done") || s.contains("complete") || s.contains("finish") -> DONE
            s.contains("progress") || s.contains("doing") || s.contains("active") || s.contains("started") -> DOING
            s.contains("todo") || s.contains("to-do") || s.contains("to do") || s.contains("pending") ||
                s.contains("open") || s.contains("backlog") || s.contains("not started") -> TODO
            else -> s
        }
    }

    // The shared state vocabulary (also produced by QueryFacets), kept here as the single source of truth.
    const val READING = "reading"
    const val TO_READ = "to_read"
    const val DONE = "done"
    const val DOING = "doing"
    const val TODO = "todo"
    const val LOW = "low"
    const val STOCKED = "stocked"
    const val NEEDED = "needed"
    const val BOUGHT = "bought"
}
