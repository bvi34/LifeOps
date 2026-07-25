package com.citation.app

import android.app.Application
import com.citation.app.data.CitationRepository
import com.citation.app.data.db.CitationDatabase
import com.citation.app.data.store.FileStores
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/**
 * Process-wide wiring. The [repository] is built asynchronously (it restores the key allocator and
 * mailbox from Room), so callers await the [Deferred]. Simple manual DI, mirroring how LifeOps holds
 * its dispatcher/services on the Application.
 */
class CitationApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob())

    lateinit var repository: Deferred<CitationRepository>
        private set

    override fun onCreate() {
        super.onCreate()
        val db = CitationDatabase.get(this)
        val files = FileStores(this)
        repository = appScope.async { CitationRepository.create(db, files) }
    }
}
