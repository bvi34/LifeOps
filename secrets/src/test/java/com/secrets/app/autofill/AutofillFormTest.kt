package com.secrets.app.autofill

import android.text.InputType
import android.view.View
import com.secrets.app.autofill.AutofillForm.FieldKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Reading somebody else's form.
 *
 * The tiers matter: a field that *declared* what it is gets believed, and a field that declared
 * nothing gets guessed at from its resource id, which is the tier where a wrong answer types a
 * password into the wrong box. Most of these are about that last tier.
 *
 * No Robolectric — [AutofillForm.classify] takes the signals as plain values and the framework
 * constants it compares against are compile-time ones, so the decision runs on the JVM exactly as
 * it does on a phone.
 */
class AutofillFormTest {

    private fun classify(
        hints: List<String> = emptyList(),
        idEntry: String? = null,
        hint: String? = null,
        htmlType: String? = null,
        inputType: Int = 0
    ) = AutofillForm.classify(hints, idEntry, hint, htmlType, inputType)

    // --- Tier one: it said what it is -------------------------------------------------------------

    @Test
    fun `a declared hint is believed`() {
        assertEquals(FieldKind.PASSWORD, classify(hints = listOf(View.AUTOFILL_HINT_PASSWORD)))
        assertEquals(FieldKind.USERNAME, classify(hints = listOf(View.AUTOFILL_HINT_USERNAME)))
        assertEquals(FieldKind.USERNAME, classify(hints = listOf(View.AUTOFILL_HINT_EMAIL_ADDRESS)))
    }

    @Test
    fun `the web's vocabulary is understood as well as Android's`() {
        // What a browser passes through from a page's `autocomplete` attribute. Neither vocabulary
        // is a superset of the other and both turn up in real view trees.
        assertEquals(FieldKind.PASSWORD, classify(hints = listOf("current-password")))
        assertEquals(FieldKind.PASSWORD, classify(hints = listOf("new-password")))
        assertEquals(FieldKind.OTP, classify(hints = listOf("one-time-code")))
        assertEquals(FieldKind.USERNAME, classify(hints = listOf("email")))
        assertEquals(FieldKind.PASSWORD, classify(hints = listOf("  PASSWORD  ")))
    }

    // --- Tier two and three: it said how it behaves ----------------------------------------------

    @Test
    fun `an HTML input type is nearly as good as a hint`() {
        assertEquals(FieldKind.PASSWORD, classify(htmlType = "password"))
        assertEquals(FieldKind.USERNAME, classify(htmlType = "email"))
        assertNull(classify(htmlType = "text"))
    }

    @Test
    fun `a native input type flag is read`() {
        val password = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        val visible = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        val email = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS

        assertEquals(FieldKind.PASSWORD, classify(inputType = password))
        assertEquals(FieldKind.PASSWORD, classify(inputType = visible))
        assertEquals(FieldKind.USERNAME, classify(inputType = email))
    }

    @Test
    fun `a numeric field is not a password because its variation bits collide`() {
        // The variation constants are only meaningful within a class; without the text-class check
        // a number field would read as a password on the same bits.
        val number = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD

        assertNull(classify(inputType = number))
    }

    @Test
    fun `a declared hint beats the input type under it`() {
        // A field that masks its characters and says it is an email is an email — sites do this for
        // "confirm your email" boxes.
        assertEquals(
            FieldKind.USERNAME,
            classify(
                hints = listOf(View.AUTOFILL_HINT_EMAIL_ADDRESS),
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            )
        )
    }

    // --- Tier four: guessing, and where it stops --------------------------------------------------

    @Test
    fun `a form that declares nothing is guessed at from its ids`() {
        assertEquals(FieldKind.PASSWORD, classify(idEntry = "et_pw_2"))
        assertEquals(FieldKind.PASSWORD, classify(idEntry = "loginPassword"))
        assertEquals(FieldKind.PASSWORD, classify(hint = "Enter your passphrase"))
        assertEquals(FieldKind.USERNAME, classify(idEntry = "user_name"))
        assertEquals(FieldKind.USERNAME, classify(hint = "Email address"))
        assertEquals(FieldKind.USERNAME, classify(idEntry = "account_field"))
    }

    @Test
    fun `a one-time code is recognised when declared and never guessed at`() {
        assertEquals(FieldKind.OTP, classify(hints = listOf("otp")))

        // "code" is on postcode, area code, discount code and country code fields. A second factor
        // pasted into a discount box is a code spent — they only work once — so tier four does not
        // get to make this call at all.
        assertNull(classify(idEntry = "discount_code"))
        assertNull(classify(idEntry = "postcode"))
        assertNull(classify(hint = "Country code"))
        assertNull(classify(idEntry = "otp_field"))
    }

    @Test
    fun `a short abbreviation is matched as a word and not as three letters inside one`() {
        // `pw` is one of the commonest names a password box is given, and it is also three letters
        // into `upward`. Both of these are real resource ids.
        assertEquals(FieldKind.PASSWORD, classify(idEntry = "et_pw"))
        assertEquals(FieldKind.PASSWORD, classify(idEntry = "pw_confirm"))
        assertEquals(FieldKind.PASSWORD, classify(idEntry = "pwd"))
        assertNull(classify(idEntry = "upward_scroll"))
        assertNull(classify(idEntry = "superuser_notice"))
    }

    @Test
    fun `a field that says nothing at all is left alone`() {
        assertNull(classify())
        assertNull(classify(idEntry = "", hint = "   "))
        assertNull(classify(idEntry = "first_name"))
        assertNull(classify(idEntry = "search_box"))
    }

    // --- What the parsed form is for ---------------------------------------------------------------

    @Test
    fun `a form with nothing found in it is neither fillable nor savable`() {
        // The case the service checks first, and the one that has to be cheap: most view trees a
        // phone hands an autofill service contain no credential field at all.
        val nothing = AutofillForm.Parsed(packageName = "com.example", webDomain = null)

        assertEquals(false, nothing.fillable)
        assertEquals(false, nothing.savable)
        assertEquals(0, nothing.ids.size)
    }
}
