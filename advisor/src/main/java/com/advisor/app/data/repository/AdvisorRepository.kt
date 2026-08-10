package com.advisor.app.data.repository

import com.advisor.app.data.db.dao.AdvisorDao
import com.advisor.app.data.db.entities.AdvisorMessageEntity
import com.advisor.app.data.db.entities.AppPermissionEntity
import com.advisor.app.data.identity.IdentityStore
import com.advisor.app.data.memory.MemoryRepository
import com.advisor.app.data.memory.TagCount
import com.advisor.app.data.source.KnowledgeSource
import com.advisor.app.logic.AdvisorPermissions
import com.advisor.app.logic.Identity
import com.advisor.app.logic.KnowledgeDocument
import com.advisor.app.logic.LocalLlmEngine
import com.advisor.app.logic.LogicEngine
import com.advisor.app.logic.LogicInput
import com.advisor.app.logic.MemoryRecall
import com.advisor.app.logic.MemoryRecord
import com.advisor.app.logic.ModelSpec
import com.advisor.app.logic.PromptAssembler
import com.advisor.app.logic.Retriever
import com.advisor.app.logic.SourceApp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * An answer produced for a question: the text, the app documents and memories it drew on, and which
 * model wrote it.
 */
data class AdvisorAnswer(
    val text: String,
    val citations: List<KnowledgeDocument>,
    val memories: List<MemoryRecord>,
    val model: ModelSpec
)

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
    private val logicEngine: LogicEngine
) {

    val model: ModelSpec get() = engine.spec

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

        // Load only what the user has granted — denied apps are never read.
        val corpus = ArrayList<KnowledgeDocument>()
        for (source in sources) {
            if (permissions.isGranted(source.source)) {
                corpus += runCatching { source.load() }.getOrDefault(emptyList())
            }
        }
        val chunks = Retriever(corpus).retrieve(question)

        // Recall long-term memory (always available; not permission-gated).
        val recalled = MemoryRecall.recall(question, memory.allRecords())

        // Let the logic engine derive extra context (no-op until the real engine ships).
        val logic = logicEngine.process(LogicInput(question, identity, chunks, recalled))

        val prompt = PromptAssembler.assemble(question, chunks, identity, recalled, logic.derivedContext)
        val text = engine.generate(prompt)

        memory.markRecalled(recalled.map { it.id })
        persistTurn(question, text, chunks.map { it.document })

        return AdvisorAnswer(text, chunks.map { it.document }, recalled, engine.spec)
    }

    private suspend fun persistTurn(question: String, answer: String, citations: List<KnowledgeDocument>) {
        val now = System.currentTimeMillis()
        dao.addMessage(
            AdvisorMessageEntity(
                id = "user-$now",
                role = ROLE_USER,
                text = question.trim(),
                citationIds = "",
                createdAt = now
            )
        )
        dao.addMessage(
            AdvisorMessageEntity(
                id = "advisor-$now",
                role = ROLE_ADVISOR,
                text = answer,
                citationIds = citations.joinToString("\n") { it.id },
                createdAt = now + 1
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
    }
}
