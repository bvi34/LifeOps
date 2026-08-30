package com.operations.sandbox

import android.app.Application
import com.advisor.app.AdvisorApp
import com.citation.app.CitationApplication
import com.health.app.HealthApp
import com.lifeops.app.LifeOpsApp
import com.maintenance.app.MaintenanceApp
import com.people.app.PeopleApp
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
 * Project and Maintenance are lazy containers that cost nothing until their screens are opened —
 * Maintenance in particular creates no database file until somebody looks at the docket. Each
 * installs exactly once.
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
    }
}
