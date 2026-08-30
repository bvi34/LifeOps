package com.maintenance.app.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import com.maintenance.app.logic.Money
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

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

/** A whole number — mileage, a year, an interval. Non-digits simply never arrive. */
@Composable
fun NumberField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    isError: Boolean = false
) {
    OutlinedTextField(
        value = value,
        onValueChange = { text -> onChange(text.filter { it.isDigit() }) },
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        supportingText = supporting?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier.fillMaxWidth()
    )
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

/**
 * A date, chosen from the platform picker rather than typed.
 *
 * The field is a read-only text box with the picker over the top of it: typing dates on a phone is
 * how you end up with 2025 written as 2205, and every date in this app is one somebody will later
 * do arithmetic against.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(
    label: String,
    value: Long?,
    onChange: (Long?) -> Unit,
    modifier: Modifier = Modifier,
    clearable: Boolean = true,
    placeholder: String = "Not set"
) {
    var picking by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value?.let { formatDay(it) } ?: placeholder,
            onValueChange = {},
            label = { Text(label) },
            readOnly = true,
            enabled = false,
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        // A disabled text field swallows nothing, so the tap target is a transparent overlay rather
        // than the field itself — which keeps the disabled colours (and so the "not editable here"
        // reading) while still being tappable.
        Box(Modifier.matchParentSize().clickable { picking = true })
    }

    if (picking) {
        val state = rememberDatePickerState(initialSelectedDateMillis = value)
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        // The picker works in UTC midnight; the calendar date is what was meant, so
                        // it is re-anchored to local midnight before it goes anywhere near a due date.
                        onChange(
                            state.selectedDateMillis?.let { millis ->
                                val date = Instant.ofEpochMilli(millis).atZone(ZoneId.of("UTC")).toLocalDate()
                                toEpochMillis(date)
                            }
                        )
                        picking = false
                    }
                ) { Text("Set") }
            },
            dismissButton = {
                if (clearable && value != null) {
                    TextButton(onClick = { onChange(null); picking = false }) { Text("Clear") }
                } else {
                    TextButton(onClick = { picking = false }) { Text("Cancel") }
                }
            }
        ) {
            DatePicker(state = state)
        }
    }
}

/** Today, as the millis these screens store. */
fun todayMillis(): Long = toEpochMillis(LocalDate.now())
