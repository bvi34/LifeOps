package com.citation.app

import android.app.Application
import android.content.Context
import com.citation.app.audio.NeuralSynthesizers
import com.citation.app.audio.SherpaNeuralSynthesizer
import com.citation.app.data.CitationRepository
import com.citation.app.data.OreillyAccess
import com.citation.app.data.db.CitationDatabase
import com.citation.app.data.pdf.PdfPageText
import com.citation.app.data.opds.CatalogCredentials
import com.citation.app.data.store.FileStores
import com.citation.app.work.RoyalRoadScheduler
import com.citation.app.work.SyncWorker
import com.operations.backupkit.AppId
import com.operations.vaultkit.SecretOwner
import com.operations.vaultkit.SecretSource
import com.operations.vaultkit.SecretSources
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/**
 * Citation's runtime. It used to *be* the `Application`, but under the Operations Sandbox container
 * a single [Application] ([com.operations.sandbox.SandboxApplication]) hosts both LifeOps and
 * Citation, so this is now a plain holder the sandbox constructs via [install]. Feature code reaches
 * it through [get]/[getOrNull] instead of casting the Application.
 *
 * The class name is unchanged so existing typed references keep compiling. [repository] is still
 * built asynchronously (it restores the key allocator and mailbox from Room); callers await it.
 */
class CitationApplication private constructor(private val app: Application) {

    private val appScope = CoroutineScope(SupervisorJob())

    lateinit var repository: Deferred<CitationRepository>
        private set

    /** Build the repository and register Citation's periodic jobs, once. Called by the sandbox. */
    private fun start() {
        val db = CitationDatabase.get(app)
        val files = FileStores(app)
        val oreillyAccess = OreillyAccess(app)
        // Catalog sign-ins live in their own Keystore-backed store rather than the database, so a
        // backup or a restored `citation.db` never carries a way into someone's server.
        val catalogCredentials = CatalogCredentials(app)
        // PDF text extraction (the reflow track) needs a Context for PDFBox's resource loader, so it
        // is built here and handed to the repository as a seam.
        val pdfText = PdfPageText(app)
        repository = appScope.async { CitationRepository.create(db, files, oreillyAccess, pdfText, catalogCredentials) }
        registerAsSecretSource(oreillyAccess, catalogCredentials)
        // The on-device neural voice's runtime, registered only once the linker confirms this build
        // actually carries it — the native libraries are fetched at build time and may legitimately
        // be absent. Off the main thread, because confirming it maps tens of megabytes. Nothing is
        // constructed here either way: a synthesizer is made when a reader first asks to be read to,
        // and a build without the libraries simply never registers one, which the narrator answers
        // by using the platform voice.
        appScope.launch {
            if (SherpaNeuralSynthesizer.isRuntimePresent()) {
                NeuralSynthesizers.register { SherpaNeuralSynthesizer(app) }
            }
        }
        // Register the periodic RR jobs (poll favourites, advance backfill, evict stale cache).
        RoyalRoadScheduler.schedule(app)
        // Register the periodic sync round with LifeOps (drain outbox, consume acquire intents).
        SyncWorker.schedule(app)
    }

    /**
     * Offer the vault a way to rebuild this app's half of itself after a forgotten passphrase.
     *
     * The two stores are handed in rather than resolved later because they are already built here,
     * and the catalogue names come from the repository — which is a [kotlinx.coroutines.Deferred],
     * and awaiting it is free by the time anybody is resetting a vault by hand.
     */
    private fun registerAsSecretSource(
        oreillyAccess: OreillyAccess,
        catalogCredentials: CatalogCredentials
    ) {
        SecretSources.register(object : SecretSource {
            override val owner = SecretOwner.of(AppId.CITATION)

            override suspend fun refile(): Int {
                // The names are resolved up front, suspending, so the store's own re-filing stays a
                // plain synchronous walk over what it holds. A `runBlocking` inside it would be a
                // database read on whatever thread happened to be running the reset.
                val repo = runCatching { repository.await() }.getOrNull()
                val names = catalogCredentials.catalogIds().associateWith { id ->
                    runCatching { repo?.catalog(id)?.name }.getOrNull()
                }
                return catalogCredentials.refileIntoVault { names[it] } + oreillyAccess.refileIntoVault()
            }
        })
    }

    companion object {
        @Volatile
        private var instance: CitationApplication? = null

        /** Construct Citation's runtime against the hosting [app] and start it, once. */
        fun install(app: Application): CitationApplication =
            instance ?: synchronized(this) {
                instance ?: CitationApplication(app).also { instance = it; it.start() }
            }

        /** The installed runtime. Throws if the host never called [install] (a wiring bug). */
        fun get(context: Context): CitationApplication =
            instance ?: error("CitationApplication.install() was never called by the hosting Application")

        /** The installed runtime, or null — for background entry points that must degrade gracefully. */
        fun getOrNull(): CitationApplication? = instance
    }
}
