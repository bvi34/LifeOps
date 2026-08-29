package com.advisor.app.logic

/**
 * **C3A — the unifying engine.** It coordinates the other components (the model, the databases, and
 * the JSON stores) into one reasoning workflow and decides *what to do* with a question rather than
 * blindly answering it. Its whole reason for existing is one rule:
 *
 * > **"Not knowing is acceptable. Being wrong without asking clarification is not."**
 *
 * So it runs a small, deterministic workflow over the already-assembled context and returns a
 * decision:
 *
 *  1. **Contradiction detection** — if recalled memory or profile entries conflict, it does not pick
 *     one: it asks which is correct ([EngineDecision.CLARIFY]).
 *  2. **Ambiguity** — an unresolved reference ("it", "that", "the operation") with nothing to bind it
 *     to is a question the engine won't guess the subject of; it asks.
 *  3. **Grounding / uncertainty** — if nothing on hand (identity, profiles, memory, granted app data)
 *     matches the question, it does not let the model improvise. If the topic clearly needs a source
 *     that isn't enabled, it says so ([EngineDecision.INVESTIGATE]); otherwise it asks for more
 *     ([EngineDecision.CLARIFY]).
 *  4. **Answer** — only when there is real grounding does it hand off to the model, with a short note
 *     of what it's grounded in.
 *
 * The one concession to usability is [LogicInput.justAsked]: if the engine already asked on the
 * previous turn, it does not ask again for the same thin-grounding/ambiguity reasons — it asked once,
 * so it proceeds. Contradictions still stop it, because answering from conflicting data is exactly
 * the "being wrong" the rule forbids.
 *
 * The reasoning is heuristic and pure (JVM-testable) — a stand-in for what a real model could later
 * do more richly. The policy, though, is the real contract, and it does not change when the model does.
 */
class C3AEngine(
    private val contradictionJaccard: Double = 0.5
) : LogicEngine {

    override fun process(input: LogicInput): LogicOutput {
        val question = input.question.trim()
        val queryTerms = QueryUnderstanding.expandTerms(Retriever.tokenize(question))
        // Social words (hello, thanks, …) and polite filler aren't something to be uncertain about.
        val contentTerms = queryTerms - GREETING_WORDS
        val rawTokens = QueryUnderstanding.normalize(question).split(NON_WORD).filter { it.isNotBlank() }
        val deicticRef = deicticReference(question, rawTokens)

        // Once the chat is underway, a bare reference or a thin question has somewhere to bind — the
        // prior turns. So (like justAsked) we don't stop to clarify what the conversation already
        // establishes; we hand the history to the model and let it resolve the follow-up.
        val leanOnContext = input.justAsked || Conversation.hasContext(input.conversation)

        // 1. Contradictions always stop the engine — it must not answer from conflicting facts.
        val contradictions = detectContradictions(input)
        if (contradictions.isNotEmpty()) {
            val c = contradictions.first()
            return LogicOutput(
                decision = EngineDecision.CLARIFY,
                clarification = "I found two things on record that conflict:\n" +
                    "• ${c.statementA}\n• ${c.statementB}\n" +
                    "Which should I treat as correct? I'd rather ask than answer from the wrong one.",
                contradictions = contradictions,
                rationale = "Sources conflict; answering from either could be wrong."
            )
        }

        // No real content (a greeting/aside) — answer, unless it leans on an unresolved reference with
        // no conversation to bind it to.
        if (contentTerms.isEmpty()) {
            return if (deicticRef != null && !leanOnContext) clarifyAmbiguity(deicticRef)
            else answer(input, overlap = 0)
        }

        val overlap = groundingOverlap(contentTerms, input)
        if (overlap > 0) {
            // Grounded enough to hand to the model.
            return answer(input, overlap)
        }

        // An identity question ("who am I") shares no literal terms with a "Name: …" fact, so lexical
        // overlap is 0 — but if we hold identity or a user profile, that IS the grounding. Answer from
        // it rather than clarifying about something we plainly have on record.
        if (IdentityQuestions.isAboutUser(question) && hasUserContext(input)) {
            return answer(input, overlap, extraNote = "Answering from your identity and standing profiles.")
        }

        // Ungrounded from here down.
        if (leanOnContext) {
            // Either we already asked last turn, or the conversation is underway — honour the rule
            // (we don't re-interrogate what context already frames) and proceed with a caveat.
            val why = if (input.justAsked) "a clarification was already requested"
            else "the recent conversation gives context"
            return answer(input, overlap, extraNote = "Grounding is thin, but $why — proceeding.")
        }

        if (deicticRef != null) return clarifyAmbiguity(deicticRef)

        val neededApp = mapToDeniedApp(contentTerms, input.deniedApps)
        if (neededApp != null) {
            return LogicOutput(
                decision = EngineDecision.INVESTIGATE,
                clarification = "This looks like it needs your ${neededApp.displayName} data, which " +
                    "isn't enabled right now. Want me to use it? Turn ${neededApp.displayName} on in " +
                    "Permissions and ask again — I won't guess from data I can't see.",
                uncertainties = listOf(
                    Uncertainty(question, "Relevant data likely lives in ${neededApp.displayName}, which is not granted.")
                ),
                rationale = "The answer needs a source that isn't available yet."
            )
        }

        return LogicOutput(
            decision = EngineDecision.CLARIFY,
            clarification = "I don't have anything in your identity, profiles, memory, or enabled " +
                "apps that answers that — and I'd rather not guess. Can you tell me a bit more, or " +
                "point me to where this lives?",
            uncertainties = listOf(
                Uncertainty(question, "No matching information in identity, profiles, memory, or granted app data.")
            ),
            rationale = "Nothing on hand answers this; asking rather than guessing."
        )
    }

    // --- ANSWER ---

    private fun answer(input: LogicInput, overlap: Int, extraNote: String? = null): LogicOutput {
        val notes = ArrayList<String>()
        notes += "Grounding sufficient: matched $overlap question term(s) across " +
            "${input.retrieved.size} app source(s) and ${input.memories.size} memory(ies)."
        if (input.profiles.isNotEmpty()) {
            notes += "Standing profiles in context: ${input.profiles.joinToString(", ") { it.name }}."
        }
        if (input.conversation.isNotEmpty()) {
            notes += "Considering ${input.conversation.size} recent conversation turn(s) for context."
        }
        if (extraNote != null) notes += extraNote
        return LogicOutput(derivedContext = notes, decision = EngineDecision.ANSWER)
    }

    private fun clarifyAmbiguity(reference: String): LogicOutput = LogicOutput(
        decision = EngineDecision.CLARIFY,
        clarification = "Before I answer — when you say \"$reference\", which one do you mean? " +
            "I'd rather check than assume the wrong thing.",
        uncertainties = listOf(Uncertainty(reference, "Unresolved reference with no clear antecedent.")),
        rationale = "Ambiguous reference; asking avoids answering about the wrong subject."
    )

    /** Do we hold identity or a user profile with entries — enough to answer a question about the user? */
    private fun hasUserContext(input: LogicInput): Boolean =
        !input.identity.isEmpty ||
            input.profiles.any { it.kind == ProfileKind.USER && it.entries.isNotEmpty() }

    // --- grounding ---

    /** How many of the question's terms appear anywhere in the assembled context. */
    private fun groundingOverlap(queryTerms: Set<String>, input: LogicInput): Int {
        val evidence = HashSet<String>()
        for (chunk in input.retrieved) {
            evidence += Retriever.tokenize(chunk.document.title)
            evidence += Retriever.tokenize(chunk.document.body)
        }
        for (memory in input.memories) evidence += Retriever.tokenize(memory.searchableText())
        for (profile in input.profiles) {
            evidence += Retriever.tokenize(profile.name)
            evidence += Retriever.tokenize(profile.summary)
            for (entry in profile.entries) evidence += Retriever.tokenize(entry.text)
        }
        for (line in input.identity.toContextLines()) evidence += Retriever.tokenize(line)
        // Recent turns are grounding too — a follow-up's subject usually lives in what was just said.
        for (turn in input.conversation) evidence += Retriever.tokenize(turn.text)
        return queryTerms.count { it in evidence }
    }

    // --- ambiguity ---

    private fun deicticReference(question: String, rawTokens: List<String>): String? {
        val lower = question.lowercase()
        DEICTIC_PHRASES.firstOrNull { lower.contains(it) }?.let { return it }
        return rawTokens.firstOrNull { it in DEICTIC_WORDS }
    }

    // --- contradiction detection ---

    private fun detectContradictions(input: LogicInput): List<Contradiction> {
        val statements = ArrayList<String>()
        for (memory in input.memories) statements += memory.content
        for (profile in input.profiles) for (entry in profile.entries) statements += entry.text
        val capped = statements.map { it.trim() }.filter { it.isNotBlank() }.take(MAX_STATEMENTS)

        val bases = capped.map { Retriever.tokenize(it).toSet() - NEGATION_TOKENS }
        val negated = capped.map { hasNegation(it) }

        val found = ArrayList<Contradiction>()
        for (i in capped.indices) {
            for (j in i + 1 until capped.size) {
                if (negated[i] == negated[j]) continue // need opposite polarity
                if (bases[i].size < MIN_BASE || bases[j].size < MIN_BASE) continue
                if (jaccard(bases[i], bases[j]) >= contradictionJaccard) {
                    found += Contradiction(capped[i], capped[j], "Same subject, opposite polarity.")
                }
            }
        }
        return found
    }

    private fun hasNegation(text: String): Boolean {
        val lower = text.lowercase()
        if (lower.contains("n't")) return true
        return NEGATION_WORDS.any { Regex("\\b" + Regex.escape(it) + "\\b").containsMatchIn(lower) }
    }

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() && b.isEmpty()) return 0.0
        val intersection = a.count { it in b }.toDouble()
        val union = (a.size + b.size - intersection)
        return if (union == 0.0) 0.0 else intersection / union
    }

    // --- question → app mapping (for INVESTIGATE) ---

    private fun mapToDeniedApp(queryTerms: Set<String>, denied: Set<SourceApp>): SourceApp? =
        denied.firstOrNull { app -> APP_KEYWORDS[app]?.any { it in queryTerms } == true }

    private companion object {
        val NON_WORD = Regex("[^a-z0-9]+")
        const val MAX_STATEMENTS = 60
        const val MIN_BASE = 2

        val DEICTIC_WORDS = setOf("it", "that", "this", "these", "those", "they", "them", "he", "she", "him", "her")
        val DEICTIC_PHRASES = listOf("the operation", "the project", "the task", "the book", "the file", "the thing")

        // Social openers/closers — present but not something to be uncertain about.
        val GREETING_WORDS = setOf(
            "hello", "hi", "hey", "heya", "hiya", "yo", "sup", "howdy", "greetings",
            "thanks", "thank", "thankyou", "thx", "cheers", "bye", "goodbye"
        )

        val NEGATION_WORDS = setOf("not", "never", "cannot", "no", "without", "avoid")
        val NEGATION_TOKENS = setOf("not", "never", "cannot", "no", "without", "avoid", "dont", "doesnt", "isnt", "wont")

        val APP_KEYWORDS: Map<SourceApp, Set<String>> = mapOf(
            SourceApp.LIFEOPS to setOf("task", "tasks", "week", "aspect", "aspects", "operation", "operations", "project", "projects", "milestone", "milestones", "goal", "goals", "priority", "deadline"),
            SourceApp.CITATION to setOf("book", "books", "read", "reading", "note", "notes", "author", "library", "highlight", "highlights"),
            SourceApp.LOGISTICS to setOf("pantry", "grocery", "groceries", "food", "stock", "ingredient", "ingredients", "recipe", "recipes", "meal", "meals"),
            SourceApp.HEALTH to setOf("temperature", "temperatures", "fever", "temp", "sick", "ill", "illness", "symptom", "symptoms", "medicine", "medicines", "dose", "doses", "medication", "paracetamol", "ibuprofen", "cough", "poorly", "doctor", "cabinet", "expired", "expiry", "pharmacy"),
            SourceApp.PEOPLE to setOf("who", "person", "people", "birthday", "birthdays", "anniversary", "household", "family", "contact", "email", "phone", "age")
        )
    }
}
