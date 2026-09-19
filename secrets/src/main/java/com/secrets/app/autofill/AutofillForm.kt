package com.secrets.app.autofill

import android.app.assist.AssistStructure
import android.text.InputType
import android.view.View
import android.view.autofill.AutofillId

/**
 * Somebody else's sign-in form, read well enough to fill it.
 *
 * The system hands an autofill service a tree of view nodes belonging to whatever app or page asked
 * for a password. Nothing in that tree is trustworthy and nothing in it is consistent: some apps
 * declare `autofillHints`, some declare an HTML input type, some declare an input type flag, and
 * plenty declare a resource id called `et_pw_2` and nothing else. So this reads every signal there
 * is, in order of how much the asker actually committed to it.
 *
 * The decision itself is in [classify], which takes the signals as plain values and touches nothing
 * on a device, so the part with judgement in it is the part under test.
 */
object AutofillForm {

    /** What a field is for, as far as a vault is concerned. */
    enum class FieldKind { USERNAME, PASSWORD, OTP }

    /**
     * What was found, and who was asking.
     *
     * [packageName] is the app the form belongs to; [webDomain] is the page, when the app is a
     * browser and says so. Both go to `AutofillMatch`, which is where the decision about *whose*
     * password may be offered is made and explained.
     */
    data class Parsed(
        val packageName: String?,
        val webDomain: String?,
        val usernameId: AutofillId? = null,
        val passwordId: AutofillId? = null,
        val otpId: AutofillId? = null
    ) {
        /** Is there anything here worth offering a credential for? */
        val fillable: Boolean get() = passwordId != null || usernameId != null || otpId != null

        /**
         * Only a form with somewhere to put a password is worth offering to *save*. A lone username
         * box — a "what is your email?" step — is a form that has not been filled in yet.
         */
        val savable: Boolean get() = passwordId != null

        val ids: Array<AutofillId> get() = listOfNotNull(usernameId, passwordId, otpId).toTypedArray()
    }

    /** Walk [structure] and pick out the first field of each kind. */
    fun parse(structure: AssistStructure): Parsed {
        var found = Parsed(
            packageName = structure.activityComponent?.packageName,
            webDomain = null
        )

        for (i in 0 until structure.windowNodeCount) {
            val root = structure.getWindowNodeAt(i).rootViewNode ?: continue
            found = visit(root, found)
        }
        return found
    }

    private fun visit(node: AssistStructure.ViewNode, soFar: Parsed): Parsed {
        var found = soFar

        // The page's own domain, taken from the first node that admits to one. A browser puts it on
        // the nodes inside the web view; an ordinary app has none, which is the difference that
        // decides how `AutofillMatch` is asked.
        node.webDomain?.takeIf { it.isNotBlank() }?.let { domain ->
            if (found.webDomain == null) found = found.copy(webDomain = domain)
        }

        val id = node.autofillId
        // A node with no autofill id cannot be filled, and one that does not take text cannot take
        // a password — a checkbox that happens to be called "password" is not a password field.
        if (id != null && node.autofillType == View.AUTOFILL_TYPE_TEXT) {
            val kind = classify(
                autofillHints = node.autofillHints?.toList().orEmpty(),
                idEntry = node.idEntry,
                hint = node.hint,
                htmlType = htmlTypeOf(node),
                inputType = node.inputType
            )
            // First of each kind wins. A sign-up form with "password" and "confirm password" fills
            // the first, which is the one the site checks; a second guess would be a second wrong
            // answer rather than a better one.
            found = when (kind) {
                FieldKind.USERNAME -> if (found.usernameId == null) found.copy(usernameId = id) else found
                FieldKind.PASSWORD -> if (found.passwordId == null) found.copy(passwordId = id) else found
                FieldKind.OTP -> if (found.otpId == null) found.copy(otpId = id) else found
                null -> found
            }
        }

        for (i in 0 until node.childCount) {
            found = visit(node.getChildAt(i), found)
        }
        return found
    }

    private fun htmlTypeOf(node: AssistStructure.ViewNode): String? =
        node.htmlInfo
            ?.attributes
            ?.firstOrNull { it.first.equals("type", ignoreCase = true) }
            ?.second

    /**
     * What one field is for, from whatever the asker was willing to say about it.
     *
     * Four tiers, most trustworthy first:
     *
     *  1. **A declared autofill hint.** The app or page said what this is for, in the vocabulary
     *     designed for saying it. Believed.
     *  2. **An HTML input type.** `type="password"` is a statement about behaviour — the browser
     *     will mask it — so it is nearly as good, and it is what most web forms actually carry.
     *  3. **An input type flag.** Same idea for a native field.
     *  4. **The id and the hint text.** Guesswork, and last for that reason. It exists because a
     *     great many real forms declare nothing at all, and a service that gives up on those is a
     *     service somebody turns off.
     *
     * A one-time-code field is recognised but is never *guessed* at from tier four: "code" appears
     * on postcode, area code, discount code and country code fields, and pasting a second factor
     * into a discount box would spend a code that only works once.
     */
    fun classify(
        autofillHints: List<String>,
        idEntry: String?,
        hint: String?,
        htmlType: String?,
        inputType: Int
    ): FieldKind? {
        for (raw in autofillHints) {
            val declared = raw.trim().lowercase()
            when {
                declared in PASSWORD_HINTS -> return FieldKind.PASSWORD
                declared in OTP_HINTS -> return FieldKind.OTP
                declared in USERNAME_HINTS -> return FieldKind.USERNAME
            }
        }

        when (htmlType?.trim()?.lowercase()) {
            "password" -> return FieldKind.PASSWORD
            "email" -> return FieldKind.USERNAME
        }

        val variation = inputType and InputType.TYPE_MASK_VARIATION
        val isText = (inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT
        if (isText) {
            when (variation) {
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD -> return FieldKind.PASSWORD

                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> return FieldKind.USERNAME
            }
        }

        val text = listOfNotNull(idEntry, hint).joinToString(" ").lowercase()
        if (text.isBlank()) return null
        return when {
            PASSWORD_WORDS.any { it in text } -> FieldKind.PASSWORD
            PASSWORD_TOKENS.any { hasToken(text, it) } -> FieldKind.PASSWORD
            USERNAME_WORDS.any { it in text } -> FieldKind.USERNAME
            USERNAME_TOKENS.any { hasToken(text, it) } -> FieldKind.USERNAME
            else -> null
        }
    }

    /**
     * Does [text] contain [token] as a word rather than as a run of letters inside one?
     *
     * The short abbreviations need this and the long words do not. `pw` is one of the commonest
     * things a password field is called in a resource id — `et_pw`, `pw_2`, `edt_pw_confirm` — and
     * it is also three letters into `upward`. A bounded match takes the first three and leaves the
     * fourth alone. Digits do not end a word for this purpose, because `pw2` is the second password
     * box rather than a different concept.
     */
    private fun hasToken(text: String, token: String): Boolean {
        var from = 0
        while (true) {
            val at = text.indexOf(token, from)
            if (at < 0) return false
            val before = if (at == 0) ' ' else text[at - 1]
            val after = if (at + token.length >= text.length) ' ' else text[at + token.length]
            if (!before.isLetter() && !after.isLetter()) return true
            from = at + 1
        }
    }

    // The Android constants plus the W3C tokens a browser passes straight through from a page's
    // `autocomplete` attribute. Both vocabularies turn up in real trees and neither is a superset.
    private val PASSWORD_HINTS = setOf(
        View.AUTOFILL_HINT_PASSWORD, "password", "current-password", "new-password"
    )

    private val USERNAME_HINTS = setOf(
        View.AUTOFILL_HINT_USERNAME, View.AUTOFILL_HINT_EMAIL_ADDRESS,
        "username", "email", "emailaddress", "email-address", "user-name"
    )

    private val OTP_HINTS = setOf(
        "smsotpcode", "one-time-code", "otp", "totp", "2facode"
    )

    private val PASSWORD_WORDS = listOf("password", "passwd", "passphrase")

    /** Matched on word boundaries rather than as substrings — see [hasToken]. */
    private val PASSWORD_TOKENS = listOf("pwd", "pw")

    // "login" and "account" are here and "code" deliberately is not; see the note on [classify].
    private val USERNAME_WORDS = listOf("username", "user_name", "userid", "email", "login", "account")

    private val USERNAME_TOKENS = listOf("user", "uid")
}
