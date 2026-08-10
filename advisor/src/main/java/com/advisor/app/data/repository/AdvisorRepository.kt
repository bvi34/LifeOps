package com.advisor.app.data.repository

import com.advisor.app.data.db.dao.AdvisorDao
import com.advisor.app.data.db.entities.AdvisorMessageEntity
import com.advisor.app.data.db.entities.AppPermissionEntity
import com.advisor.app.data.source.KnowledgeSource
import com.advisor.app.logic.AdvisorPermissions
import com.advisor.app.logic.KnowledgeDocument
import com.advisor.app.logic.LocalLlmEngine
import com.advisor.app.logic.ModelSpec
import com.advisor.app.logic.PromptAssembler
import com.advisor.app.logic.Retriever
import com.advisor.app.logic.SourceApp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** An answer produced for a question: the text, the documents it cited, and which model wrote it. */
data class AdvisorAnswer(
    val text: String,
    val citations: List<KnowledgeDocument>,
    val model: ModelSpec
)

/**
 * The Advisor's brain-stem: it owns the RAG pipeline and the permission gate, and is the only thing
 * the ViewModel talks to. A question flows through it as:
 *
 *   permissions → load only granted sources → retrieve top chunks → assemble prompt → run model.
 *
 * The permission gate is enforced *here*, before any source is read, so an app the user hasn't
 * granted is never even loaded — retrieval physically cannot see it.
 */
class AdvisorRepository(
    private val dao: AdvisorDao,
    private val sources: List<KnowledgeSource>,
    private val engine: LocalLlmEngine
) {

    val model: ModelSpec get() = engine.spec

    // --- permissions ---

    fun observePermissions(): Flow<AdvisorPermissions> =
        dao.observePermissions().map { rows -> rows.toPermissions() }

    suspend fun currentPermissions(): AdvisorPermissions = dao.getPermissions().toPermissions()

    suspend fun setPermission(app: SourceApp, granted: Boolean) {
        dao.setPermission(
            AppPermissionEntity(app.key, granted, System.currentTimeMillis())
        )
    }

    // --- conversation ---

    fun observeMessages(): Flow<List<AdvisorMessageEntity>> = dao.observeMessages()

    suspend fun clearConversation() = dao.clearMessages()

    /**
     * Answer [question] against the currently-granted apps. Persists the question and answer as a
     * conversation turn so history survives process death and is included in backup.
     */
    suspend fun ask(question: String): AdvisorAnswer {
        val permissions = currentPermissions()

        // Load only what the user has granted — denied apps are never read.
        val corpus = ArrayList<KnowledgeDocument>()
        for (source in sources) {
            if (permissions.isGranted(source.source)) {
                corpus += runCatching { source.load() }.getOrDefault(emptyList())
            }
        }

        val chunks = Retriever(corpus).retrieve(question)
        val prompt = PromptAssembler.assemble(question, chunks)
        val text = engine.generate(prompt)
        val citations = chunks.map { it.document }

        persistTurn(question, text, citations)
        return AdvisorAnswer(text, citations, engine.spec)
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
