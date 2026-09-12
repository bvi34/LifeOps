package com.operations.sandbox.cloud

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.operations.backupkit.AppId
import com.operations.backupkit.cloud.AzureBlobTarget
import com.operations.backupkit.cloud.CloudBackupFrequency
import com.operations.backupkit.cloud.CloudBackupRetention
import com.operations.backupkit.cloud.CloudBackupSchedule
import com.operations.backupkit.cloud.TargetCheck
import com.operations.vaultkit.ManagedSecrets
import com.operations.vaultkit.SecretOwner
import com.operations.vaultkit.SecretRef

/**
 * Everything the scheduled cloud backup has to remember: where it sends archives, how often, what
 * to keep, and how the last run went.
 *
 * Plain `SharedPreferences` rather than a Room database, and deliberately *outside* the archive, for
 * the reason [com.operations.sandbox.update.UpdatePrefs] gives: this belongs to the container, not
 * to any hosted app. It is also the specific thing that must not travel — an archive that carried
 * its own upload destination and credential would let anybody who obtained one keep writing into
 * the household's storage account, and restoring a year-old backup onto a new phone should not
 * silently resume a schedule nobody asked for.
 *
 * The signature is the exception, and it is the same exception the updater's GitHub token is: it is
 * kept locally in `EncryptedSharedPreferences` *and* mirrored into the vault, so a restore onto a
 * new phone can put it back rather than leaving the household's backups quietly switched off. See
 * [SAS_REF] and the long version in [ManagedSecrets].
 */
class CloudBackupPrefs internal constructor(context: Context, private val override: SharedPreferences?) {

    constructor(context: Context) : this(context, null)

    private val app = context.applicationContext

    private val prefs = app.getSharedPreferences("sandbox_cloud_backup", Context.MODE_PRIVATE)

    /**
     * The signature's working copy, encrypted at rest and separate from the settings.
     *
     * The fallback to a plain app-private file is not laziness: `EncryptedSharedPreferences` fails
     * outright on devices whose keystore is in a bad state, and a container that will not launch
     * because it could not open an optional credential store would be a far worse bug than the one
     * the encryption guards against. [override] is the test seam `UpdatePrefs` carries for the same
     * reason — `AndroidKeyStore` does not exist on the JVM, and what the tests exercise is the
     * mirroring, not androidx's encryption.
     */
    private val secrets: SharedPreferences = override ?: runCatching {
        val key = MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            app,
            "sandbox_cloud_backup_secrets",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }.getOrElse {
        app.getSharedPreferences("sandbox_cloud_backup_secrets_plain", Context.MODE_PRIVATE)
    }

    /**
     * Whether the sandbox may archive itself to Azure on its own.
     *
     * Off until somebody switches it on, and off is genuinely off: nothing here is scheduled, and
     * the worker that would do the work is cancelled rather than left to wake up and find a flag
     * false (see [ScheduledCloudBackupWorker.sync]).
     */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    /** The storage account name — `household` in `household.blob.core.windows.net`. */
    var account: String
        get() = prefs.getString(KEY_ACCOUNT, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_ACCOUNT, value.trim()).apply()

    /** The container archives are written into. It must already exist; nothing here creates one. */
    var container: String
        get() = prefs.getString(KEY_CONTAINER, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_CONTAINER, value.trim()).apply()

    /** An optional folder inside the container, stored exactly as the household typed it. */
    var prefix: String
        get() = prefs.getString(KEY_PREFIX, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_PREFIX, value.trim()).apply()

    /** The storage endpoint — only a sovereign cloud ever changes this. */
    var endpointSuffix: String
        get() = prefs.getString(KEY_ENDPOINT, AzureBlobTarget.DEFAULT_ENDPOINT_SUFFIX)
            .orEmpty().ifBlank { AzureBlobTarget.DEFAULT_ENDPOINT_SUFFIX }
        set(value) = prefs.edit().putString(KEY_ENDPOINT, value.trim()).apply()

    /**
     * The container's shared access signature: local working copy, vault copy, in that order.
     *
     * Read-through means normal running never consults the vault — the local store answers, and
     * only an empty one (which after a restore is this one) falls through and is refilled.
     */
    var sasToken: String?
        get() = ManagedSecrets.readThrough(
            ref = SAS_REF,
            local = { secrets.getString(KEY_SAS, null)?.takeIf { it.isNotBlank() } },
            rehydrate = { fromVault -> writeSasLocally(fromVault) }
        )
        set(value) {
            // Normalized on the way in so the token is stored the same however it was pasted — the
            // portal's `?sv=…`, the CLI's `sv=…`, or a whole blob URL.
            val clean = AzureBlobTarget.normalizeSas(value.orEmpty())
            writeSasLocally(clean)
            // After the local write, never instead of it: uploads must keep working on a phone with
            // no vault, and on one whose vault is shut the write is queued rather than lost.
            if (clean.isEmpty()) {
                ManagedSecrets.forget(SAS_REF)
            } else {
                ManagedSecrets.remember(SAS_REF, clean, SAS_LABEL, SecretOwner.SHELL)
            }
        }

    private fun writeSasLocally(value: String) {
        secrets.edit().apply {
            if (value.isEmpty()) remove(KEY_SAS) else putString(KEY_SAS, value)
        }.apply()
    }

    /**
     * Take the signature back out of the vault if this store has lost it, and say how many refs were
     * restored (one or none).
     *
     * The push half of the read-through above, and the one that matters most here: the scheduled
     * backup runs in the background, hours before anybody opens anything, and a worker that reads
     * through to a *shut* vault gets null and records "paste a signature" on a phone whose household
     * has done nothing wrong. Pushing on unlock means the first archive after a restore goes to the
     * same container the last one did.
     */
    fun restockFromVault(): Int = if (
        ManagedSecrets.restock(
            ref = SAS_REF,
            local = { secrets.getString(KEY_SAS, null)?.takeIf { it.isNotBlank() } },
            save = { value -> writeSasLocally(value) }
        )
    ) 1 else 0

    /** How often an archive is owed. */
    var frequency: CloudBackupFrequency
        get() = CloudBackupFrequency.fromKey(prefs.getString(KEY_FREQUENCY, null))
        set(value) = prefs.edit().putString(KEY_FREQUENCY, value.key).apply()

    /**
     * Whether to wait for an unmetered network.
     *
     * On by default, and the default that matters: a whole-suite archive is not a few kilobytes,
     * and a household on a metered plan should not discover this feature through their bill.
     */
    var wifiOnly: Boolean
        get() = prefs.getBoolean(KEY_WIFI_ONLY, true)
        set(value) = prefs.edit().putBoolean(KEY_WIFI_ONLY, value).apply()

    /** How many archives to keep in the container — see [CloudBackupRetention]. */
    var keep: Int
        get() = prefs.getInt(KEY_KEEP, CloudBackupRetention.DEFAULT_KEEP)
        set(value) = prefs.edit().putInt(KEY_KEEP, value.coerceAtLeast(CloudBackupRetention.KEEP_EVERYTHING)).apply()

    /**
     * Which apps a scheduled archive includes. Everything by default — an automatic backup that
     * quietly omitted an app would be discovered on the one day it mattered.
     *
     * Held as [AppId.key] strings rather than ordinals, for the reason the manifest is: the set
     * outlives any particular build's enum order, and a key this build has never heard of is
     * dropped on read rather than crashing the screen that lists them.
     */
    var selectedApps: Set<AppId>
        get() {
            val stored = prefs.getStringSet(KEY_APPS, null) ?: return AppId.entries.toSet()
            return stored.mapNotNull { AppId.fromKey(it) }.toSet()
        }
        set(value) = prefs.edit().putStringSet(KEY_APPS, value.map { it.key }.toSet()).apply()

    /** When the last scheduled or manual cloud archive completed, successfully or not. */
    var lastRunAt: Long
        get() = prefs.getLong(KEY_LAST_RUN, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_RUN, value).apply()

    /** When an archive last actually landed in the container. */
    var lastSuccessAt: Long
        get() = prefs.getLong(KEY_LAST_SUCCESS, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_SUCCESS, value).apply()

    /** The last run's outcome in the household's words — what the settings screen shows. */
    var lastStatus: String?
        get() = prefs.getString(KEY_LAST_STATUS, null)
        set(value) = prefs.edit().apply {
            if (value.isNullOrBlank()) remove(KEY_LAST_STATUS) else putString(KEY_LAST_STATUS, value)
        }.apply()

    /** The name of the last archive that landed, for the "last archive: …" line. */
    var lastBlobName: String?
        get() = prefs.getString(KEY_LAST_BLOB, null)
        set(value) = prefs.edit().apply {
            if (value.isNullOrBlank()) remove(KEY_LAST_BLOB) else putString(KEY_LAST_BLOB, value)
        }.apply()

    /** The destination as currently configured, or the first thing wrong with it. */
    fun target(): TargetCheck = AzureBlobTarget.check(
        account = account,
        container = container,
        prefix = prefix,
        sasToken = sasToken.orEmpty(),
        endpointSuffix = endpointSuffix
    )

    /** Whether an archive is owed — the arithmetic lives in [CloudBackupSchedule], with its tests. */
    fun isDue(now: Long = System.currentTimeMillis()): Boolean = CloudBackupSchedule.isDue(
        enabled = enabled,
        lastRunAt = lastRunAt,
        now = now,
        intervalMs = frequency.intervalMs
    )

    /** When the next archive is owed, for the line under the switch. */
    fun nextRunAt(now: Long = System.currentTimeMillis()): Long =
        CloudBackupSchedule.nextRunAt(lastRunAt, now, frequency.intervalMs)

    /**
     * File the signature into a vault that has just been rebuilt — the other half of
     * [com.operations.vaultkit.SecretSource], for a household that forgot their passphrase. Returns
     * how many refs were written, which here is one or none.
     */
    fun refileIntoVault(): Int {
        val local = secrets.getString(KEY_SAS, null)?.takeIf { it.isNotBlank() } ?: return 0
        ManagedSecrets.remember(SAS_REF, local, SAS_LABEL, SecretOwner.SHELL)
        return 1
    }

    companion object {
        private const val KEY_ENABLED = "enabled"
        private const val KEY_ACCOUNT = "account"
        private const val KEY_CONTAINER = "container"
        private const val KEY_PREFIX = "prefix"
        private const val KEY_ENDPOINT = "endpoint_suffix"
        private const val KEY_FREQUENCY = "frequency"
        private const val KEY_WIFI_ONLY = "wifi_only"
        private const val KEY_KEEP = "keep"
        private const val KEY_APPS = "apps"
        private const val KEY_LAST_RUN = "last_run_at"
        private const val KEY_LAST_SUCCESS = "last_success_at"
        private const val KEY_LAST_STATUS = "last_status"
        private const val KEY_LAST_BLOB = "last_blob"
        private const val KEY_SAS = "azure_sas"

        /**
         * Where the signature is filed in the vault: `sandbox/self/azure-backup-sas`.
         *
         * `self` because the shell has one destination, not one per row — the same reasoning the
         * updater's token uses. [SecretOwner.SHELL] because the container is not one of the apps it
         * hosts, and a household looking at their own vault should be able to see that the sandbox
         * keeps a credential of its own.
         */
        val SAS_REF = SecretRef(SecretOwner.SHELL_KEY, SecretRef.SELF, "azure-backup-sas")

        /** What the row is called in the vault list, beside the household's own logins. */
        private val SAS_LABEL = ManagedSecrets.label(SecretOwner.SHELL, "Azure backup signature")
    }
}
