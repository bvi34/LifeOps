package com.operations.sandbox

import android.app.Application
import com.advisor.app.AdvisorApp
import com.citation.app.CitationApplication
import com.health.app.HealthApp
import com.lifeops.app.LifeOpsApp
import com.people.app.PeopleApp
import com.logistics.app.LogisticsApp

/**
 * The single [Application] for the whole suite. LifeOps and Citation are library modules now, so
 * this one process brings both of them up and owns their shared storage — which is precisely what
 * lets the Operations Sandbox back everything up into one archive without any cross-process plumbing.
 *
 * Startup order is intentional but not coupled: LifeOps runs its heavy launch work (week rollover,
 * reminder scheduling, the WAL-checkpoint lifecycle callback, the sleep service); Citation builds
 * its repository asynchronously and registers its periodic jobs. Logistics, Advisor, Health and
 * People are lazy containers that cost nothing until their screens are opened. Each installs exactly
 * once.
 *
 * People is installed last but is not last to matter: LifeOps' own startup runs a People sync round,
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
    }
}
