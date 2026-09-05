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
    OPERATION("operation"),
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
    CHECK_IN("check-in"),
    PARTNER_TASK("partner's task"),
    PROJECT("project"),
    OUTLINE_PIECE("outline piece"),
    LORE_ENTRY("lore entry"),
    TIMELINE_EVENT("timeline event"),
    BOARD_CARD("board card"),
    DOCUMENT("document"),
    ASSET("asset"),
    UPKEEP_PLAN("upkeep job"),
    SERVICE_RECORD("service record"),
    COVERAGE("policy"),
    LOAN("loan"),
    RECALL("recall"),
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
 *  - tasks/operations: `done`, `doing`, `todo`
 *  - pantry: `low`, `stocked`  ·  grocery: `needed`, `bought`
 *  - recipes and shelved ideas: none — a recipe has no lifecycle, and an idea's active/archived
 *    split is a shelf, not a state a question ever asks to match
 *  - health records, medications, people and their dates: none — a temperature reading or a birthday
 *    is a fact with a timestamp, not something that moves through states
 *  - board cards and a partner's tasks: `done`, `todo`
 *  - check-ins: none — a day that was recorded is a fact about that day, not a state it is in
 *  - outline pieces: `todo`, `doing`, `drafted`, `done`, `cut` — the drafting ladder, which is not
 *    the task ladder: a *drafted* scene is not a finished one, and *cut* material is kept
 *  - upkeep jobs and policies: `overdue`, `due_soon`, `scheduled`, `needs_baseline`, `dormant` —
 *    Maintenance's own verdict, read back rather than recomputed
 *  - recalls: `outstanding`, `acknowledged`
 *  - projects, lore, timeline events, assets, service records, loans and documents: none — a deed
 *    filed in March and a house are facts, not things that move through states
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
            "operation" -> ObjectType.OPERATION
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
        // Health and People describe the same household from two sides, so they share a branch: a
        // person is a person whichever app holds them, and a note is the same kind of thing
        // wherever it was written. What is *not* shared is a state — see [stateOf].
        SourceApp.HEALTH, SourceApp.PEOPLE -> when (doc.kind) {
            "person" -> ObjectType.PROFILE
            "temperature", "temperature-history", "reading", "symptom", "illness" -> ObjectType.HEALTH_RECORD
            "medication", "dose" -> ObjectType.MEDICATION
            "date" -> ObjectType.DATE
            "care", "person-note" -> ObjectType.NOTE
            // The daily log: the form, one day, and the shape of the rest are all the same kind of
            // thing to ask about ("what does the check-in say"), so they share one facet.
            "check-in", "check-in-form", "check-in-history" -> ObjectType.CHECK_IN
            // A partner's week is a task — but somebody else's, which is the whole point of the
            // seam and the reason it does not answer to "what are *my* tasks". The pairing itself
            // is left UNKNOWN: it is not a task, and typing it would let a question about what a
            // partner has finished throw the link away as a state mismatch.
            "partner-task" -> ObjectType.PARTNER_TASK
            else -> ObjectType.UNKNOWN
        }
        SourceApp.PROJECT -> when (doc.kind) {
            "project" -> ObjectType.PROJECT
            "outline" -> ObjectType.OUTLINE_PIECE
            // A project's own writing and a document filed on Repository's shelf are the same kind
            // of thing to ask about ("which document says…"), so they share a facet even though
            // one is written here and the other was handed to the household.
            "project-doc" -> ObjectType.DOCUMENT
            "lore" -> ObjectType.LORE_ENTRY
            "timeline" -> ObjectType.TIMELINE_EVENT
            "card" -> ObjectType.BOARD_CARD
            else -> ObjectType.UNKNOWN
        }
        SourceApp.MAINTENANCE -> when (doc.kind) {
            "asset" -> ObjectType.ASSET
            "upkeep" -> ObjectType.UPKEEP_PLAN
            "service" -> ObjectType.SERVICE_RECORD
            "coverage" -> ObjectType.COVERAGE
            "loan" -> ObjectType.LOAN
            "recall" -> ObjectType.RECALL
            else -> ObjectType.UNKNOWN
        }
        SourceApp.REPOSITORY -> when (doc.kind) {
            "document" -> ObjectType.DOCUMENT
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
        ObjectType.TASK, ObjectType.OPERATION -> normalizeTaskState(DocumentFacts.status(doc))
        ObjectType.PANTRY_ITEM -> if (DocumentFacts.isLowStock(doc)) LOW else STOCKED
        ObjectType.GROCERY_ITEM -> if (DocumentFacts.groceryNeeded(doc)) NEEDED else BOUGHT
        // A partner's task is still a task, and its source writes the same `Status:` line.
        ObjectType.PARTNER_TASK -> normalizeTaskState(DocumentFacts.status(doc))
        // A board card is a task by another name, and its source writes the same `Status:` line.
        ObjectType.BOARD_CARD -> normalizeTaskState(DocumentFacts.status(doc))
        // The drafting ladder is its own vocabulary — a *drafted* scene is not a *done* one — so it
        // is normalized separately rather than squeezed into the task states.
        ObjectType.OUTLINE_PIECE -> normalizeDraftState(DocumentFacts.status(doc))
        // Upkeep and cover share one due vocabulary, which is Maintenance's own: the source writes
        // the verdict the app itself computed, and this only reads it back.
        ObjectType.UPKEEP_PLAN, ObjectType.COVERAGE -> normalizeDueState(DocumentFacts.status(doc))
        ObjectType.RECALL -> normalizeRecallState(DocumentFacts.status(doc))
        else -> null
    }

    /**
     * The drafting ladder, normalized. `revised` and `done` both mean *finished* and `cut` is its
     * own answer — cut material is still in the outline and is deliberately not "done".
     */
    private fun normalizeDraftState(status: String?): String? {
        val s = status?.lowercase()?.trim() ?: return null
        return when (s) {
            "done", "revised" -> DONE
            "drafting" -> DOING
            "idea", "outlined" -> TODO
            "drafted" -> DRAFTED
            "cut" -> CUT
            else -> s
        }
    }

    /** The due vocabulary shared by an upkeep plan and a policy, as its source phrased it. */
    private fun normalizeDueState(status: String?): String? {
        val s = status?.lowercase()?.trim() ?: return null
        return when (s) {
            "overdue" -> OVERDUE
            "due soon" -> DUE_SOON
            "scheduled" -> SCHEDULED
            "needs baseline" -> NEEDS_BASELINE
            "dormant" -> DORMANT
            else -> s
        }
    }

    /** A recall is owed until it is marked dealt with. Two states, and no ladder between them. */
    private fun normalizeRecallState(status: String?): String? {
        val s = status?.lowercase()?.trim() ?: return null
        return when (s) {
            "acknowledged" -> ACKNOWLEDGED
            "outstanding" -> OUTSTANDING
            else -> s
        }
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
    const val DRAFTED = "drafted"
    const val CUT = "cut"
    const val OVERDUE = "overdue"
    const val DUE_SOON = "due_soon"
    const val SCHEDULED = "scheduled"
    const val NEEDS_BASELINE = "needs_baseline"
    const val DORMANT = "dormant"
    const val OUTSTANDING = "outstanding"
    const val ACKNOWLEDGED = "acknowledged"
}
