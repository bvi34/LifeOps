package com.maintenance.app.ui.common

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import com.maintenance.app.logic.Money
import java.time.LocalDate

/**
 * The form fields these screens are built from.
 *
 * Two ideas run through all of them. **What you typed is what is held** — a money field keeps your
 * text while you are typing it and only converts on the way out, so a half-entered "12." does not
 * get rewritten under the cursor. And **a complaint is shown, not enforced**: a field that cannot be
 * parsed says so in its support text and leaves the value alone, because a dialog that refuses to
 * close is a dialog you lose your typing to.
 */

/** A plain text field. Nothing clever; it exists so every field in the app looks the same. */
@Composable
fun TextField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    supporting: String? = null,
    isError: Boolean = false,
    capitalise: KeyboardCapitalization = KeyboardCapitalization.Sentences
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = singleLine,
        isError = isError,
        supportingText = supporting?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(capitalization = capitalise),
        modifier = modifier.fillMaxWidth()
    )
}

/**
 * A number. Whole by default — mileage, a year, an interval — and non-digits simply never arrive.
 *
 * [decimals] lets a single point through, for the handful of fields that are genuinely fractional:
 * a lot size is 0.34 acres and no amount of validation afterwards helps a field whose keyboard has
 * already swallowed the dot.
 */
@Composable
fun NumberField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    isError: Boolean = false,
    decimals: Boolean = false
) {
    OutlinedTextField(
        value = value,
        onValueChange = { text ->
            onChange(if (decimals) digitsAndOnePoint(text) else text.filter { it.isDigit() })
        },
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        supportingText = supporting?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(
            keyboardType = if (decimals) KeyboardType.Decimal else KeyboardType.Number
        ),
        modifier = modifier.fillMaxWidth()
    )
}

/**
 * The digits, and the first separator typed, as a point.
 *
 * Two details it would be easy to get wrong. The point **survives with nothing after it**, because
 * "0." is what "0.34" looks like halfway through typing and a filter that tidied it away would make
 * the field impossible to type a decimal into. And a **comma counts as the separator**: half the
 * world's keyboards offer one there, and dropping it would silently turn 0,34 acres into 34.
 */
internal fun digitsAndOnePoint(text: String): String {
    var pointed = false
    return buildString {
        text.forEach { char ->
            when {
                char.isDigit() -> append(char)
                (char == '.' || char == ',') && !pointed -> {
                    pointed = true
                    append('.')
                }
            }
        }
    }
}

/**
 * An amount of money, held as text while it is being typed and reported in cents.
 *
 * [onChange] is handed null while the text is not yet a number — including while it is empty — so a
 * caller can tell "nothing entered" from "zero", which is the difference between a service that was
 * free and one whose price you have not looked up yet.
 */
@Composable
fun MoneyField(
    label: String,
    text: String,
    onChange: (text: String, cents: Long?) -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null
) {
    val symbol = currencySymbol()
    val parsed = Money.parse(text)
    val bad = text.isNotBlank() && parsed == null
    OutlinedTextField(
        value = text,
        onValueChange = { onChange(it, Money.parse(it)) },
        // The symbol rides in the label rather than as a prefix: a prefix sits in the text box and
        // reads, at a glance, like part of what you typed.
        label = { Text("$label ($symbol)") },
        singleLine = true,
        isError = bad,
        supportingText = when {
            bad -> { { Text("An amount, like 1,234.56") } }
            supporting != null -> { { Text(supporting) } }
            else -> null
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier.fillMaxWidth()
    )
}

/** Today, as the millis these screens store. */
fun todayMillis(): Long = toEpochMillis(LocalDate.now())
