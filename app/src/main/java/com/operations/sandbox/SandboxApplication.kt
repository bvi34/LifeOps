package com.operations.sandbox

import android.app.Application
import com.advisor.app.AdvisorApp
import com.citation.app.CitationApplication
import com.health.app.HealthApp
import com.lifeops.app.LifeOpsApp
import com.maintenance.app.MaintenanceApp
import com.people.app.PeopleApp
import com.repository.app.RepositoryApp
import com.project.app.ProjectApp
import com.logistics.app.LogisticsApp

/**
 * The single [Application] for the whole suite. LifeOps and Citation are library modules now, so
 * this one process brings both of them up and owns their shared storage — which is precisely what
 * lets the Operations Sandbox back everything up into one archive without any cross-process plumbing.
 *
 * Startup order is intentional but not coupled: LifeOps runs its heavy launch work (week rollover,
 * reminder scheduling, the WAL-checkpoint lifecycle callback, the sleep service); Citation builds
 * its repository asynchronously and registers its periodic jobs. Logistics, Advisor, Health, People,
 * Project, Maintenance and Repository are lazy containers that cost nothing until their screens are
 * opened — Maintenance creates no database file until somebody looks at the docket, and Repository
 * none until something asks the shelf a question. Each installs exactly once.
 *
 * Repository is installed last and is the one every other app may reach into: it is where the
 * household's documents live, and an app that files one (or lends the shelf its own) resolves it
 * lazily rather than being handed it, so the order here cannot matter.
 *
 * People is installed late but is not late to matter: LifeOps' own startup runs a People sync round,
 * and both peers reconcile through a folder rather than through each other, so the order they come
 * up in cannot change what either ends up holding.
 */
class SandboxApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        LifeOpsApp.install(this)
        CitationApplication.install(this)
        LogisticsApp.install(this)
        AdvisorApp.install(this)
        HealthApp.install(this)
        PeopleApp.install(this)
        ProjectApp.install(this)
        MaintenanceApp.install(this)
        RepositoryApp.install(this)
    }
}
