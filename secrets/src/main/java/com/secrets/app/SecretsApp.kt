package com.secrets.app

import android.app.Application
import android.content.Context
import com.operations.vaultkit.SecretsAccess
import com.secrets.app.broker.VaultBroker
import com.secrets.app.data.SecretsPrefs
import com.secrets.app.data.VaultStore

/**
 * Secrets' runtime container, mirroring the other hosted apps: the Operations Sandbox [Application]
 * calls [install] once, and the activity resolves it with [get].
 *
 * ## What install actually does
 *
 * It registers the broker, and that is the whole of it. Registration is cheap — a [VaultStore] that
 * reads one file's header — and it has to happen at start-up rather than when somebody opens
 * Secrets, because the apps that read through the vault do not know Secrets exists: Finance asks
 * [SecretsAccess] for a token when it syncs, which may be minutes before anybody has opened this
 * app and may be on a phone where they never do.
 *
 * Nothing is unlocked by installing. The vault comes up shut on every process start, always: there
 * is no "remember me for a week", the key is never persisted in a form the app can read by itself,
 * and a phone that reboots in somebody else's pocket comes back as locked as it should be.
 *
 * ## Nothing here runs on its own
 *
 * No worker, no service, no notification, no timer that wakes the device. The auto-lock is a check
 * made while the app is on screen (see `MainActivity`), not a scheduled job — a background alarm
 * that existed to close a vault would be a background alarm that knows when the vault is open.
 */
class SecretsApp private constructor(app: Application) {

    val vault: VaultStore = VaultStore(app)

    val prefs: SecretsPrefs by lazy { SecretsPrefs(app) }

    private val broker = VaultBroker(vault)

    private fun register() {
        SecretsAccess.register(broker)
    }

    companion object {

        @Volatile
        private var instance: SecretsApp? = null

        fun install(app: Application): SecretsApp =
            instance ?: synchronized(this) {
                instance ?: SecretsApp(app).also { it.register(); instance = it }
            }

        fun get(context: Context): SecretsApp =
            instance ?: synchronized(this) {
                instance ?: SecretsApp(context.applicationContext as Application)
                    .also { it.register(); instance = it }
            }

        /**
         * The instance if there is one, without creating it.
         *
         * For the backup contributor, which runs in a restore that may or may not have been preceded
         * by anybody opening this app, and which should not bring a vault store into existence just
         * to tell it that a file changed.
         */
        fun peek(): SecretsApp? = instance
    }
}
