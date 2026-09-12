package com.finance.app

import android.app.Application
import android.content.Context
import com.finance.app.data.db.FinanceDatabase
import com.finance.app.data.prefs.FinancePrefs
import com.finance.app.data.repository.BillPublisher
import com.finance.app.data.repository.FinanceRepository
import com.finance.app.data.repository.FinanceSync
import com.finance.app.data.secure.FinanceSecrets
import com.lifeops.app.connection.TaskCompletionBus
import com.operations.backupkit.AppId
import com.operations.vaultkit.SecretOwner
import com.operations.vaultkit.SecretSource
import com.operations.vaultkit.SecretSources
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Finance's tiny runtime container, mirroring the other hosted apps: the Operations Sandbox
 * [Application] calls [install] once, and the activity resolves it with [get].
 *
 * Everything is lazy, so a suite install where nobody ever opens Finance pays nothing for it — no
 * database file is created and no keystore key is generated until somebody looks at an account.
 *
 * ## Nothing here runs on its own
 *
 * There is no sync service, no periodic worker and no notification. A refresh happens when the app
 * comes to the foreground and the picture is stale, or when somebody presses Refresh — and that is
 * the entire set of triggers. An app that phoned a bank on a schedule would need to justify a
 * background network cost against a benefit that is close to zero: nobody needs a four-hour-old
 * balance to be a two-hour-old balance.
 *
 * What it does instead is put the bills **on the LifeOps week** as tasks dated the day they fall
 * due, and take the ticks back (see [BillPublisher]) — one planner for the suite, and this app
 * supplying it rather than competing with it. That also means Finance does not raise reminders of
 * its own: the reminder is a task, in the place you already look.
 *
 * [install] registers for LifeOps' completion announcements. That registration is deliberately
 * cheap and deliberately gated: it is a listener on a bus, and it does nothing at all until this
 * install has actually published something, so the lazy-database promise above survives a household
 * that never opens this app.
 */
class FinanceApp private constructor(private val app: Application) {

    val database by lazy { FinanceDatabase.getInstance(app) }
    val prefs by lazy { FinancePrefs(app) }
    val secrets by lazy { FinanceSecrets(app) }
    val repository by lazy { FinanceRepository(database.financeDao()) }
    val publisher by lazy {
        BillPublisher(
            store = repository,
            onPublished = { prefs.hasPublishedTasks = true }
        )
    }
    val sync by lazy { FinanceSync(repository, secrets, publisher) }

    /**
     * Rounds and refreshes run here rather than on a screen's scope: a tick announced by LifeOps
     * arrives with no screen behind it, and a refresh started by opening the app should finish even
     * if you walked straight back out again.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Reconcile the bills with the week, in the background.
     *
     * Safe to call often and from anywhere — the round is idempotent, so the honest thing is to run
     * it whenever something might have changed rather than to reason about when it can be skipped.
     */
    fun publishNow() {
        scope.launch { publisher.round() }
    }

    /**
     * Refresh from the providers if it has been a while, then reconcile.
     *
     * Called when Finance comes to the foreground. The throttle lives in [FinancePrefs] rather than
     * here so that a screen offering an explicit Refresh can bypass it, which is the right split:
     * the app should be shy about asking a bank on its own and completely obedient when asked.
     */
    fun refreshIfStale() {
        scope.launch {
            if (!prefs.shouldAutoRefresh()) {
                publisher.round()
                return@launch
            }
            prefs.lastAutoRefreshAt = System.currentTimeMillis()
            sync.refreshAll()
        }
    }

    /**
     * Offer the vault a way to rebuild this app's half of itself.
     *
     * Registered at install, cheap by construction: it is one object in a map, and nothing it holds
     * is touched until somebody resets a forgotten passphrase — at which point the lazy store and
     * the lazy database open for the first time, which is exactly the moment they are wanted.
     *
     * The name lookup is why this lives here rather than in [FinanceSecrets]: the credentials are
     * filed by connection id, and only the database knows that `7f3a…` is called "USAA".
     */
    private fun registerAsSecretSource() {
        SecretSources.register(object : SecretSource {
            override val owner = SecretOwner.of(AppId.FINANCE)

            override suspend fun refile(): Int {
                val names = runCatching { repository.connections().associate { it.id to it.displayName } }
                    .getOrDefault(emptyMap())
                return secrets.refileIntoVault { id -> names[id] }
            }
        })
    }

    private fun listenForCompletions() {
        TaskCompletionBus.register {
            // Straight onto the background scope, gate and all: LifeOps resumes this on whichever
            // dispatcher ticked the task, which for a tap on a task row is the main thread — and
            // even reading a preference for the first time is a disk read.
            scope.launch {
                // The gate: an install that has never published a task cannot own the one just
                // ticked, and answering that from the database would open it for nothing.
                if (!prefs.hasPublishedTasks) return@launch
                // The round finds the completion for itself — including the case where the task was
                // carried into a new week under a new id, which the announcement's id alone could
                // not resolve.
                publisher.round()
            }
        }
    }

    companion object {

        @Volatile
        private var instance: FinanceApp? = null

        fun install(app: Application): FinanceApp =
            instance ?: synchronized(this) {
                instance ?: FinanceApp(app).also {
                    it.listenForCompletions()
                    it.registerAsSecretSource()
                    instance = it
                }
            }

        fun get(context: Context): FinanceApp =
            instance ?: synchronized(this) {
                instance ?: FinanceApp(context.applicationContext as Application)
                    .also {
                        it.listenForCompletions()
                        it.registerAsSecretSource()
                        instance = it
                    }
            }
    }
}
