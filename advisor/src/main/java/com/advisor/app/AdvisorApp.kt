package com.advisor.app

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.util.Log
import androidx.room.RoomDatabase
import com.advisor.app.data.action.LifeOpsTaskWriter
import com.advisor.app.data.db.AdvisorDatabase
import com.advisor.app.data.identity.IdentityStore
import com.advisor.app.data.memory.AdvisorMemoryDatabase
import com.advisor.app.data.memory.MemoryRepository
import com.advisor.app.data.profile.ProfileStore
import com.advisor.app.data.prompt.SystemPromptStore
import com.advisor.app.data.repository.AdvisorRepository
import com.advisor.app.data.source.CachingKnowledgeSource
import com.advisor.app.data.source.CitationKnowledgeSource
import com.advisor.app.data.source.HealthKnowledgeSource
import com.advisor.app.data.source.KnowledgeSource
import com.advisor.app.data.source.LifeOpsKnowledgeSource
import com.advisor.app.data.source.LogisticsKnowledgeSource
import com.advisor.app.data.source.MaintenanceKnowledgeSource
import com.advisor.app.data.source.PeopleKnowledgeSource
import com.advisor.app.data.source.ProjectKnowledgeSource
import com.advisor.app.data.source.RepositoryKnowledgeSource
import com.advisor.app.data.source.RoomChangeFeed
import com.advisor.app.device.DeviceMonitor
import com.advisor.app.llm.AdvisorModelStore
import com.advisor.app.llm.EmbeddingModelStore
import com.advisor.app.llm.LlamaCppBackend
import com.advisor.app.llm.LlamaCppEmbedder
import com.advisor.app.logic.C3AEngine
import com.advisor.app.logic.HybridRetriever
import com.advisor.app.logic.LogicEngine
import com.advisor.app.logic.Qwen3LlmEngine
import com.advisor.app.logic.TaskWriter
import com.advisor.app.logic.VectorCache
import com.citation.app.data.db.CitationDatabase
import com.health.app.data.db.HealthDatabase
import com.lifeops.app.data.db.LifeOpsDatabase
import com.logistics.app.data.db.LogisticsDatabase
import com.maintenance.app.data.db.MaintenanceDatabase
import com.people.app.data.db.PeopleDatabase
import com.project.app.data.db.ProjectDatabase
import com.repository.app.data.db.RepositoryDatabase
import java.io.File

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

    /** The user-editable standing instruction the model is given before every question. */
    val systemPromptStore by lazy { SystemPromptStore(app) }

    /**
     * The read-only bridges into every hosted app's data. Loaded only when granted.
     *
     * Each is wrapped in a [CachingKnowledgeSource] so a question does not re-read whole tables and
     * rebuild a document per row when nothing has been written since the last one. The table lists
     * below are what each source actually reads — keep them in step with the source, since a table
     * missing here means a stale snapshot until something else in the same database changes. A name
     * that does not exist is caught at subscribe time and simply disables that source's cache.
     */
    val knowledgeSources: List<KnowledgeSource> by lazy {
        listOf(
            LifeOpsKnowledgeSource(app).cachedOn("aspects", "tasks", "operations", "milestones") {
                LifeOpsDatabase.getInstance(app)
            },
            CitationKnowledgeSource(app).cachedOn("books", "notes") {
                CitationDatabase.get(app)
            },
            LogisticsKnowledgeSource(app).cachedOn("pantry_items", "grocery_items") {
                LogisticsDatabase.getInstance(app)
            },
            HealthKnowledgeSource(app).cachedOn(
                "profiles", "readings", "symptoms", "medications", "doses", "episodes",
                "care_notes", "cabinet_items"
            ) {
                HealthDatabase.getInstance(app)
            },
            PeopleKnowledgeSource(app).cachedOn(
                "people", "person_notes", "important_dates",
                "check_in_fields", "check_ins", "check_in_answers",
                "partner_links", "partner_week_tasks", "partner_outbox"
            ) {
                PeopleDatabase.getInstance(app)
            },
            ProjectKnowledgeSource(app).cachedOn(
                "projects", "outline_nodes", "docs", "doc_blocks", "lore_entries",
                "timeline_events", "board_columns", "board_cards"
            ) {
                ProjectDatabase.getInstance(app)
            },
            MaintenanceKnowledgeSource(app).cachedOn(
                "assets", "asset_attributes", "upkeep_plans", "service_records", "meter_readings",
                "loans", "coverages", "recalls"
            ) {
                MaintenanceDatabase.getInstance(app)
            },
            RepositoryKnowledgeSource(app).cachedOn("documents") {
                RepositoryDatabase.getInstance(app)
            }
        )
    }

    private fun KnowledgeSource.cachedOn(
        vararg tables: String,
        database: () -> RoomDatabase
    ): KnowledgeSource = CachingKnowledgeSource(this, RoomChangeFeed(arrayOf(*tables), database))

    /** Owns the on-device model file: in-app import of a Qwen3-4B GGUF, its status, and removal. */
    val modelStore by lazy { AdvisorModelStore(app) }

    /** Owns the on-device embedding model file that powers semantic retrieval (imported in-app). */
    val embeddingModelStore by lazy { EmbeddingModelStore(app) }

    /**
     * Retrieval strategy: semantic when an embedding model is loaded (via [LlamaCppEmbedder]), else the
     * deterministic lexical retriever. The vector cache is held here so it lives across questions —
     * and, being file-backed, across app starts: embedding the corpus is the slowest thing that
     * happens on a first question, and there is no reason to repeat it every launch.
     *
     * It lives in `cacheDir` because that is what it is. The system may delete it under storage
     * pressure and nothing breaks: the vectors are recomputed, which costs time and nothing else.
     */
    val retriever by lazy {
        HybridRetriever(
            LlamaCppEmbedder(embeddingModelStore),
            VectorCache.persistent(File(app.cacheDir, "advisor-vectors.bin"))
        )
    }

    /**
     * The language model: a local **Qwen3-4B** (Q4_K_M GGUF) run on-device via llama.cpp. Until the
     * weights are provisioned on the device the backend reports not-ready and the engine falls back to
     * a deterministic, grounded placeholder — so Advisor works either way and gains real reasoning the
     * moment the model file is present, with no code change.
     */
    val engine by lazy {
        val backend = backendHolder.value
        // A generation already running when the phone crosses into SEVERE is stopped where it is,
        // keeping what it has written — the budget that let it start was read up to a minute ago.
        deviceMonitor.watchThermal { level ->
            if (level.stopsGeneration) backend.interrupt("the phone is ${level.label}")
        }
        Qwen3LlmEngine(
            backend,
            // The prompt-boundary diagnostics: the engine itself stays Android-free, so the Android
            // log is attached here, at the wiring layer that already knows about the framework.
            log = { line -> Log.i(Qwen3LlmEngine.TAG, line) },
            device = deviceMonitor
        )
    }

    /** Memory, heat and power, read from the platform for the engine's per-turn budget. */
    val deviceMonitor by lazy { DeviceMonitor(app) }

    /**
     * Held as a [Lazy] rather than a `by lazy` property so the memory callback below can ask whether
     * it exists: a trim must never be the thing that loads the native library.
     */
    private val backendHolder = lazy { LlamaCppBackend(modelStore) }

    init {
        // The weights are gigabytes of anonymous memory in a process every app in the suite shares, so
        // when the system says that process is now background (TRIM_MEMORY_BACKGROUND — from 34 the
        // only other level delivered is UI_HIDDEN) they are the one thing worth handing back. Holding
        // them is what gets the whole suite killed rather than just this model reloaded. UI_HIDDEN
        // alone is not enough: leaving for a moment and coming back would cost a full reload.
        app.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND && backendHolder.isInitialized()) {
                    backendHolder.value.trim()
                }
            }

            override fun onConfigurationChanged(newConfig: Configuration) {}

            @Deprecated("Superseded by onTrimMemory; required by the interface.")
            override fun onLowMemory() {}
        })
    }

    /** The C3A unifying engine: coordinates the components and decides answer / clarify / investigate. */
    val logicEngine: LogicEngine by lazy { C3AEngine() }

    /**
     * The one place Advisor *writes* into another app: creating a LifeOps task the user explicitly
     * asked for, through LifeOps' own connection route. Still permission-gated — the repository checks
     * the LifeOps grant before calling it, exactly as it does before reading.
     */
    val taskWriter: TaskWriter by lazy { LifeOpsTaskWriter(app) }

    val repository by lazy {
        AdvisorRepository(
            dao = database.advisorDao(),
            sources = knowledgeSources,
            engine = engine,
            memory = memoryRepository,
            identityStore = identityStore,
            systemPromptStore = systemPromptStore,
            profileStore = profileStore,
            logicEngine = logicEngine,
            modelStore = modelStore,
            embeddingModelStore = embeddingModelStore,
            retriever = retriever,
            taskWriter = taskWriter
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
