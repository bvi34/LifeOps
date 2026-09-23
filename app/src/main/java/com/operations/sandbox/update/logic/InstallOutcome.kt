package com.operations.sandbox.update.logic

/**
 * What the package installer said about an install session, in words the Updates tab can use.
 *
 * The installer answers a session with a status code and, sometimes, a message written for
 * developers ("INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package … signatures do not match").
 * Which codes can be answered with a sentence, which mean "put the Install button back", and which
 * mean the install is still going are decided here, in plain Kotlin, so they can be tested on the
 * JVM. Reading the status out of the broadcast is the updater's job.
 *
 * The codes are the platform's `PackageInstaller.STATUS_*` values, repeated rather than imported so
 * this file stays framework-free. They are part of the public API and cannot change;
 * `InstallOutcomeTest` checks each one against the platform constant all the same.
 */
sealed interface InstallOutcome {

    /** Installed. The running process is about to be replaced, so this is rarely seen. */
    data object Installed : InstallOutcome

    /**
     * Android wants the user to confirm, and the confirmation screen is on its way. The install is
     * still running.
     *
     * This happens when Operations Sandbox is not yet the app of record for its own updates — the
     * first update after installing from Files or a browser. Once one update has gone through this
     * way, the next one needs no confirmation.
     */
    data object AwaitingConfirmation : InstallOutcome

    /**
     * The user backed out of the confirmation, or the session was abandoned. Not a failure: the
     * download is still there and the Install button should be too.
     */
    data object Cancelled : InstallOutcome

    /** The install did not happen, and [message] says why in a way a person can act on. */
    data class Failed(val title: String, val message: String) : InstallOutcome

    companion object {
        // PackageInstaller.STATUS_* — see the class comment.
        const val STATUS_PENDING_USER_ACTION = -1
        const val STATUS_SUCCESS = 0
        const val STATUS_FAILURE = 1
        const val STATUS_FAILURE_BLOCKED = 2
        const val STATUS_FAILURE_ABORTED = 3
        const val STATUS_FAILURE_INVALID = 4
        const val STATUS_FAILURE_CONFLICT = 5
        const val STATUS_FAILURE_STORAGE = 6
        const val STATUS_FAILURE_INCOMPATIBLE = 7
        const val STATUS_FAILURE_TIMEOUT = 8

        /**
         * Interpret one status broadcast. [detail] is the installer's own message, kept at the end
         * of a failure's sentence: nobody should have to read it, but it is the only thing that
         * helps when a sentence here turns out to be wrong.
         */
        fun of(status: Int, detail: String?): InstallOutcome {
            val suffix = detail?.trim()?.takeIf { it.isNotEmpty() }?.let { " ($it)" }.orEmpty()
            return when (status) {
                STATUS_SUCCESS -> Installed
                STATUS_PENDING_USER_ACTION -> AwaitingConfirmation
                STATUS_FAILURE_ABORTED -> Cancelled
                STATUS_FAILURE_CONFLICT -> Failed(
                    title = "Not installed",
                    message = "Android refused to put this APK over the build you are running. " +
                        "The usual cause is that the two are signed with different keys, which " +
                        "Android never allows — see the steps for moving across on this tab." + suffix
                )
                STATUS_FAILURE_STORAGE -> Failed(
                    title = "Not enough space",
                    message = "The phone doesn't have room to install the update. Free some " +
                        "space and tap Install again — the download is kept." + suffix
                )
                STATUS_FAILURE_INVALID -> Failed(
                    title = "Not installed",
                    message = "Android couldn't read the downloaded APK. It may have been cut " +
                        "short: dismiss this and download it again." + suffix
                )
                STATUS_FAILURE_INCOMPATIBLE -> Failed(
                    title = "Not installed",
                    message = "This release won't run on this phone — it was built for a newer " +
                        "Android or a different processor." + suffix
                )
                STATUS_FAILURE_BLOCKED -> Failed(
                    title = "Install blocked",
                    message = "Something on the phone — a device policy or another app — " +
                        "blocked the install." + suffix
                )
                STATUS_FAILURE_TIMEOUT -> Failed(
                    title = "Not installed",
                    message = "Android gave up waiting for the install to be confirmed. Tap " +
                        "Install again." + suffix
                )
                else -> Failed(
                    title = "Not installed",
                    message = "Android didn't install the update." + suffix
                )
            }
        }
    }
}
