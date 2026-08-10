package com.advisor.app.data.repository

import com.advisor.app.data.db.dao.AdvisorDao
import com.advisor.app.data.db.entities.AdvisorMessageEntity
import com.advisor.app.data.db.entities.AppPermissionEntity
import com.advisor.app.data.identity.IdentityStore
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
import com.advisor.app.logic.Conversation
import com.advisor.app.logic.ConversationTurn
import com.advisor.app.logic.EngineDecision
import com.advisor.app.logic.FunctionRequest
import com.advisor.app.logic.FunctionRouter
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
import com.advisor.app.logic.SmallTalk
import com.advisor.app.logic.HybridRetriever
import com.advisor.app.logic.SourceApp
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
    private val profileStore: ProfileStore,
    private val logicEngine: LogicEngine,
    private val modelStore: AdvisorModelStore,
    private val embeddingModelStore: EmbeddingModelStore,
    private val retriever: HybridRetriever = HybridRetriever(),
    private val functions: FunctionRouter = FunctionRouter.DEFAULT
) {

    val model: ModelSpec get() = engine.spec

    /** True when retrieval is running semantically (an embedding model is loaded), not lexically. */
    val semanticRetrieval: Boolean get() = retriever.isSemantic

    // --- on-device model file (in-app import; no network, nothing leaves the device) ---

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
    suspend fun ask(question: String): AdvisorAnswer {
        val permissions = currentPermissions()
        val identity = loadIdentity()

        // Standing profiles are always-on context — no query, referenced by name.
        val profiles = profileStore.list().filter { it.alwaysInclude }

        // An explicit write command ("remember that …", "add to LLM persona that …") is an
        // instruction, not a question — perform it directly and confirm, before the C3A gate that
        // would otherwise ask for clarification about a fact it has no grounding for.
        val intent = WriteIntent.detect(question, profiles)
        if (intent.hasWrites) return applyWriteCommand(question, intent)

        // A purely social or meta turn ("hi", "thanks", "what can you do?") isn't a data question —
        // reply warmly and immediately from what we already hold (the user's name, the enabled apps)
        // rather than dead-ending in the grounding gate. Grounded questions, even ones that open with
        // "hi, …", carry real content words and so fall straight through to the retrieval pipeline.
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

        // Load only what the user has granted — denied apps are never read.
        val corpus = ArrayList<KnowledgeDocument>()
        for (source in sources) {
            if (permissions.isGranted(source.source)) {
                corpus += runCatching { source.load() }.getOrDefault(emptyList())
            }
        }

        // Function dispatch: some questions are computations ("how many times have I said X",
        // "count my word usage in my tasks") that retrieval can't answer — it can only surface rows.
        // If a registered capability handles this, it computes the real answer and we skip the RAG path.
        functions.handler(question)?.let { fn ->
            return runFunction(question, fn, permissions, identity, profiles, corpus)
        }

        // Recent chat history, oldest-first, so retrieval, the C3A gate and the prompt all reason over
        // the conversation — not just this one message. Loaded before the turn is persisted, so it's
        // strictly the prior turns.
        val conversation = dao.recentMessages(HISTORY_TURNS)
            .asReversed()
            .map { ConversationTurn(fromUser = it.role == ROLE_USER, text = it.text) }
            .filter { it.text.isNotBlank() }
        // A terse follow-up ("who's its author?") carries its subject in the prior turns — fold them
        // into the query so retrieval and recall find what the follow-up is actually about.
        val retrievalQuery = Conversation.retrievalQuery(conversation, question)

        // Retrieval may be semantic (an embedding model runs off the UI thread) or lexical; the
        // retriever picks. The corpus is already permission-filtered, so revocation is honoured here.
        val chunks = withContext(Dispatchers.Default) { retriever.retrieve(corpus, retrievalQuery) }

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
        // uncertain it asks the user, and the model is not invoked to resolve it internally.
        if (logic.asksUser) {
            val text = logic.clarification
                ?: "I need a bit more to answer that — could you clarify?"
            persistTurn(question, text, emptyList(), KIND_CLARIFICATION)
            return AdvisorAnswer(text, emptyList(), emptyList(), engine.spec, logic.decision)
        }

        val prompt = PromptAssembler.assemble(
            question, chunks, identity, recalled, profiles, logic.derivedContext, conversation
        )
        // A real local model runs for seconds and can block on native inference — keep it off the UI
        // thread. The placeholder engine is instant, so this is free when the model isn't loaded.
        val raw = withContext(Dispatchers.Default) { engine.generate(prompt) }

        // Apply any writes the model emitted — @remember(<profile>): … to a standing profile and
        // @memorize: … to long-term memory — then show a clean answer with the directives removed.
        for (append in ProfileDirectives.parse(raw)) {
            profileStore.append(append.profileKey, append.text, ProfileEntry.AUTHOR_ADVISOR)
        }
        for (write in MemoryDirectives.parse(raw)) {
            memory.remember(content = write.content, tags = write.tags, source = SOURCE_ADVISOR)
        }
        val text = MemoryDirectives.strip(ProfileDirectives.strip(raw))

        memory.markRecalled(recalled.map { it.id })
        persistTurn(question, text, chunks.map { it.document }, KIND_NORMAL)

        return AdvisorAnswer(text, chunks.map { it.document }, recalled, engine.spec, EngineDecision.ANSWER)
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
        /** How many recent messages feed the conversation context (≈ the last handful of exchanges). */
        const val HISTORY_TURNS = 8
    }
}
