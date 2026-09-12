package com.operations.sandbox

import android.app.Application
import com.advisor.app.AdvisorApp
import com.citation.app.CitationApplication
import com.health.app.HealthApp
import com.lifeops.app.LifeOpsApp
import com.maintenance.app.MaintenanceApp
import com.people.app.PeopleApp
import com.repository.app.RepositoryApp
import com.secrets.app.SecretsApp
import com.project.app.ProjectApp
import com.finance.app.FinanceApp
import com.logistics.app.LogisticsApp
import com.operations.sandbox.update.UpdatePrefs
import com.operations.vaultkit.SecretOwner
import com.operations.vaultkit.SecretSource
import com.operations.vaultkit.SecretSources

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
 *
 * The shell registers one thing on its own behalf, after Secrets: it holds a credential too (the
 * updater's GitHub token) and is therefore a [SecretSource] like Finance and Citation, so a vault
 * rebuilt after a forgotten passphrase gets the token filed back rather than leaving the suite
 * unable to update itself.
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
        FinanceApp.install(this)
        RepositoryApp.install(this)
        // Secrets is installed last and matters first: installing it registers the broker that
        // Finance, Citation and anything else reads its credentials back through. It has to happen
        // at start-up rather than when somebody opens the app, because a sync that runs before
        // anybody has touched Secrets is exactly the case the vault exists to serve — and it costs
        // one file's header being read. Nothing is unlocked by installing; the vault comes up shut
        // on every process start, always.
        SecretsApp.install(this)
        registerShellAsSecretSource()
    }

    /**
     * The container's own half of the vault seam.
     *
     * The shell is not a hosted app — it has no tile, no [com.operations.backupkit.AppId] and no
     * backup contributor — but it holds exactly the kind of credential the vault was built for, and
     * losing it on a restore has exactly the consequence the vault was built to prevent. So it
     * registers here on the same terms every app does: it can refill what it still holds, and it
     * cannot read anything it did not file.
     *
     * [UpdatePrefs] is constructed on demand rather than held: it is two `SharedPreferences` opens,
     * and a reset is the one moment it is worth paying for them.
     */
    private fun registerShellAsSecretSource() {
        SecretSources.register(object : SecretSource {
            override val owner = SecretOwner.SHELL

            override suspend fun refile(): Int = UpdatePrefs(this@SandboxApplication).refileIntoVault()
        })
    }
}
