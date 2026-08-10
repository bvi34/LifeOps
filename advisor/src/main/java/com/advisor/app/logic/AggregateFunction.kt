package com.advisor.app.logic

/**
 * The "calculator over your data": counts and numeric roll-ups over the records you already collect —
 *
 * - **counts**: "how many tasks do I have?", "how many books?", "how many tasks are done?";
 * - **task time**: "how much time do my tasks take?", "average task estimate", "longest task";
 * - **milestone points**: "how many points have I earned?", "average points per milestone".
 *
 * Numbers come from [DocumentFacts]. Corpus-backed scopes honour the permission gate. It stays out of
 * the pantry/grocery lane (that's [InventoryFunction]) and out of word counting (that's
 * [WordUsageFunction]). Pure and JVM-testable.
 */
class AggregateFunction : AdvisorFunction {

    override val name: String = "aggregate"

    override fun handles(question: String): Boolean = parse(question) != null

    override fun run(request: FunctionRequest): FunctionResult =
        when (val plan = parse(request.question)) {
            null -> FunctionResult("I couldn't tell what to total up.")
            is Plan.Numeric -> numeric(plan, request)
            is Plan.Count -> count(plan, request)
        }

    // --- numeric roll-ups ---

    private fun numeric(plan: Plan.Numeric, request: FunctionRequest): FunctionResult {
        if (SourceApp.LIFEOPS !in request.grantedApps) return enable("LifeOps")

        val (docs, values, unit, label) = when (plan.metric) {
            Metric.TASK_TIME -> Quad(
                kind(request, "task"),
                kind(request, "task").mapNotNull { DocumentFacts.estimateMinutes(it)?.toDouble() },
                "min", "estimated task"
            )
            Metric.MILESTONE_POINTS -> Quad(
                kind(request, "milestone"),
                kind(request, "milestone").mapNotNull { DocumentFacts.points(it)?.toDouble() },
                "pts", "milestone"
            )
        }
        if (values.isEmpty()) {
            return FunctionResult("None of your ${label}s have a ${if (unit == "min") "time estimate" else "points value"} recorded yet.")
        }

        val result = when (plan.op) {
            Op.SUM -> values.sum()
            Op.AVERAGE -> values.average()
            Op.MIN -> values.min()
            Op.MAX -> values.max()
            Op.COUNT -> values.size.toDouble()
        }
        val n = values.size
        val text = when (plan.op) {
            Op.SUM -> "Across $n $label${s(n)}, that's ${amount(result, unit)} in total."
            Op.AVERAGE -> "That averages ${amount(result, unit)} per $label (over $n)."
            Op.MIN -> "The smallest is ${amount(result, unit)} (of $n $label${s(n)})."
            Op.MAX -> "The largest is ${amount(result, unit)} (of $n $label${s(n)})."
            Op.COUNT -> "$n $label${s(n)} have a value recorded."
        }
        return FunctionResult(text, docs.take(MAX_CITATIONS))
    }

    // --- counts ---

    private fun count(plan: Plan.Count, request: FunctionRequest): FunctionResult {
        val app = plan.scope.app
        if (app != null && app !in request.grantedApps) return enable(app.displayName)

        val docs = when (plan.scope) {
            Scope.TASKS -> kind(request, "task")
            Scope.PROJECTS -> kind(request, "project")
            Scope.MILESTONES -> kind(request, "milestone")
            Scope.BOOKS -> kind(request, "book")
            Scope.NOTES -> kind(request, "note")
            Scope.MEMORIES -> null
            Scope.PROFILES -> null
        }
        val total = docs?.size ?: when (plan.scope) {
            Scope.MEMORIES -> request.memories.size
            Scope.PROFILES -> request.profiles.size
            else -> 0
        }

        // Optional status/priority filter, tasks only.
        if (plan.filter != null && plan.scope == Scope.TASKS && docs != null) {
            val matched = docs.filter { matchesFilter(it, plan.filter) }
            return FunctionResult("${matched.size} of your $total ${noun(plan.scope)} ${be(matched.size)} ${plan.filter}.", matched.take(MAX_CITATIONS))
        }
        return FunctionResult("You have $total ${noun(plan.scope, total)}.", (docs ?: emptyList()).take(MAX_CITATIONS))
    }

    private fun matchesFilter(doc: KnowledgeDocument, filter: String): Boolean {
        val status = DocumentFacts.status(doc)?.lowercase().orEmpty()
        val priority = DocumentFacts.priority(doc)?.lowercase().orEmpty()
        val synonyms = FILTER_SYNONYMS[filter] ?: setOf(filter)
        return synonyms.any { status.contains(it) || priority.contains(it) }
    }

    // --- helpers ---

    private fun kind(request: FunctionRequest, kind: String): List<KnowledgeDocument> {
        val app = if (kind == "book" || kind == "note") SourceApp.CITATION else SourceApp.LIFEOPS
        return request.corpus.filter { it.source == app && it.kind == kind }
    }

    private fun enable(app: String) =
        FunctionResult("That needs your $app data, which isn't enabled right now. Turn $app on in Permissions and ask again.")

    private fun amount(value: Double, unit: String): String {
        val n = trim(value)
        if (unit == "min" && value >= 60) {
            val total = Math.round(value).toInt()
            return "$n min (${total / 60}h ${total % 60}m)"
        }
        return "$n $unit"
    }

    private fun trim(value: Double): String =
        if (value % 1.0 == 0.0) value.toLong().toString()
        else String.format("%.1f", value).trimEnd('0').trimEnd('.')

    private fun s(n: Int) = if (n == 1) "" else "s"
    private fun be(n: Int) = if (n == 1) "is" else "are"
    private fun noun(scope: Scope, n: Int = 2): String {
        val one = when (scope) {
            Scope.TASKS -> "task"; Scope.PROJECTS -> "project"; Scope.MILESTONES -> "milestone"
            Scope.BOOKS -> "book"; Scope.NOTES -> "note"; Scope.MEMORIES -> "memory"; Scope.PROFILES -> "profile"
        }
        return if (n == 1) one else if (one == "memory") "memories" else one + "s"
    }

    // --- parsing ---

    private enum class Op { SUM, AVERAGE, MIN, MAX, COUNT }
    private enum class Metric { TASK_TIME, MILESTONE_POINTS }
    private enum class Scope(val app: SourceApp?) {
        TASKS(SourceApp.LIFEOPS), PROJECTS(SourceApp.LIFEOPS), MILESTONES(SourceApp.LIFEOPS),
        BOOKS(SourceApp.CITATION), NOTES(SourceApp.CITATION), MEMORIES(null), PROFILES(null)
    }

    private sealed interface Plan {
        data class Numeric(val op: Op, val metric: Metric) : Plan
        data class Count(val scope: Scope, val filter: String?) : Plan
    }

    private fun parse(question: String): Plan? {
        val q = question.trim().removeSuffix("?").trim().lowercase()
        if (q.isEmpty()) return null

        // A numeric metric (task time / milestone points) takes precedence over a bare count.
        val metric = when {
            POINTS_METRIC.containsMatchIn(q) -> Metric.MILESTONE_POINTS
            TASK_TIME_METRIC.containsMatchIn(q) -> Metric.TASK_TIME
            else -> null
        }
        if (metric != null) {
            val op = when {
                AVERAGE.containsMatchIn(q) -> Op.AVERAGE
                MAX.containsMatchIn(q) -> Op.MAX
                MIN.containsMatchIn(q) -> Op.MIN
                SUM.containsMatchIn(q) -> Op.SUM
                else -> Op.SUM // "how much time / how many points" reads as a total
            }
            return Plan.Numeric(op, metric)
        }

        // Otherwise a count of a scope: "how many <noun>", "count my <noun>", "number of <noun>".
        val scope = COUNT_SCOPE.find(q)?.let { scopeFrom(it.groupValues[1]) } ?: return null
        val filter = if (scope == Scope.TASKS) TASK_FILTER.find(q)?.groupValues?.get(1)?.trim() else null
        return Plan.Count(scope, filter?.takeIf { it.isNotBlank() })
    }

    private fun scopeFrom(word: String): Scope? = when (word.lowercase().trim()) {
        "task", "tasks", "to-do", "to-dos", "todo", "todos" -> Scope.TASKS
        "project", "projects" -> Scope.PROJECTS
        "milestone", "milestones" -> Scope.MILESTONES
        "book", "books" -> Scope.BOOKS
        "note", "notes" -> Scope.NOTES
        "memory", "memories" -> Scope.MEMORIES
        "profile", "profiles" -> Scope.PROFILES
        else -> null
    }

    private data class Quad(
        val docs: List<KnowledgeDocument>,
        val values: List<Double>,
        val unit: String,
        val label: String
    )

    private companion object {
        const val MAX_CITATIONS = 12

        val SUM = Regex("""(?i)\b(total|sum|combined|altogether|in total|add up|how much time|how many (?:minutes|points))\b""")
        val AVERAGE = Regex("""(?i)\b(average|avg|mean)\b""")
        val MAX = Regex("""(?i)\b(max|maximum|highest|longest|biggest|most)\b""")
        val MIN = Regex("""(?i)\b(min|minimum|lowest|shortest|smallest|least|fewest)\b""")

        val TASK_TIME_METRIC = Regex("""(?i)\b(?:task|tasks)\b.*\b(?:time|minute|minutes|estimate|estimates|estimated|duration|long)\b|""" +
            """\b(?:time|minutes|estimate|estimated|duration)\b.*\b(?:task|tasks)\b""")
        val POINTS_METRIC = Regex("""(?i)\b(points|pts)\b|\bmilestone points\b""")

        // "how many <noun>", "count (my) <noun>", "number of <noun>"
        val COUNT_SCOPE = Regex(
            """(?i)\b(?:how many|count(?:\s+my)?|number of|total number of)\s+(?:my\s+|the\s+)?""" +
                """(tasks?|to-dos?|todos?|projects?|milestones?|books?|notes?|memories|memory|profiles?)\b"""
        )
        // A status/priority qualifier for task counts.
        val TASK_FILTER = Regex(
            """(?i)\b(done|completed|complete|finished|open|todo|to-do|pending|in progress|in-progress|""" +
                """doing|active|blocked|overdue|high|medium|low|urgent)\b"""
        )

        val FILTER_SYNONYMS: Map<String, Set<String>> = mapOf(
            "done" to setOf("done", "complete", "finish"),
            "completed" to setOf("done", "complete", "finish"),
            "complete" to setOf("done", "complete", "finish"),
            "finished" to setOf("done", "complete", "finish"),
            "todo" to setOf("todo", "to-do", "to do", "not started", "open", "pending"),
            "to-do" to setOf("todo", "to-do", "to do", "not started", "open", "pending"),
            "pending" to setOf("pending", "open", "todo", "to-do"),
            "open" to setOf("open", "todo", "to-do", "pending"),
            "in progress" to setOf("progress", "doing", "active", "started"),
            "in-progress" to setOf("progress", "doing", "active", "started"),
            "doing" to setOf("progress", "doing", "active", "started")
        )
    }
}
