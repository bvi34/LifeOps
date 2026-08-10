package com.operations.sandbox

import android.app.Application
import com.advisor.app.AdvisorApp
import com.citation.app.CitationApplication
import com.lifeops.app.LifeOpsApp
import com.logistics.app.LogisticsApp

/**
 * The single [Application] for the whole suite. LifeOps and Citation are library modules now, so
 * this one process brings both of them up and owns their shared storage — which is precisely what
 * lets the Operations Sandbox back everything up into one archive without any cross-process plumbing.
 *
 * Startup order is intentional but not coupled: LifeOps runs its heavy launch work (week rollover,
 * reminder scheduling, the WAL-checkpoint lifecycle callback, the sleep service); Citation builds
 * its repository asynchronously and registers its periodic jobs. Both install exactly once.
 */
class SandboxApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        LifeOpsApp.install(this)
        CitationApplication.install(this)
        LogisticsApp.install(this)
        AdvisorApp.install(this)
    }
}
