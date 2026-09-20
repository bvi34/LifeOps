package com.operations.sandbox

import android.app.Application
import com.advisor.app.AdvisorApp
import com.citation.app.CitationApplication
import com.finance.app.FinanceApp
import com.health.app.HealthApp
import com.lifeops.app.LifeOpsApp
import com.logistics.app.LogisticsApp
import com.maintenance.app.MaintenanceApp
import com.operations.sandbox.cloud.CloudBackupPrefs
import com.operations.sandbox.cloud.ScheduledCloudBackupWorker
import com.operations.sandbox.update.UpdatePrefs
import com.operations.vaultkit.SecretOwner
import com.operations.vaultkit.SecretSource
import com.operations.vaultkit.SecretSources
import com.people.app.PeopleApp
import com.project.app.ProjectApp
import com.repository.app.RepositoryApp
import com.secrets.app.SecretsApp
import com.utilities.app.UtilitiesApp

/**
 * The single [Application] for the whole suite. LifeOps and Citation are library modules now, so
 * this one process brings both of them up and owns their shared storage — which is precisely what
 * lets the Operations Sandbox back everything up into one archive without any cross-process plumbing.
 *
 * Startup order is intentional but not coupled: LifeOps runs its heavy launch work (week rollover,
 * reminder scheduling, the WAL-checkpoint lifecycle callback, the sleep service); Citation builds
 * its repository asynchronously and registers its periodic jobs. Logistics, Advisor, Health, People,
 * Project, Maintenance, Repository and Utilities are lazy containers that cost nothing until their
 * screens are opened — Maintenance creates no database file until somebody looks at the docket, and Repository
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
 * The shell registers on its own behalf too, after Secrets: it holds credentials (the updater's
 * GitHub token, and the signature the scheduled cloud backup uploads with) and is therefore a
 * [SecretSource] like Finance and Citation, so a vault rebuilt after a forgotten passphrase gets
 * them filed back rather than leaving the suite unable to update or to back itself up.
 *
 * It also owns one piece of background work now — the scheduled archive to the household's own
 * storage account — which is registered here rather than from a screen, because a backup that only
 * exists while somebody has the settings open is not a backup.
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
        // Utilities last, and it is the cheapest install in the suite: two stores opened, no worker,
        // no service, no channel. It is here at all so that a restore has something to tell that the
        // appearance file and the word list changed underneath it — the components the platform
        // starts (the keyboard, the SMS receivers) each resolve their own store on the way in and do
        // not need this to have run.
        UtilitiesApp.install(this)
        registerShellAsSecretSource()
        // Re-register the scheduled cloud backup. WorkManager's own store survives a restart, but
        // not a reinstall or a "clear data", and this is also where a frequency or Wi-Fi-only
        // setting restored from a vault-backed reinstall first takes effect. It cancels the job
        // when the feature is off, so calling it unconditionally is the whole of the contract.
        ScheduledCloudBackupWorker.sync(this)
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
     * The shell holds two of them now — the updater's GitHub token and the signature the scheduled
     * backup uploads with — and both are refiled, because a rebuilt vault that restores one and not
     * the other leaves the household with exactly the silent failure this seam exists to prevent.
     *
     * [UpdatePrefs] and [CloudBackupPrefs] are constructed on demand rather than held: they are a
     * couple of `SharedPreferences` opens, and a reset is the one moment it is worth paying for them.
     */
    private fun registerShellAsSecretSource() {
        SecretSources.register(object : SecretSource {
            override val owner = SecretOwner.SHELL

            override suspend fun refile(): Int =
                UpdatePrefs(this@SandboxApplication).refileIntoVault() +
                    CloudBackupPrefs(this@SandboxApplication).refileIntoVault()

            /**
             * And the direction a restore walks: both of the shell's credentials are device-bound
             * and did not travel, the vault did, and this puts them back the moment it is opened
             * rather than waiting for the next launch check or the next scheduled archive to ask.
             */
            override suspend fun rehydrate(): Int =
                UpdatePrefs(this@SandboxApplication).restockFromVault() +
                    CloudBackupPrefs(this@SandboxApplication).restockFromVault()
        })
    }
}
