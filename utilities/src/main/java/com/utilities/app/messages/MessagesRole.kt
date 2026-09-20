package com.utilities.app.messages

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Telephony

/**
 * Being — or not being — the phone's messenger.
 *
 * The two rungs of the Messages takeover, expressed as the questions the platform can actually
 * answer. Nothing here decides what those answers *mean*; that is
 * [com.utilities.app.shelf.Takeovers.messages], which is pure and tested. This is the thin layer
 * that asks.
 */
object MessagesRole {

    /** Is this app the one Android hands arriving texts to? */
    fun isDefault(context: Context): Boolean =
        Telephony.Sms.getDefaultSmsPackage(context) == context.packageName

    /** Can this app read the store at all — the lower rung, and the one worth having on its own. */
    fun canRead(context: Context): Boolean = granted(context, Manifest.permission.READ_SMS)

    fun canSend(context: Context): Boolean = granted(context, Manifest.permission.SEND_SMS)

    fun canReadContacts(context: Context): Boolean = granted(context, Manifest.permission.READ_CONTACTS)

    /** A tablet with no radio has no texts to take over. */
    fun hasTelephony(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)

    /**
     * The intent that asks to become the default.
     *
     * `RoleManager` since Android 10, which is every phone this suite runs on — the suite's floor is
     * 34. The older `ACTION_CHANGE_DEFAULT` is not kept as a fallback, because a fallback for a
     * version the app cannot be installed on is dead code that looks like caution.
     *
     * Returns null when the role does not exist on this device at all, which is the honest answer
     * for a tablet: there is nothing to ask for.
     */
    fun requestDefault(context: Context): Intent? {
        val roles = context.getSystemService(RoleManager::class.java) ?: return null
        if (!roles.isRoleAvailable(RoleManager.ROLE_SMS)) return null
        return roles.createRequestRoleIntent(RoleManager.ROLE_SMS)
    }

    /**
     * Where somebody goes to hand the job back.
     *
     * Deliberately offered as prominently as taking it: the takeover is reversible, and an app that
     * makes it easy to become the messenger and hard to stop is an app doing something else.
     */
    fun systemDefaultsScreen(): Intent =
        Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)

    /** The permissions the reading rung needs, in the order they are worth asking for. */
    val readingPermissions: Array<String> = arrayOf(
        Manifest.permission.READ_SMS,
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.SEND_SMS
    )

    private fun granted(context: Context, permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}
