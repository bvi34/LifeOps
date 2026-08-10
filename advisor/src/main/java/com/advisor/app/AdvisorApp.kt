package com.advisor.app

import android.app.Application
import android.content.Context
import com.advisor.app.data.db.AdvisorDatabase
import com.advisor.app.data.identity.IdentityStore
import com.advisor.app.data.memory.AdvisorMemoryDatabase
import com.advisor.app.data.memory.MemoryRepository
import com.advisor.app.data.profile.ProfileStore
import com.advisor.app.data.repository.AdvisorRepository
import com.advisor.app.data.source.CitationKnowledgeSource
import com.advisor.app.data.source.KnowledgeSource
import com.advisor.app.data.source.LifeOpsKnowledgeSource
import com.advisor.app.data.source.LogisticsKnowledgeSource
import com.advisor.app.logic.C3AEngine
import com.advisor.app.logic.LogicEngine
import com.advisor.app.logic.PlaceholderLlmEngine

/**
 * Advisor's tiny runtime container, mirroring LifeOps/Citation/Logistics: the hosting Operations
 * Sandbox [Application] calls [install] once, and the (single) activity resolves it with [get]. It
 * owns Advisor's own database, the read-only knowledge sources into the other apps, and the
 * repository that runs the RAG pipeline. Everything is lazy, so bringing Advisor up is essentially
 * free until its screen is opened — and no other app's database is touched until a question is asked
 * (and only for the apps the user has granted).
 */
class AdvisorApp private constructor(private val app: Application) {

    val database by lazy { AdvisorDatabase.getInstance(app) }

    /** The dedicated, heavily-tagged long-term memory store (its own database). */
    val memoryDatabase by lazy { AdvisorMemoryDatabase.getInstance(app) }
    val memoryRepository by lazy { MemoryRepository(memoryDatabase.memoryDao()) }

    /** Identity-based data, persisted as portable JSON rather than in the database. */
    val identityStore by lazy { IdentityStore(app) }

    /** Standing, always-on named profiles (user, LLM persona, projects), persisted as JSON. */
    val profileStore by lazy { ProfileStore(app) }

    /** The read-only bridges into every hosted app's data. Loaded only when granted. */
    val knowledgeSources: List<KnowledgeSource> by lazy {
        listOf(
            LifeOpsKnowledgeSource(app),
            CitationKnowledgeSource(app),
            LogisticsKnowledgeSource(app)
        )
    }

    /** The language model. A deterministic placeholder today; a local 2–4B GGUF model is the target. */
    val engine by lazy { PlaceholderLlmEngine() }

    /** The C3A unifying engine: coordinates the components and decides answer / clarify / investigate. */
    val logicEngine: LogicEngine by lazy { C3AEngine() }

    val repository by lazy {
        AdvisorRepository(
            dao = database.advisorDao(),
            sources = knowledgeSources,
            engine = engine,
            memory = memoryRepository,
            identityStore = identityStore,
            profileStore = profileStore,
            logicEngine = logicEngine
        )
    }

    companion object {
        @Volatile
        private var instance: AdvisorApp? = null

        fun install(app: Application): AdvisorApp =
            instance ?: synchronized(this) {
                instance ?: AdvisorApp(app).also { instance = it }
            }

        fun get(context: Context): AdvisorApp =
            instance ?: synchronized(this) {
                // Be forgiving: if the host forgot to install, build from the app context rather than
                // crash the screen (the sources only need a context).
                instance ?: AdvisorApp(context.applicationContext as Application).also { instance = it }
            }
    }
}
