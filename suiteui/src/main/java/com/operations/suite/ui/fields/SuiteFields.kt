package com.operations.suite.ui.fields

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import com.operations.suitekit.SuiteInput
import com.operations.suitekit.SuiteMoney
import java.util.Currency
import java.util.Locale

/**
 * The suite's text and number fields — the only ones.
 *
 * Every app was writing `OutlinedTextField` out longhand, and four of them had wrapped it in a
 * private helper of their own first: two `NumField`s that differed by nothing, a `NumberField`, a
 * `MacroField`, a `DecimalField`. They had drifted in the way copies do — one filtered non-digits
 * out and one let them through, one put its argument list in a different order, and each had a
 * separate opinion about whether a field fills its row.
 *
 * Three ideas run through the set, inherited from the best of those copies.
 *
 * **What you typed is what is held.** These fields take a `String` and hand back a `String`, even
 * the numeric ones. A field that parsed on every keystroke would rewrite `"12."` under the cursor
 * halfway through `"12.5"`, and one that held a `Double` could not tell "nothing entered" from
 * "zero" — which is the difference between a service that was free and one you have not looked up.
 *
 * **A complaint is shown, not enforced.** Bad input sits in [supporting] and the value is left
 * alone. A dialog that refuses to close is a dialog you lose your typing to.
 *
 * **A filter is not a validator.** [SuiteNumberField] runs on every keystroke, so it accepts every
 * *prefix* of a number — a lone `-`, a trailing `.` — and leaves the question of whether the
 * finished text means anything to the caller. See [SuiteInput].
 */

/**
 * A text field.
 *
 * Nothing clever, which is the point: it exists so that every field in the suite is the same width,
 * the same shape, and has its supporting text in the same voice. Reach past it to a raw
 * `OutlinedTextField` only for genuinely bespoke chrome — a visual transformation, custom colours —
 * and not merely to add an icon, which [leading] and [trailing] already carry.
 */
@Composable
fun SuiteTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    placeholder: String? = null,
    supporting: String? = null,
    isError: Boolean = false,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    capitalise: KeyboardCapitalization = KeyboardCapitalization.Sentences,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Default,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        isError = isError,
        enabled = enabled,
        readOnly = readOnly,
        supportingText = supporting?.let { { Text(it) } },
        leadingIcon = leading,
        trailingIcon = trailing,
        keyboardOptions = KeyboardOptions(
            capitalization = capitalise,
            keyboardType = keyboardType,
            imeAction = imeAction
        ),
        modifier = modifier.fillMaxWidth()
    )
}

/**
 * Several lines of prose — a note, a description, the thing somebody actually wanted to write down.
 *
 * It is [SuiteTextField] with the single-line assumption dropped, named separately because "this
 * field expects a paragraph" is worth reading at the call site rather than inferring from a
 * `singleLine = false` three arguments in.
 */
@Composable
fun SuiteNoteField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    minLines: Int = 3,
    maxLines: Int = 8,
    placeholder: String? = null,
    supporting: String? = null,
    enabled: Boolean = true
) {
    SuiteTextField(
        label = label,
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        singleLine = false,
        minLines = minLines,
        maxLines = maxLines,
        placeholder = placeholder,
        supporting = supporting,
        enabled = enabled
    )
}

/**
 * A number, held as text.
 *
 * Whole by default — a mileage, a year, an interval — and non-digits simply never arrive, so there
 * is nothing to validate afterwards and no error message to write. [decimals] lets a single point
 * through for the fields that are genuinely fractional (a lot size is 0.34 acres, a dose is 2.5ml),
 * and [signed] a leading minus for the ones that can go the other way (a temperature, an
 * adjustment). Both are off by default, because a field that accepts more than it means is a field
 * whose bad values turn up somewhere else.
 */
@Composable
fun SuiteNumberField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    decimals: Boolean = false,
    signed: Boolean = false,
    placeholder: String? = null,
    supporting: String? = null,
    isError: Boolean = false,
    enabled: Boolean = true,
    imeAction: ImeAction = ImeAction.Default,
    trailing: @Composable (() -> Unit)? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(SuiteInput.filter(it, decimals, signed)) },
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = true,
        isError = isError,
        enabled = enabled,
        supportingText = supporting?.let { { Text(it) } },
        trailingIcon = trailing,
        keyboardOptions = KeyboardOptions(
            // The keyboard matches what the filter will accept: offering a decimal pad to a field
            // that swallows the point is a small cruelty people blame themselves for.
            keyboardType = if (decimals) KeyboardType.Decimal else KeyboardType.Number,
            imeAction = imeAction
        ),
        modifier = modifier.fillMaxWidth()
    )
}

/**
 * An amount of money, held as text while it is being typed and reported in cents.
 *
 * [onChange] is handed null while the text is not yet a number — including while it is empty — so a
 * caller can tell "nothing entered" from "zero". Unlike [SuiteNumberField] this one does **not**
 * filter: people paste amounts out of bank apps, commas, currency symbols and all, and
 * [SuiteMoney.parse] is deliberately forgiving about exactly that. What it will not accept is a
 * third decimal place, which is a typo far more often than it is a fraction of a cent — and that
 * shows as a complaint rather than a swallowed keystroke.
 */
@Composable
fun SuiteMoneyField(
    label: String,
    text: String,
    onChange: (text: String, cents: Long?) -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    enabled: Boolean = true
) {
    val symbol = rememberCurrencySymbol()
    val bad = text.isNotBlank() && SuiteMoney.parse(text) == null
    OutlinedTextField(
        value = text,
        onValueChange = { onChange(it, SuiteMoney.parse(it)) },
        // The symbol rides in the label rather than as a prefix: a prefix sits in the text box and
        // reads, at a glance, like part of what you typed.
        label = { Text("$label ($symbol)") },
        singleLine = true,
        isError = bad,
        enabled = enabled,
        supportingText = when {
            bad -> { { Text("An amount, like 1,234.56") } }
            supporting != null -> { { Text(supporting) } }
            else -> null
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier.fillMaxWidth()
    )
}

/**
 * The currency symbol this phone uses.
 *
 * [SuiteMoney] takes the symbol as a parameter so its arithmetic and its tests do not change meaning
 * by locale; resolving it is a job for the edge, and this is the edge. An unknown locale falls back
 * to `$` rather than to an empty string, because a bare `1,234.56` in a column of money reads as a
 * mileage.
 */
@Composable
fun rememberCurrencySymbol(): String = remember {
    runCatching { Currency.getInstance(Locale.getDefault()).symbol }
        .getOrNull()?.takeIf { it.isNotBlank() } ?: "$"
}

/** Money for a screen: cents in, the phone's own symbol on the front. */
@Composable
fun suiteMoney(cents: Long, withCents: Boolean = true): String =
    SuiteMoney.format(cents, symbol = rememberCurrencySymbol(), cents = withCents)
