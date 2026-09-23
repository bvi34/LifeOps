package com.operations.sandbox.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import com.operations.sandbox.update.logic.InstallOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Installs a downloaded release over the running app through a [PackageInstaller] session.
 *
 * This replaced handing the APK to the system installer with `ACTION_VIEW`, which always showed
 * the installer's confirmation screen. A session can ask for [PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED],
 * and Android grants it when this app is the one that installed the running version. So the first
 * update after a sideload from Files or a browser still asks, once; after that, pressing Install
 * installs. Pressing Install is still required — nothing here runs on its own.
 *
 * [PackageInstaller.SessionParams.setRequestUpdateOwnership] makes the sandbox the *update owner*
 * too (Android 14). Another installer that tries to replace it afterwards — a stray APK opened from
 * Files — gets a confirmation that names the sandbox as the app that normally updates it.
 *
 * The session's answer comes back as a broadcast to [InstallStatusReceiver], which republishes it
 * on [outcomes] for the Updates tab.
 */
class SelfInstaller(context: Context) {

    private val appContext = context.applicationContext

    /**
     * Whether the next install should go through with no confirmation: this app installed the
     * running version, or owns its updates. A guess the installer has the final word on, used only
     * to choose the sentence under the Install button.
     */
    fun installsWithoutPrompt(): Boolean = runCatching {
        val source = appContext.packageManager.getInstallSourceInfo(appContext.packageName)
        source.installingPackageName == appContext.packageName ||
            source.updateOwnerPackageName == appContext.packageName
    }.getOrDefault(false)

    /**
     * Copy [apk] into a new session and commit it. Returns once the session is committed; what the
     * installer makes of it arrives on [outcomes].
     *
     * A session that fails before its commit is abandoned, so a half-written one doesn't sit in the
     * installer's staging area until the next reboot.
     */
    suspend fun install(apk: File) = withContext(Dispatchers.IO) {
        val installer = appContext.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(appContext.packageName)
            setSize(apk.length())
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            setRequestUpdateOwnership(true)
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                session.openWrite(SESSION_ENTRY, 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                session.commit(statusReceiver(sessionId).intentSender)
            }
        } catch (failure: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            throw failure
        }
    }

    /**
     * Where the installer reports back. Explicit and not exported, and mutable because the installer
     * writes the status into it — the one combination Android 14 allows for a mutable intent.
     */
    private fun statusReceiver(sessionId: Int): PendingIntent =
        PendingIntent.getBroadcast(
            appContext,
            sessionId,
            Intent(appContext, InstallStatusReceiver::class.java).setAction(ACTION_INSTALL_STATUS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

    companion object {
        const val ACTION_INSTALL_STATUS = "com.operations.sandbox.update.INSTALL_STATUS"
        private const val SESSION_ENTRY = "base.apk"

        private val outcomeFlow = MutableSharedFlow<InstallOutcome>(extraBufferCapacity = 8)

        /** Every status the installer has reported since the process started. */
        val outcomes: SharedFlow<InstallOutcome> = outcomeFlow.asSharedFlow()

        internal fun publish(outcome: InstallOutcome) {
            outcomeFlow.tryEmit(outcome)
        }
    }
}

/**
 * Receives an install session's status and passes it on.
 *
 * When Android wants the user to confirm, the status carries the confirmation screen as an intent,
 * and starting it is this receiver's job. The user has just pressed Install, so the sandbox is in
 * the foreground and allowed to start it.
 */
class InstallStatusReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SelfInstaller.ACTION_INSTALL_STATUS) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val outcome = InstallOutcome.of(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
        if (outcome == InstallOutcome.AwaitingConfirmation) {
            val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            if (confirm == null) {
                SelfInstaller.publish(
                    InstallOutcome.Failed("Not installed", "Android asked for a confirmation it didn't provide.")
                )
                return
            }
            runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure {
                    SelfInstaller.publish(
                        InstallOutcome.Failed(
                            "Not installed",
                            "Android wanted a confirmation, and the screen for it couldn't be opened " +
                                "(${it.message}). Open the sandbox and tap Install again."
                        )
                    )
                    return
                }
        }
        SelfInstaller.publish(outcome)
    }
}
