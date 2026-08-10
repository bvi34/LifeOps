package com.advisor.app.logic

/**
 * Counts words the user (or the assistant) actually wrote — the capability behind "how many times have
 * I said *fuck*?" and "count my word usage in my tasks". Two modes:
 *
 * - **specific**: a target word/phrase is named → the exact whole-word occurrence count in the chosen
 *   scope ("You've said \"fuck\" 3 times in our conversation.");
 * - **summary**: no target ("count my word usage") → the most-used words in the scope.
 *
 * The **scope** is parsed from the question ("in my tasks", "in my notes", …) or defaults sensibly —
 * "said/typed" means the conversation, otherwise the whole enabled corpus. App scopes respect the
 * permission gate: if the app isn't enabled, it says so rather than counting an empty set as zero.
 *
 * Pure and JVM-testable; it computes over the [FunctionRequest] the repository assembles.
 */
class WordUsageFunction : AdvisorFunction {

    override val name: String = "word-usage"

    override fun handles(question: String): Boolean = parse(question) != null

    override fun run(request: FunctionRequest): FunctionResult {
        val parsed = parse(request.question)
            ?: return FunctionResult("I couldn't tell which words to count — try \"how many times have I said <word>\".")

        // App-backed scopes are gated: don't report zero for data the user simply hasn't enabled.
        val app = parsed.scope.app
        if (app != null && app !in request.grantedApps) {
            return FunctionResult(
                "Counting ${parsed.scope.label} needs your ${app.displayName} data, which isn't " +
                    "enabled right now. Turn ${app.displayName} on in Permissions and ask again."
            )
        }

        val sources = gather(parsed, request)
        if (sources.isEmpty()) {
            return FunctionResult("I don't have any of ${parsed.scope.label} to count yet.")
        }

        return if (parsed.target != null) countSpecific(parsed, sources)
        else summarize(parsed, sources)
    }

    // --- counting ---

    private fun countSpecific(parsed: Parsed, sources: List<TextUnit>): FunctionResult {
        val needle = Regex("(?<![A-Za-z0-9])" + Regex.escape(parsed.target!!) + "(?![A-Za-z0-9])", RegexOption.IGNORE_CASE)
        var total = 0
        val hitDocs = ArrayList<KnowledgeDocument>()
        for (unit in sources) {
            val n = needle.findAll(unit.text).count()
            if (n > 0) {
                total += n
                unit.document?.let { if (it !in hitDocs) hitDocs += it }
            }
        }

        val subject = if (parsed.subject == Subject.ASSISTANT) "I've" else "you've"
        val text = if (total == 0) {
            "I don't see \"${parsed.target}\" anywhere in ${parsed.scope.label} (0 matches)."
        } else {
            val times = if (total == 1) "1 time" else "$total times"
            buildString {
                append("By my count, $subject used \"${parsed.target}\" $times in ${parsed.scope.label}")
                if (hitDocs.isNotEmpty()) append(" — across ${hitDocs.size} ${itemNoun(parsed.scope, hitDocs.size)}")
                append('.')
            }
        }
        return FunctionResult(text, hitDocs.take(MAX_CITATIONS))
    }

    private fun summarize(parsed: Parsed, sources: List<TextUnit>): FunctionResult {
        val counts = HashMap<String, Int>()
        for (unit in sources) for (token in Retriever.tokenize(unit.text)) {
            counts[token] = (counts[token] ?: 0) + 1
        }
        if (counts.isEmpty()) {
            return FunctionResult("There aren't enough words in ${parsed.scope.label} to tally yet.")
        }
        val top = counts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(TOP_WORDS)

        val text = buildString {
            append("Your most-used words in ${parsed.scope.label} (${sources.size} ")
            append(itemNoun(parsed.scope, sources.size)).append(" scanned):")
            for (entry in top) append("\n• ${entry.key} — ${entry.value}")
        }
        return FunctionResult(text)
    }

    // --- gathering text for a scope ---

    /** A unit of text to scan, with the document it came from (for citations) when it is corpus-backed. */
    private data class TextUnit(val text: String, val document: KnowledgeDocument? = null)

    private fun gather(parsed: Parsed, request: FunctionRequest): List<TextUnit> = when (parsed.scope) {
        Scope.CONVERSATION -> request.conversation
            .filter { if (parsed.subject == Subject.ASSISTANT) !it.fromUser else it.fromUser }
            .filter { it.text.isNotBlank() }
            .map { TextUnit(it.text) }

        Scope.MEMORY -> request.memories.map { TextUnit(it.searchableText()) }

        Scope.PROFILES -> request.profiles.flatMap { p -> p.entries.map { TextUnit(it.text) } }

        Scope.ALL -> request.corpus.map { TextUnit(docText(it), it) } +
            request.conversation.filter { it.fromUser && it.text.isNotBlank() }.map { TextUnit(it.text) }

        else -> request.corpus
            .filter { it.source == parsed.scope.app && (parsed.scope.kind == null || it.kind == parsed.scope.kind) }
            .map { TextUnit(docText(it), it) }
    }

    private fun docText(doc: KnowledgeDocument): String = (doc.title + "\n" + doc.body).trim()

    private fun itemNoun(scope: Scope, n: Int): String {
        val one = when (scope) {
            Scope.CONVERSATION, Scope.ALL -> "message"
            Scope.TASKS -> "task"
            Scope.NOTES -> "note"
            Scope.BOOKS -> "book"
            Scope.PANTRY -> "pantry item"
            Scope.GROCERIES -> "grocery item"
            Scope.MEMORY -> "memory"
            Scope.PROFILES -> "entry"
        }
        return if (n == 1) one else one + "s"
    }

    // --- parsing the request ---

    private enum class Subject { USER, ASSISTANT }

    private enum class Scope(val label: String, val app: SourceApp?, val kind: String?) {
        CONVERSATION("our conversation", null, null),
        TASKS("your tasks", SourceApp.LIFEOPS, "task"),
        NOTES("your reading notes", SourceApp.CITATION, "note"),
        BOOKS("your books", SourceApp.CITATION, "book"),
        PANTRY("your pantry", SourceApp.LOGISTICS, "pantry"),
        GROCERIES("your grocery list", SourceApp.LOGISTICS, "grocery"),
        MEMORY("your long-term memory", null, null),
        PROFILES("your standing profiles", null, null),
        ALL("your enabled data", null, null)
    }

    private data class Parsed(val target: String?, val scope: Scope, val subject: Subject)

    /** Interpret [question]; null when it isn't a word-usage request. */
    private fun parse(question: String): Parsed? {
        val q = question.trim().removeSuffix("?").trim()
        if (q.isEmpty()) return null

        val (scopeFromClause, core) = extractScope(q)
        val subject = if (SUBJECT_ASSISTANT.containsMatchIn(q)) Subject.ASSISTANT else Subject.USER

        // Summary: "word usage / word count / most used words / how many words".
        if (SUMMARY.containsMatchIn(q)) {
            return Parsed(target = null, scope = scopeFromClause ?: defaultScope(q, summary = true), subject = subject)
        }

        // Specific: "how many times have I said <target>".
        val target = SPECIFIC.find(core)?.groupValues?.get(1)?.trim()?.trim('"', '\'', '.', '!')?.trim()
        if (!target.isNullOrBlank()) {
            return Parsed(target = target, scope = scopeFromClause ?: defaultScope(q, summary = false), subject = subject)
        }
        return null
    }

    /** "said/typed" points at the conversation; a bare summary spans all enabled data. */
    private fun defaultScope(q: String, summary: Boolean): Scope =
        if (!summary || CONVERSATIONAL_VERB.containsMatchIn(q)) Scope.CONVERSATION else Scope.ALL

    /** Pull an "in my <scope>" clause out of [q], returning the scope and the question with it removed. */
    private fun extractScope(q: String): Pair<Scope?, String> {
        val m = SCOPE_CLAUSE.find(q) ?: return null to q
        val scope = when (m.groupValues[1].lowercase().trim()) {
            "task", "tasks", "to-do", "todos", "to-dos" -> Scope.TASKS
            "note", "notes" -> Scope.NOTES
            "book", "books", "library" -> Scope.BOOKS
            "pantry" -> Scope.PANTRY
            "grocery", "groceries", "grocery list" -> Scope.GROCERIES
            "memory", "memories" -> Scope.MEMORY
            "profile", "profiles" -> Scope.PROFILES
            "message", "messages", "chat", "chats", "conversation", "conversations" -> Scope.CONVERSATION
            else -> null
        }
        val core = q.removeRange(m.range).trim().ifEmpty { q }
        return scope to core
    }

    private companion object {
        const val TOP_WORDS = 10
        const val MAX_CITATIONS = 8

        private const val SAY_VERB =
            "(?:say|said|says|saying|use|used|uses|using|type|typed|types|typing|write|wrote|written|writes)"

        // "how many times / how often … I|we|you <verb> [the word] <target>"
        val SPECIFIC = Regex(
            """(?i)\bhow\s+(?:many\s+times|often)\b[^"']*?\b(?:i|we|you)\b\s+$SAY_VERB\b\s+""" +
                """(?:the\s+word\s+|the\s+phrase\s+|word\s+|phrase\s+)?["']?([A-Za-z0-9][A-Za-z0-9'\- ]*?)["']?\s*$"""
        )

        val SUMMARY = Regex(
            """(?i)\b(?:word\s+usage|word\s+count|word\s+frequency|most\s+(?:used|common|frequent)\s+words|""" +
                """how\s+many\s+(?:different\s+|unique\s+)?words|vocabulary)\b"""
        )

        // "in / across / from my tasks|notes|…"
        val SCOPE_CLAUSE = Regex(
            """(?i)\b(?:in|across|from|within|among|inside|throughout|over)\s+(?:my\s+|the\s+|our\s+)?""" +
                """(tasks?|to-dos?|todos?|notes?|books?|library|pantry|groceries|grocery(?:\s+list)?|""" +
                """memories|memory|profiles?|messages?|chats?|conversations?)\b.*$"""
        )

        val SUBJECT_ASSISTANT = Regex("""(?i)\b(?:have|has|did|do|does)\s+you\b|\byou\s+$SAY_VERB\b""")
        val CONVERSATIONAL_VERB = Regex("""(?i)\b(?:said|say|saying|typed|type|typing|told)\b""")
    }
}
