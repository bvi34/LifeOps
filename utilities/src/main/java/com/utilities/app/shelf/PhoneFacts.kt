package com.utilities.app.shelf

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import com.utilities.app.keyboard.UtilitiesKeyboardService
import com.utilities.app.messages.MessagesRole

/**
 * What the platform will tell us about each takeover, and nothing more.
 *
 * Every function here returns a fact. What the facts *mean* — the wording on the tile, which step
 * to offer next, whether "installed but not selected" counts as half-done — is
 * [Takeovers], which is pure and tested. Keeping the two apart is what stops the shelf's copy from
 * being spread across a Compose file and a permission check.
 */
object PhoneFacts {

    /** Both takeovers' current state, in the order the shelf draws them. */
    fun all(context: Context): List<TakeoverStatus> = listOf(
        keyboard(context),
        messages(context)
    )

    fun keyboard(context: Context): TakeoverStatus = Takeovers.keyboard(
        enabled = keyboardEnabled(context),
        selected = keyboardSelected(context)
    )

    fun messages(context: Context): TakeoverStatus = Takeovers.messages(
        canRead = MessagesRole.canRead(context),
        canSend = MessagesRole.canSend(context),
        isDefault = MessagesRole.isDefault(context),
        hasTelephony = MessagesRole.hasTelephony(context),
        canReadContacts = MessagesRole.canReadContacts(context)
    )

    /**
     * Has the household switched this keyboard on in system settings?
     *
     * Read from the list of enabled input methods rather than from the secure setting string,
     * because the setting is a colon-separated list whose format has changed between releases and
     * the list is the platform answering the question directly.
     */
    fun keyboardEnabled(context: Context): Boolean {
        val manager = context.getSystemService(InputMethodManager::class.java) ?: return false
        return runCatching {
            manager.enabledInputMethodList.any { it.packageName == context.packageName }
        }.getOrDefault(false)
    }

    /**
     * Is it the one being typed on?
     *
     * `DEFAULT_INPUT_METHOD` is a flattened component name, so it is unflattened and compared
     * properly rather than by `startsWith` on the package — a package whose name is a prefix of
     * another's would otherwise answer yes for somebody else's keyboard.
     */
    fun keyboardSelected(context: Context): Boolean {
        val current = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        }.getOrNull() ?: return false
        val component = ComponentName.unflattenFromString(current) ?: return false
        return component.packageName == context.packageName &&
            component.className == UtilitiesKeyboardService::class.java.name
    }

    /** Where the household switches the keyboard on. There is no way to do it from inside an app. */
    fun keyboardSettings(): Intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)

    /**
     * Ask the phone which keyboard to use.
     *
     * The picker is the platform's, and it is the only way an app may cause a keyboard to be
     * selected — which is correct, and is why this is an offer rather than a switch.
     */
    fun showKeyboardPicker(context: Context) {
        (context.getSystemService(InputMethodManager::class.java))?.showInputMethodPicker()
    }
}
