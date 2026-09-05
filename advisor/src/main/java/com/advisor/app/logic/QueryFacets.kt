package com.advisor.app.logic

/**
 * What a question is asking *for*, as comparable facets: the [objectTypes] it wants ("a book", "a
 * task") and, when it says so, the lifecycle [state] ("that I'm *reading* now", "*to-read*"). This is
 * the query side of the relevance judgement — [RelevanceEngine] compares each retrieved candidate's
 * facets ([KnowledgeFacets]) against these to decide relevant / object-mismatch / state-mismatch.
 *
 * Inference is deliberately conservative: a facet is only claimed when the wording clearly implies it,
 * so a broad question ("what should I focus on this week?") yields [hasOpinion] == false and leaves the
 * retrieval ranking untouched. The state vocabulary matches [KnowledgeFacets] exactly so the two sides
 * compare without translation.
 */
data class QueryFacets(
    val objectTypes: Set<ObjectType> = emptySet(),
    val state: String? = null
) {
    /** True when the question named something worth filtering on — a type and/or a state. */
    val hasOpinion: Boolean get() = objectTypes.isNotEmpty() || state != null

    companion object {

        fun infer(question: String): QueryFacets {
            val q = QueryUnderstanding.normalize(question).lowercase()
            val types = LinkedHashSet<ObjectType>()
            for ((pattern, type) in TYPE_CUES) if (pattern.containsMatchIn(q)) types += type
            return QueryFacets(objectTypes = types, state = inferState(q, types))
        }

        /** A state facet, only when a clear cue is present and it's meaningful for the inferred types. */
        private fun inferState(q: String, types: Set<ObjectType>): String? {
            // Book lifecycle — only when the question is about books/reading, so a task cue like "in
            // progress" never gets read as a book state (and vice-versa).
            if (types.contains(ObjectType.BOOK)) {
                when {
                    TO_READ_CUE.containsMatchIn(q) -> return KnowledgeFacets.TO_READ
                    FINISHED_CUE.containsMatchIn(q) -> return KnowledgeFacets.DONE
                    // "currently reading", "am I reading", "in a READING status/state", or a bare
                    // "reading" — the word only reaches this book branch as a state cue, so it means
                    // in-progress. Checked last so "reading list" (to-read) and the rest win first.
                    READING_NOW_CUE.containsMatchIn(q) || READING_WORD.containsMatchIn(q) ->
                        return KnowledgeFacets.READING
                }
            }
            // Task/operation lifecycle — and a board card and a partner's task, which move through
            // the same three.
            if (types.contains(ObjectType.TASK) || types.contains(ObjectType.OPERATION) ||
                types.contains(ObjectType.BOARD_CARD) || types.contains(ObjectType.PARTNER_TASK)
            ) {
                when {
                    DONE_CUE.containsMatchIn(q) -> return KnowledgeFacets.DONE
                    DOING_CUE.containsMatchIn(q) -> return KnowledgeFacets.DOING
                    TODO_CUE.containsMatchIn(q) -> return KnowledgeFacets.TODO
                }
            }
            // The drafting ladder. "Drafted" is checked before the generic finished cues, because a
            // drafted scene is exactly the thing a question about drafting is asking for and is not
            // the same as a done one.
            if (types.contains(ObjectType.OUTLINE_PIECE)) {
                when {
                    CUT_CUE.containsMatchIn(q) -> return KnowledgeFacets.CUT
                    DRAFTED_CUE.containsMatchIn(q) -> return KnowledgeFacets.DRAFTED
                    DRAFTING_CUE.containsMatchIn(q) -> return KnowledgeFacets.DOING
                    DONE_CUE.containsMatchIn(q) -> return KnowledgeFacets.DONE
                    TODO_CUE.containsMatchIn(q) -> return KnowledgeFacets.TODO
                }
            }
            // What the house owes. "Overdue" is its own word and beats the softer "coming up".
            if (types.contains(ObjectType.UPKEEP_PLAN) || types.contains(ObjectType.COVERAGE)) {
                when {
                    OVERDUE_CUE.containsMatchIn(q) -> return KnowledgeFacets.OVERDUE
                    DUE_SOON_CUE.containsMatchIn(q) -> return KnowledgeFacets.DUE_SOON
                }
            }
            if (types.contains(ObjectType.RECALL)) {
                when {
                    ACKNOWLEDGED_CUE.containsMatchIn(q) -> return KnowledgeFacets.ACKNOWLEDGED
                    OUTSTANDING_CUE.containsMatchIn(q) -> return KnowledgeFacets.OUTSTANDING
                }
            }
            return null
        }

        // Object-type cues. Ordered, but all matching types are collected (a question can want two).
        private val TYPE_CUES: List<Pair<Regex, ObjectType>> = listOf(
            Regex("""\b(book|books|reading|read|novel|novels|audiobook|library|reader)\b""") to ObjectType.BOOK,
            Regex("""\b(note|notes|highlight|highlights|quote|quotes|annotation|annotations)\b""") to ObjectType.NOTE,
            Regex("""\b(task|tasks|todo|todos|to-do|to-dos|chore|chores|errand|errands)\b""") to ObjectType.TASK,
            // "project"/"projects" stay in the cue list: LifeOps renamed the thing to an
            // Operation, but a question is asked in whatever word the user has in their head,
            // and the old one will be in there for a long while yet.
            Regex("""\b(operation|operations|project|projects)\b""") to ObjectType.OPERATION,
            Regex("""\b(milestone|milestones|achievement|achievements)\b""") to ObjectType.MILESTONE,
            Regex("""\b(recipe|recipes|cook|cooking|dish|dishes|bake|baking)\b""") to ObjectType.RECIPE,
            Regex("""\b(idea|ideas|someday|future operation|future operations|future project|future projects|backlog|shelved)\b""") to ObjectType.IDEA,
            Regex("""\b(pantry|ingredient|ingredients|in stock|restock|fridge)\b""") to ObjectType.PANTRY_ITEM,
            Regex("""\b(grocery|groceries|shopping list|to buy)\b""") to ObjectType.GROCERY_ITEM,
            // Health and People. "Reading" is deliberately absent from the health cue: it is already
            // the book cue, and a question about what you are reading must not start expecting
            // blood-pressure readings.
            Regex("""\b(temperature|temperatures|fever|fevers|symptom|symptoms|illness|illnesses|poorly|sick)\b""")
                to ObjectType.HEALTH_RECORD,
            Regex("""\b(medicine|medicines|medication|medications|dose|doses|paracetamol|ibuprofen|antibiotic|antibiotics)\b""")
                to ObjectType.MEDICATION,
            Regex("""\b(birthday|birthdays|anniversary|anniversaries)\b""") to ObjectType.DATE,
            Regex("""\b(check[- ]?in|check[- ]?ins|checked in|streak|streaks|daily log|journal)\b""")
                to ObjectType.CHECK_IN,
            Regex("""\b(partner|partner'?s|partners)\b""") to ObjectType.PARTNER_TASK,
            // Project. "Project" is deliberately two cues: LifeOps calls the thing an Operation and
            // the Project app calls its own thing a project, and a question asked in that word wants
            // whichever of them the user has. Both types are accepted; neither is a mismatch.
            Regex("""\b(project|projects)\b""") to ObjectType.PROJECT,
            Regex("""\b(outline|outlines|chapter|chapters|scene|scenes|act|acts|draft|drafts|drafted|drafting|manuscript)\b""")
                to ObjectType.OUTLINE_PIECE,
            Regex("""\b(lore|wiki|worldbuilding|character|characters|faction|factions)\b""") to ObjectType.LORE_ENTRY,
            Regex("""\b(timeline|timelines|chronology|era|eras)\b""") to ObjectType.TIMELINE_EVENT,
            Regex("""\b(board|kanban|card|cards|column|columns)\b""") to ObjectType.BOARD_CARD,
            // Repository (and a project's own documents, which share the facet).
            Regex("""\b(document|documents|paperwork|statement|statements|receipt|receipts|manual|manuals|""" +
                """deed|deeds|contract|contracts|invoice|invoices|policy document|filed|shelf|scan|scans)\b""")
                to ObjectType.DOCUMENT,
            // Maintenance.
            Regex("""\b(asset|assets|car|cars|vehicle|vehicles|truck|trucks|house|home|appliance|appliances|""" +
                """furnace|mower|motorcycle|motorbike|odometer|mileage)\b""") to ObjectType.ASSET,
            Regex("""\b(upkeep|maintenance|servicing|oil change|filter|tune-up|schedule|scheduled)\b""")
                to ObjectType.UPKEEP_PLAN,
            Regex("""\b(service history|service record|service records|serviced|repair|repairs|repaired|garage|mechanic)\b""")
                to ObjectType.SERVICE_RECORD,
            Regex("""\b(insurance|insured|policy|policies|warranty|warranties|registration|coverage|premium|premiums)\b""")
                to ObjectType.COVERAGE,
            Regex("""\b(loan|loans|mortgage|mortgages|payoff|paid off|principal|escrow|amortisation|amortization)\b""")
                to ObjectType.LOAN,
            Regex("""\b(recall|recalls|recalled)\b""") to ObjectType.RECALL
        )

        // Explicit state names ("to_read", "to-read status") as well as conversational phrasings.
        private val TO_READ_CUE = Regex(
            """\b(to[-_ ]?read|want to read|going to read|plan(?:ning)? to read|read next|reading list|""" +
                """to be read|tbr|haven'?t read|not read yet|unread|read soon|queued?)\b"""
        )
        // Bare "done/completed/finished" count here (this branch is book-only, so they mean finished),
        // as do "done/finished/completed status/state".
        private val FINISHED_CUE = Regex(
            """\b(finished|done|completed|complete|already read|have i read|have read|""" +
                """i(?:'ve| have) read|did i read|done reading|read this year)\b"""
        )
        private val READING_NOW_CUE = Regex(
            """\b(currently|right now|these days|at the moment|in progress|reading now|am i reading|""" +
                """i(?:'m| am) reading|book am i on|reading currently|reading status|reading state)\b"""
        )
        private val READING_WORD = Regex("""\breading\b""")

        private val DONE_CUE = Regex("""\b(done|completed|complete|finished|accomplished)\b""")
        private val DOING_CUE = Regex("""\b(in progress|in-progress|working on|doing|underway|started)\b""")
        private val TODO_CUE = Regex(
            """\b(to do|to-do|todo|pending|open|outstanding|unfinished|not done|still (?:need|have)|left to do)\b"""
        )

        // The drafting ladder's own words, kept apart from the task ones for the same reason the
        // states are: "drafted" and "done" are two different answers about the same scene.
        private val DRAFTED_CUE = Regex("""\b(drafted|first draft|rough draft|written)\b""")
        private val DRAFTING_CUE = Regex("""\b(drafting|being written|mid-draft|part.?written)\b""")
        private val CUT_CUE = Regex("""\b(cut|cut material|deleted scene|deleted scenes)\b""")

        // What is owed, and how soon.
        private val OVERDUE_CUE = Regex("""\b(overdue|late|past due|missed|behind on|lapsed|expired)\b""")
        private val DUE_SOON_CUE = Regex(
            """\b(due|due soon|coming up|upcoming|this month|next month|soon|renew|renewing|renewal)\b"""
        )
        private val OUTSTANDING_CUE = Regex("""\b(open|outstanding|unfixed|not fixed|still owed|owed)\b""")
        private val ACKNOWLEDGED_CUE = Regex("""\b(acknowledged|dealt with|handled|sorted|done)\b""")
    }
}
