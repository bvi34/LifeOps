package com.advisor.app.data.repository

import com.advisor.app.data.db.dao.AdvisorDao
import com.advisor.app.data.db.entities.AdvisorMessageEntity
import com.advisor.app.data.db.entities.AppPermissionEntity
import com.advisor.app.data.identity.IdentityStore
import com.advisor.app.data.prompt.SystemPromptStore
import com.advisor.app.data.memory.MemoryRepository
import com.advisor.app.data.memory.TagCount
import android.net.Uri
import com.advisor.app.data.profile.ProfileStore
import com.advisor.app.data.source.KnowledgeSource
import com.advisor.app.llm.AdvisorModelInfo
import com.advisor.app.llm.AdvisorModelStore
import com.advisor.app.llm.EmbeddingModelStore
import com.advisor.app.logic.AdvisorFunction
import com.advisor.app.logic.AdvisorPermissions
import com.advisor.app.logic.AdvisorPrompt
import com.advisor.app.logic.AnswerText
import com.advisor.app.logic.AssistantNaming
import com.advisor.app.logic.Citations
import com.advisor.app.logic.Conversation
import com.advisor.app.logic.ConversationTurn
import com.advisor.app.logic.ConversationWindow
import com.advisor.app.logic.CorpusMerge
import com.advisor.app.logic.EngineDecision
import com.advisor.app.logic.FunctionRequest
import com.advisor.app.logic.FunctionRouter
import com.advisor.app.logic.GroundingResult
import com.advisor.app.logic.Identity
import com.advisor.app.logic.KnowledgeDocument
import com.advisor.app.logic.LocalLlmEngine
import com.advisor.app.logic.LogicEngine
import com.advisor.app.logic.LogicInput
import com.advisor.app.logic.MemoryDirectives
import com.advisor.app.logic.MemoryRecall
import com.advisor.app.logic.MemoryRecord
import com.advisor.app.logic.ModelSpec
import com.advisor.app.logic.Profile
import com.advisor.app.logic.ProfileDirectives
import com.advisor.app.logic.ProfileEntry
import com.advisor.app.logic.ProfileKind
import com.advisor.app.logic.PromptAssembler
import com.advisor.app.logic.PromptBudget
import com.advisor.app.logic.RelevanceDirectives
import com.advisor.app.logic.RelevanceEngine
import com.advisor.app.logic.RetrievedChunk
import com.advisor.app.logic.SmallTalk
import com.advisor.app.logic.HybridRetriever
import com.advisor.app.logic.SourceApp
import com.advisor.app.logic.TaskIntent
import com.advisor.app.logic.TaskWriteResult
import com.advisor.app.logic.TaskWriter
import com.advisor.app.logic.WriteIntent
import com.advisor.app.logic.WriteIntentResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * An answer produced for a question: the text, the app documents and memories it drew on, and which
 * model wrote it.
 */
data class AdvisorAnswer(
    val text: String,
    val citations: List<KnowledgeDocument>,
    val memories: List<MemoryRecord>,
    val model: ModelSpec,
    val decision: EngineDecision = EngineDecision.ANSWER
) {
    /** True when the assistant asked instead of answering (clarify/investigate). */
    val isQuestion: Boolean get() = decision != EngineDecision.ANSWER
}

/**
 * The Advisor's brain-stem: it owns the full pipeline and is the only thing the ViewModel talks to.
 * A question flows through it as:
 *
 *   permissions → load granted sources → retrieve → recall memory → logic engine → prompt → model.
 *
 * The permission gate is enforced *here*, before any app source is read, so a denied app is never
 * even loaded. Identity and long-term memory are Advisor's own and are always available; the logic
 * engine is a seam (a no-op today) that injects derived context between recall and prompt assembly.
 */
class AdvisorRepository(
    private val dao: AdvisorDao,
    private val sources: List<KnowledgeSource>,
    private val engine: LocalLlmEngine,
    private val memory: MemoryRepository,
    private val identityStore: IdentityStore,
    private val systemPromptStore: SystemPromptStore,
    private val profileStore: ProfileStore,
    private val logicEngine: LogicEngine,
    private val modelStore: AdvisorModelStore,
    private val embeddingModelStore: EmbeddingModelStore,
    private val retriever: HybridRetriever = HybridRetriever(),
    private val functions: FunctionRouter = FunctionRouter.DEFAULT,
    /** The one write capability into another app: creating a task the user asked for. */
    private val taskWriter: TaskWriter? = null
) {

    val model: ModelSpec get() = engine.spec

    /** Ground-truth diagnostic of the generation layer — the loaded file, or why it's still placeholder. */
    val modelStatus: String get() = engine.status

    /** What the phone has and what that lets the model do, measured now; null when unmeasured. */
    suspend fun deviceStatus(): String? = withContext(Dispatchers.Default) { engine.describeDevice() }

    /** True when retrieval is running semantically (an embedding model is loaded), not lexically. */
    val semanticRetrieval: Boolean get() = retriever.isSemantic

    // --- on-device model file (in-app import; no network, nothing leaves the device) ---

    // --- the standing system prompt (user-editable) ---

    /** The instruction the model is given before every question — the user's, or the shipped default. */
    suspend fun loadSystemPrompt(): String = systemPromptStore.load()

    /** The shipped default, for the editor's "reset" affordance and its placeholder text. */
    val defaultSystemPrompt: String get() = SystemPromptStore.DEFAULT

    /** True when the user has replaced the default. */
    suspend fun isSystemPromptCustom(): Boolean = systemPromptStore.isCustom()

    /** Save a new standing instruction; blank resets to the shipped default. */
    suspend fun saveSystemPrompt(text: String) = systemPromptStore.save(text)

    /** Discard any customisation and go back to the shipped default. */
    suspend fun resetSystemPrompt() = systemPromptStore.reset()

    /** Status of the installed model file, independent of whether the native runtime is in the build. */
    fun modelInfo(): AdvisorModelInfo = modelStore.info()

    /** Import a user-picked GGUF into app storage, reporting copy progress. */
    suspend fun importModel(uri: Uri, onProgress: (copied: Long, total: Long) -> Unit) =
        modelStore.import(uri, onProgress)

    /** Remove the imported model file. */
    fun deleteModel(): Boolean = modelStore.delete()

    // --- on-device embedding model file (powers semantic retrieval; same no-network promise) ---

    /** Status of the installed embedding model file. */
    fun embeddingModelInfo(): AdvisorModelInfo = embeddingModelStore.info()

    /** Import a user-picked embedding GGUF into app storage, reporting copy progress. */
    suspend fun importEmbeddingModel(uri: Uri, onProgress: (copied: Long, total: Long) -> Unit) =
        embeddingModelStore.import(uri, onProgress)

    /** Remove the imported embedding model file. */
    fun deleteEmbeddingModel(): Boolean = embeddingModelStore.delete()

    // --- permissions ---

    fun observePermissions(): Flow<AdvisorPermissions> =
        dao.observePermissions().map { rows -> rows.toPermissions() }

    suspend fun currentPermissions(): AdvisorPermissions = dao.getPermissions().toPermissions()

    suspend fun setPermission(app: SourceApp, granted: Boolean) {
        dao.setPermission(AppPermissionEntity(app.key, granted, System.currentTimeMillis()))
    }

    // --- identity ---

    suspend fun loadIdentity(): Identity = identityStore.load()

    suspend fun saveIdentity(identity: Identity) = identityStore.save(identity)

    // --- standing profiles (always-on named dossiers; not database-queried) ---

    suspend fun listProfiles(): List<Profile> = profileStore.list()

    suspend fun loadProfile(key: String): Profile? = profileStore.load(key)

    suspend fun appendToProfile(key: String, text: String): Profile =
        profileStore.append(key, text, ProfileEntry.AUTHOR_USER)

    suspend fun createProfile(name: String, kind: ProfileKind, summary: String): Profile =
        profileStore.create(name, kind, summary)

    suspend fun deleteProfile(key: String) = profileStore.delete(key)

    // --- memory ---

    fun observeMemories(): Flow<List<MemoryRecord>> = memory.observeMemories()

    fun observeTagCounts(): Flow<List<TagCount>> = memory.observeTagCounts()

    suspend fun remember(content: String, tags: List<String>, salience: Int, pinned: Boolean): String =
        memory.remember(content = content, tags = tags, salience = salience, pinned = pinned)

    suspend fun forget(id: String) = memory.forget(id)

    suspend fun setMemoryPinned(id: String, pinned: Boolean) = memory.setPinned(id, pinned)

    // --- conversation ---

    fun observeMessages(): Flow<List<AdvisorMessageEntity>> = dao.observeMessages()

    suspend fun clearConversation() = dao.clearMessages()

    /**
     * Answer [question] against the granted apps, the user's identity, and recalled long-term
     * memory. Persists the turn and bumps recall stats on the memories that were surfaced.
     */
    /**
     * Answer [question] over the user's granted data.
     *
     * [onPartial] is optional and, when given, is called with the answer as it forms — the whole of it
     * so far, ready to display. A real 4B model runs for tens of seconds on-device, so this is the
     * difference between watching a spinner and watching a reply; callers that don't care (tests, the
     * placeholder path) simply omit it.
     */
    suspend fun ask(question: String, onPartial: ((String) -> Unit)? = null): AdvisorAnswer {
        val permissions = currentPermissions()
        val identity = loadIdentity()

        // Standing profiles are always-on context — no query, referenced by name.
        val profiles = profileStore.list().filter { it.alwaysInclude }

        // The standing instruction, which the user may have rewritten. Read once per question (the
        // store memoises it) so a save takes effect on the very next answer with no restart.
        val systemPrompt = systemPromptStore.load()

        // Whether a real language model is loaded (not the extractive placeholder). When it is, we let
        // *it* drive the conversation — the pre-model shortcuts below (canned small talk, the C3A
        // "ask instead of guessing" gate) exist because the placeholder can't reason or chat; a real
        // model can, so we hand those turns to it (with all the same grounding) rather than intercepting
        // them. Explicit write commands and computed functions still run first — those are tools, not
        // guesses. This is the "User ⇄ LLM, engine assists" flow.
        //
        // Whether it may run this turn is the phone's call as much as the file's: a hot phone, one
        // saving power, or one without the memory to load the weights gets a smaller answer or the
        // placeholder. Settled once, here, so every check below sees the same answer.
        val budget = withContext(Dispatchers.Default) { engine.admit() }
        val hasModel = !engine.spec.isPlaceholder

        // "add a task to Life Ops called X" is a command to *another app*, and the one thing that
        // must never be improvised: with no way to write, a model simply says it did it (and leaves
        // behind a memory note instead of a task). Perform it for real, or say plainly why not.
        TaskIntent.detect(question)?.let { return applyTaskCommand(question, it, permissions) }

        // An explicit write command ("remember that …", "add to LLM persona that …") is an
        // instruction, not a question — perform it directly and confirm, before the C3A gate that
        // would otherwise ask for clarification about a fact it has no grounding for.
        val intent = WriteIntent.detect(question, profiles)
        if (intent.hasWrites) return applyWriteCommand(question, intent)

        // A request to rename the assistant ("can you call yourself Ava?", "go by Ava now") is a
        // command to persist how it should refer to itself, not a data question — save it to the persona
        // profile and confirm, before retrieval would otherwise treat it as a look-up and dump rows.
        AssistantNaming.detect(question)?.let { newName ->
            return applyAssistantRename(question, newName, profiles)
        }

        // A purely social or meta turn ("hi", "thanks", "what can you do?") isn't a data question. With
        // only the placeholder, reply warmly from what we already hold rather than dead-ending in the
        // grounding gate. With a real model loaded, let it handle the chit-chat itself — it's more
        // natural, and the system prompt already tells it who it is and what it can do.
        if (!hasModel) {
            SmallTalk.detect(
                question,
                SmallTalk.Context(
                    userName = identity.name,
                    assistantName = SmallTalk.assistantNameFrom(profiles),
                    grantedApps = permissions.granted
                )
            )?.let { chat ->
                persistTurn(question, chat.text, emptyList(), KIND_NORMAL)
                return AdvisorAnswer(chat.text, emptyList(), emptyList(), engine.spec, EngineDecision.ANSWER)
            }
        }

        // Load only what the user has granted — denied apps are never read. Sources cache their
        // snapshot between questions (rebuilding when their tables change), so revoking an app must
        // also evict it: otherwise its rows would sit in memory after the user withdrew access.
        val loaded = ArrayList<KnowledgeDocument>()
        for (source in sources) {
            if (permissions.isGranted(source.source)) {
                loaded += runCatching { source.load() }.getOrDefault(emptyList())
            } else {
                source.evict()
            }
        }
        // A book read in Citation is also a row in LifeOps' Collection, and its highlights are
        // synced onto that row's notes. Both sources are right about their own data; the corpus
        // shouldn't carry the same thing twice. See CorpusMerge.
        val corpus = CorpusMerge.merge(loaded)

        // Function dispatch: some questions are computations ("how many times have I said X",
        // "count my word usage in my tasks") that retrieval can't answer — it can only surface rows.
        // If a registered capability handles this, it computes the real answer and we skip the RAG path.
        functions.handler(question)?.let { fn ->
            return runFunction(question, fn, permissions, identity, profiles, corpus)
        }

        // Recent chat history, oldest-first, so retrieval, the C3A gate and the prompt all reason over
        // the conversation — not just this one message. Loaded before the turn is persisted, so it's
        // strictly the prior turns.
        //
        // How far back to read is anchored to the conversation's length rather than being a fixed
        // "last N": the model's KV cache keeps whatever this prompt shares with the previous one, and
        // the history is exactly the shareable part, so a window that slid every turn would throw away
        // the reuse and re-prefill the lot. See ConversationWindow.
        val totalMessages = dao.countMessages()
        val windowStart = ConversationWindow.startIndex(totalMessages)
        val conversation = dao.recentMessages(totalMessages - windowStart)
            .asReversed()
            .map { ConversationTurn(fromUser = it.role == ROLE_USER, text = it.text) }
            .filter { it.text.isNotBlank() }
        // A terse follow-up ("who's its author?") carries its subject in the prior turns — fold them
        // into the query so retrieval and recall find what the follow-up is actually about.
        val retrievalQuery = Conversation.retrievalQuery(conversation, question)

        // Retrieval may be semantic (an embedding model runs off the UI thread) or lexical; the
        // retriever picks. The corpus is already permission-filtered, so revocation is honoured here.
        val retrieved = withContext(Dispatchers.Default) { retriever.retrieve(corpus, retrievalQuery) }

        // Categorical relevance grounding: judge each candidate against what the question actually asks
        // for — object type (a *book*?) and lifecycle state (one I'm *reading* now?) — and keep only what
        // fits, so a pantry item or a to-read book never dumps into "what am I reading?". Facets are
        // inferred from the current question (not the conversation-folded retrieval query), and the
        // baseline is deterministic here — a real model can override any verdict via RelevanceEngine.
        val grounding = RelevanceEngine.assess(question, retrieved)
        val chunks = grounding.grounding

        // Recall long-term memory (always available; not permission-gated).
        val recalled = MemoryRecall.recall(retrievalQuery, memory.allRecords())

        // The C3A unifying engine decides whether to answer, ask for clarification, or flag that a
        // source is needed. justAsked = the previous turn was itself a clarification, so it won't loop.
        val justAsked = dao.lastMessageOf(ROLE_ADVISOR)?.kind == KIND_CLARIFICATION
        val logic = logicEngine.process(
            LogicInput(
                question = question,
                identity = identity,
                retrieved = chunks,
                memories = recalled,
                profiles = profiles,
                grantedApps = permissions.granted,
                deniedApps = SourceApp.entries.toSet() - permissions.granted,
                justAsked = justAsked,
                conversation = conversation
            )
        )

        // "Not knowing is acceptable. Being wrong without asking is not." — when the engine is
        // uncertain it asks the user. This deterministic gate is the placeholder's safety net (it can't
        // reason about thin grounding, so it must not improvise). A real model can: the system prompt
        // already tells it to say so plainly and ask one natural follow-up when nothing fits — so we let
        // it handle uncertainty conversationally instead of returning a canned clarification here.
        if (logic.asksUser && !hasModel) {
            val text = logic.clarification
                ?: "I need a bit more to answer that — could you clarify?"
            persistTurn(question, text, emptyList(), KIND_CLARIFICATION)
            return AdvisorAnswer(text, emptyList(), emptyList(), engine.spec, logic.decision)
        }

        // The grounded set the answer is built from; the refinement round below may narrow it.
        var groundingResult = grounding
        var groundChunks = chunks

        // How much of that history actually fits, measured against the rest of this prompt rather than
        // guessed. What overflows is dropped here, deliberately: the native backend's own overflow
        // handling truncates from the *head*, which is where the system instruction lives, so a long
        // conversation would otherwise cost the model its grounding contract precisely when it needed
        // it. Measured by rendering the prompt without its conversation — the only part whose size
        // this decides.
        //
        // Trimming is stride-aligned like the window itself, and settled once: the refinement pass
        // reuses this same history rather than recomputing a window that a shrinking CONTEXT would let
        // grow, which would move the shared prefix for no gain.
        val promptConversation = ConversationWindow.fit(
            conversation,
            PromptBudget.historyChars(
                groundedPrompt(question, groundChunks, identity, recalled, profiles, logic.derivedContext, groundingResult, emptyList(), systemPrompt)
                    .render(includeConversation = false),
                engine.contextTokens
            )
        )

        // Directives are the app's private bookkeeping, not part of the reply, so what streams out is
        // the answer with them removed — including one still being typed, which the whole-line strip
        // patterns cannot match yet.
        val stream: ((String) -> Unit)? = onPartial?.let { emit ->
            { text: String -> emit(AnswerText.inProgress(text)) }
        }

        // First pass. The prompt carries the grounded context plus the logic notes and the relevance
        // filter's summary, so the model can be honest about near-misses. A real local model runs for
        // seconds and can block on native inference, so keep generation off the UI thread; the
        // placeholder engine is instant, so this is free when no model is loaded.
        var raw = withContext(Dispatchers.Default) {
            generate(question, groundChunks, identity, recalled, profiles, logic.derivedContext, groundingResult, promptConversation, systemPrompt, stream)
        }

        // Model-in-the-loop refinement: the model may flag any shown candidate that doesn't fit with a
        // @relevance(n): <verdict> line. Apply those verdicts over the deterministic baseline
        // (RelevanceEngine.reassess) and drop what no longer fits. Bounded to one round so it always
        // terminates, and inert for the placeholder (which never emits @relevance).
        val votes = RelevanceDirectives.parse(raw)
        if (votes.isNotEmpty()) {
            val refToId = groundChunks.mapIndexed { index, chunk -> (index + 1) to chunk.document.id }.toMap()
            val overrides = votes.mapNotNull { v -> refToId[v.ref]?.let { it to v.category } }.toMap()
            val refined = RelevanceEngine.reassess(groundingResult, overrides)
            if (refined.grounding.map { it.document.id } != groundChunks.map { it.document.id }) {
                val kept = refined.grounding.map { it.document.id }.toSet()
                val dropped = groundChunks.map { it.document.id }.toSet() - kept
                // Whether to answer again is the most expensive decision in this pipeline: a second
                // pass is a second full generation, tens of seconds on-device, and the system prompt
                // asks for these verdicts on *every* turn — so re-running whenever one arrives made
                // the routine cost of a question two generations rather than one.
                //
                // It is only worth paying when the first answer actually leaned on something the model
                // has just disqualified. The `[n]` markers say exactly that: an answer that never cited
                // a dropped candidate did not rest on it, and asking again over a set it already
                // ignored reproduces the same reply for the same cost. The exception is a refinement
                // that leaves *nothing* standing — the answer was grounded in candidates that are all
                // now disqualified, so it has to be made honestly over an empty set.
                val leanedOnDropped =
                    Citations.citedIds(raw, PromptAssembler.blocks(groundChunks)).any { it in dropped }
                val nothingLeftStanding = refined.grounding.isEmpty() && groundChunks.isNotEmpty()

                // A phone that is hot or saving power does not get the second pass, since a second
                // pass is what doubles the cost. The first answer then stands with the grounding
                // it was written against: its `[n]` markers number *that* set, so narrowing the
                // sources under an answer that still cites the dropped ones would point them at the
                // wrong rows. An answer resting on nothing left standing is the exception — it is
                // wrong, not just longer, so it is redone whatever it costs.
                val answerAgain = nothingLeftStanding || (leanedOnDropped && budget.allowRefinement)
                val firstAnswerStandsAsWritten = leanedOnDropped && !answerAgain

                if (!firstAnswerStandsAsWritten) {
                    // Either way the misfits stop being shown as sources: when the answer stands,
                    // nothing in its text points at them — that is exactly why it stands — so
                    // dropping them leaves no dangling reference.
                    groundingResult = refined
                    groundChunks = refined.grounding

                    if (answerAgain) {
                        raw = withContext(Dispatchers.Default) {
                            generate(question, groundChunks, identity, recalled, profiles, logic.derivedContext, groundingResult, promptConversation, systemPrompt, stream)
                        }
                    }
                }
            }
        }

        // Apply any writes the model emitted — @remember(<profile>): … to a standing profile and
        // @memorize: … to long-term memory — then show a clean answer with every directive (writes and
        // relevance votes alike) removed.
        for (append in ProfileDirectives.parse(raw)) {
            profileStore.append(append.profileKey, append.text, ProfileEntry.AUTHOR_ADVISOR)
        }
        for (write in MemoryDirectives.parse(raw)) {
            memory.remember(content = write.content, tags = write.tags, source = SOURCE_ADVISOR)
        }
        val text = AnswerText.finished(raw)

        memory.markRecalled(recalled.map { it.id })
        persistTurn(question, text, groundChunks.map { it.document }, KIND_NORMAL)

        return AdvisorAnswer(text, groundChunks.map { it.document }, recalled, engine.spec, EngineDecision.ANSWER)
    }

    /**
     * Load the model ahead of the first question, so opening the assistant pays for it rather than the
     * first thing the user asks. Off the main thread — this reads gigabytes — and safe to call more
     * than once; a question asked mid-load waits for the same load rather than starting another.
     */
    suspend fun warmUpModel() = withContext(Dispatchers.Default) { engine.warmUp() }

    /** Build the grounded prompt and run it through the engine, streaming when a caller is watching. */
    private fun generate(
        question: String,
        chunks: List<RetrievedChunk>,
        identity: Identity,
        recalled: List<MemoryRecord>,
        profiles: List<Profile>,
        logicNotes: List<String>,
        grounding: GroundingResult,
        conversation: List<ConversationTurn>,
        systemPrompt: String,
        onPartial: ((String) -> Unit)?
    ): String {
        val prompt = groundedPrompt(
            question, chunks, identity, recalled, profiles, logicNotes, grounding, conversation, systemPrompt
        )
        return if (onPartial == null) engine.generate(prompt) else engine.generate(prompt, onPartial)
    }

    /**
     * Assemble the RAG prompt for a grounded answer: the logic engine's notes plus the relevance
     * filter's one-line summary of what it set aside become the model's REASONING context, so the model
     * can explain near-misses rather than pretend the filtered rows never existed.
     */
    private fun groundedPrompt(
        question: String,
        chunks: List<RetrievedChunk>,
        identity: Identity,
        recalled: List<MemoryRecord>,
        profiles: List<Profile>,
        logicNotes: List<String>,
        grounding: GroundingResult,
        conversation: List<ConversationTurn>,
        systemPrompt: String
    ): AdvisorPrompt {
        val derived = logicNotes + listOfNotNull(grounding.filterNote().takeIf { it.isNotBlank() })
        return PromptAssembler.assemble(
            question, chunks, identity, recalled, profiles, derived, conversation, systemPrompt
        )
    }

    /**
     * Run a dispatched [fn] over the full available data and persist its computed answer. A function
     * needs the *whole* stored conversation (not the recent-turns window) so a count like "how many
     * times have I said X" is accurate, so it's loaded here rather than reusing the pipeline window.
     */
    private suspend fun runFunction(
        question: String,
        fn: AdvisorFunction,
        permissions: AdvisorPermissions,
        identity: Identity,
        profiles: List<Profile>,
        corpus: List<KnowledgeDocument>
    ): AdvisorAnswer {
        val fullConversation = dao.recentMessages(Int.MAX_VALUE)
            .asReversed()
            .map { ConversationTurn(fromUser = it.role == ROLE_USER, text = it.text) }
            .filter { it.text.isNotBlank() }
        val result = withContext(Dispatchers.Default) {
            fn.run(
                FunctionRequest(
                    question = question,
                    corpus = corpus,
                    conversation = fullConversation,
                    memories = memory.allRecords(),
                    profiles = profiles,
                    identity = identity,
                    grantedApps = permissions.granted,
                    deniedApps = SourceApp.entries.toSet() - permissions.granted
                )
            )
        }
        persistTurn(question, result.text, result.citations, KIND_NORMAL)
        return AdvisorAnswer(result.text, result.citations, emptyList(), engine.spec, EngineDecision.ANSWER)
    }

    /**
     * Persist a request to rename the assistant to [newName] and confirm it, bypassing retrieval and the
     * model. The name is appended to the standing **persona** profile as a plain instruction, which is
     * where [SmallTalk.assistantNameFrom] reads it — so from the next turn on the assistant introduces
     * itself and signs off as [newName]. Falls back to the seeded [PERSONA_PROFILE_KEY] when no persona
     * profile is in context yet (the store creates it on write).
     */
    private suspend fun applyAssistantRename(
        question: String,
        newName: String,
        profiles: List<Profile>
    ): AdvisorAnswer {
        val personaKey = profiles.firstOrNull { it.kind == ProfileKind.PERSONA }?.key ?: PERSONA_PROFILE_KEY
        profileStore.append(personaKey, "You are called $newName.", ProfileEntry.AUTHOR_ADVISOR)
        val text = "Done — I'll go by $newName from now on."
        persistTurn(question, text, emptyList(), KIND_NORMAL)
        return AdvisorAnswer(text, emptyList(), emptyList(), engine.spec, EngineDecision.ANSWER)
    }

    /**
     * Carry out an explicit "make me a task" command against the owning app and confirm what actually
     * happened — created, skipped as a same-week duplicate, or not written at all.
     *
     * The permission gate applies to writing exactly as it does to reading: an app the user hasn't
     * granted is not written to, and the reply says so instead of failing silently. When the command
     * is clear but unnamed, it asks for the title rather than inventing one — the same "not knowing is
     * acceptable, being wrong without asking is not" rule the C3A gate follows.
     */
    private suspend fun applyTaskCommand(
        question: String,
        intent: TaskIntent.Result,
        permissions: AdvisorPermissions
    ): AdvisorAnswer {
        if (intent is TaskIntent.Result.NeedsTitle) {
            val ask = "I can add that task — what should I call it?"
            persistTurn(question, ask, emptyList(), KIND_CLARIFICATION)
            return AdvisorAnswer(ask, emptyList(), emptyList(), engine.spec, EngineDecision.CLARIFY)
        }
        val command = (intent as TaskIntent.Result.Create).command
        val writer = taskWriter?.takeIf { it.app == command.app }
        val text = when {
            writer == null ->
                "I can't add tasks to ${command.app.displayName} — I can only read from it."
            !permissions.isGranted(command.app) ->
                "I'd need access to ${command.app.displayName} first — turn it on in Permissions and " +
                    "ask me again, and I'll add \"${command.title}\"."
            else -> confirm(writer.create(command))
        }
        persistTurn(question, text, emptyList(), KIND_NORMAL)
        return AdvisorAnswer(text, emptyList(), emptyList(), engine.spec, EngineDecision.ANSWER)
    }

    /** Put a [TaskWriteResult] into words — only ever describing what the app reported back. */
    private fun confirm(result: TaskWriteResult): String = when (result) {
        is TaskWriteResult.Created -> buildString {
            append("Added \"").append(result.title).append("\"")
            result.target?.let { append(" under ").append(it) }
            result.weekLabel?.let { append(" to this week (").append(it).append(')') }
            append('.')
            result.unmatchedTarget?.let {
                append(" I couldn't find \"").append(it)
                append("\" in there, so it isn't filed under anything — tell me where it belongs and ")
                append("you can move it.")
            }
        }
        is TaskWriteResult.Duplicate ->
            "\"${result.title}\" is already on this week's list, so I left the existing one alone."
        is TaskWriteResult.Failed -> "I couldn't add that task: ${result.reason}"
    }

    /**
     * Perform an explicit user write command and confirm it, bypassing retrieval and the model — a
     * "remember this" / "add to <profile>" turn is an instruction to persist, not a question to answer.
     * Profile writes are tagged as assistant-authored (the assistant made the write on request) and
     * memory writes carry the [SOURCE_ADVISOR] source, mirroring the model-directive write paths.
     */
    private suspend fun applyWriteCommand(question: String, intent: WriteIntentResult): AdvisorAnswer {
        val lines = ArrayList<String>()
        for (append in intent.profileAppends) {
            val profile = profileStore.append(append.profileKey, append.text, ProfileEntry.AUTHOR_ADVISOR)
            lines += "Saved to your ${profile.name} profile:\n• ${append.text}"
        }
        for (removal in intent.profileRemovals) {
            val (profile, removed) = profileStore.removeMatchingEntry(removal.profileKey, removal.text)
            val profileName = profile?.name ?: removal.profileKey
            lines += if (removed > 0) {
                "Removed from your $profileName profile:\n• ${removal.text}"
            } else {
                "I couldn't find that exact line in your $profileName profile:\n• ${removal.text}"
            }
        }
        for (write in intent.memoryWrites) {
            memory.remember(content = write.content, tags = write.tags, source = SOURCE_ADVISOR)
            val tagNote = if (write.tags.isEmpty()) "" else " (tags: ${write.tags.joinToString(", ")})"
            lines += "Saved to long-term memory:\n• ${write.content}$tagNote"
        }
        val text = lines.joinToString("\n\n")
        persistTurn(question, text, emptyList(), KIND_NORMAL)
        return AdvisorAnswer(text, emptyList(), emptyList(), engine.spec, EngineDecision.ANSWER)
    }

    private suspend fun persistTurn(
        question: String,
        answer: String,
        citations: List<KnowledgeDocument>,
        advisorKind: String
    ) {
        val now = System.currentTimeMillis()
        dao.addMessage(
            AdvisorMessageEntity(
                id = "user-$now",
                role = ROLE_USER,
                text = question.trim(),
                citationIds = "",
                createdAt = now,
                kind = KIND_NORMAL
            )
        )
        dao.addMessage(
            AdvisorMessageEntity(
                id = "advisor-$now",
                role = ROLE_ADVISOR,
                text = answer,
                citationIds = citations.joinToString("\n") { it.id },
                createdAt = now + 1,
                kind = advisorKind
            )
        )
    }

    private fun List<AppPermissionEntity>.toPermissions(): AdvisorPermissions {
        val granted = filter { it.granted }
            .mapNotNull { SourceApp.fromKey(it.appKey) }
            .toSet()
        return AdvisorPermissions(granted)
    }

    companion object {
        const val ROLE_USER = "user"
        const val ROLE_ADVISOR = "advisor"
        const val KIND_NORMAL = "normal"
        const val KIND_CLARIFICATION = "clarification"
        /** Marks a memory the assistant wrote (via a user command or a @memorize directive). */
        const val SOURCE_ADVISOR = "advisor"
        /** The seeded persona profile the assistant's name is stored in (see [ProfileStore]). */
        const val PERSONA_PROFILE_KEY = "llm-persona"
    }
}
