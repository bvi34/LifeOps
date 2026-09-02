@file:OptIn(ExperimentalMaterial3Api::class)

package com.operations.suite.ui.pickers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.LocalDate

/**
 * The suite's date picker — the only one. Two shapes, one dialog underneath:
 * [SuiteDateButton] for a date that sits in a row of controls, [SuiteDateField] for a date that sits
 * in a form of text fields. Both refuse typing, because a date typed on a phone is how 2025 becomes
 * 2205 and every date in this suite is one something will later do arithmetic against.
 *
 * Callers speak [LocalDate], not millis. The overloads take the two other forms apps actually store
 * — an ISO `"2026-09-02"` string, and local-midnight millis — and convert at the edge, so the
 * UTC-midnight trap in Material's own picker (see [SuiteDates]) is handled once here instead of
 * once per app.
 */

/**
 * The dialog itself.
 *
 * [onPick] owns closing it. That is deliberate: the two-step "when" picker uses a confirmed date to
 * *advance* rather than to close, and a dialog that dismissed itself on confirm would take the
 * second step down with it. [onDismiss] means cancelled, and only that.
 *
 * [notAfter] / [notBefore] bound what the calendar will even offer. A picker that never shows an
 * impossible choice needs no error message afterwards — Health's "you cannot record a temperature
 * that hasn't been taken yet" is a `notAfter = LocalDate.now()` and nothing else.
 */
@Composable
fun SuiteDatePickerDialog(
    initial: LocalDate?,
    onPick: (LocalDate?) -> Unit,
    onDismiss: () -> Unit,
    clearable: Boolean = false,
    confirmLabel: String = "OK",
    notBefore: LocalDate? = null,
    notAfter: LocalDate? = null
) {
    val selectable = remember(notBefore, notAfter) {
        object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                val date = SuiteDates.fromPickerMillis(utcTimeMillis)
                return (notBefore == null || !date.isBefore(notBefore)) &&
                    (notAfter == null || !date.isAfter(notAfter))
            }

            override fun isSelectableYear(year: Int): Boolean =
                (notBefore == null || year >= notBefore.year) && (notAfter == null || year <= notAfter.year)
        }
    }
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial?.let { SuiteDates.toPickerMillis(it) },
        selectableDates = selectable
    )

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Row {
                if (clearable) {
                    TextButton(onClick = { onPick(null) }) { Text("Clear") }
                    Spacer(Modifier.width(4.dp))
                }
                TextButton(onClick = {
                    onPick(state.selectedDateMillis?.let { SuiteDates.fromPickerMillis(it) })
                }) { Text(confirmLabel) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    ) {
        DatePicker(state = state)
    }
}

/** The button shape: an outlined button showing the date, or "Set <label>" when there isn't one. */
@Composable
fun SuiteDateButton(
    label: String,
    date: LocalDate?,
    onDateChange: (LocalDate?) -> Unit,
    modifier: Modifier = Modifier,
    clearable: Boolean = true,
    notBefore: LocalDate? = null,
    notAfter: LocalDate? = null,
    display: (LocalDate) -> String = SuiteDates::toIso
) {
    var picking by remember { mutableStateOf(false) }

    OutlinedButton(onClick = { picking = true }, modifier = modifier) {
        Icon(Icons.Default.CalendarToday, contentDescription = null, modifier = Modifier.width(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(date?.let(display) ?: "Set $label")
    }

    if (picking) {
        SuiteDatePickerDialog(
            initial = date,
            onPick = { picking = false; onDateChange(it) },
            onDismiss = { picking = false },
            clearable = clearable,
            notBefore = notBefore,
            notAfter = notAfter
        )
    }
}

/** The same button, for an app that stores its days as ISO `"2026-09-02"` text. */
@Composable
fun SuiteDateButton(
    label: String,
    isoDate: String?,
    onIsoDateChange: (String?) -> Unit,
    modifier: Modifier = Modifier,
    clearable: Boolean = true,
    notBefore: LocalDate? = null,
    notAfter: LocalDate? = null
) {
    SuiteDateButton(
        label = label,
        date = SuiteDates.parseIso(isoDate),
        onDateChange = { onIsoDateChange(it?.let(SuiteDates::toIso)) },
        modifier = modifier,
        clearable = clearable,
        notBefore = notBefore,
        notAfter = notAfter
    )
}

/**
 * The form shape: a read-only text box with the picker over the top of it, so a date sits in a
 * column of fields without pretending to be typeable.
 */
@Composable
fun SuiteDateField(
    label: String,
    date: LocalDate?,
    onDateChange: (LocalDate?) -> Unit,
    modifier: Modifier = Modifier,
    clearable: Boolean = true,
    placeholder: String = "Not set",
    notBefore: LocalDate? = null,
    notAfter: LocalDate? = null,
    display: (LocalDate) -> String = SuiteDates::formatDay
) {
    var picking by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = date?.let(display) ?: placeholder,
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
        SuiteDatePickerDialog(
            initial = date,
            onPick = { picking = false; onDateChange(it) },
            onDismiss = { picking = false },
            // "Clear" only makes sense once there is something to clear.
            clearable = clearable && date != null,
            confirmLabel = "Set",
            notBefore = notBefore,
            notAfter = notAfter
        )
    }
}

/** The same field, for an app that stores its days as local-midnight millis. */
@Composable
fun SuiteDateField(
    label: String,
    millis: Long?,
    onMillisChange: (Long?) -> Unit,
    modifier: Modifier = Modifier,
    clearable: Boolean = true,
    placeholder: String = "Not set",
    notBefore: LocalDate? = null,
    notAfter: LocalDate? = null,
    display: (LocalDate) -> String = SuiteDates::formatDay
) {
    SuiteDateField(
        label = label,
        date = millis?.let { SuiteDates.toLocalDate(it) },
        onDateChange = { onMillisChange(it?.let { date -> SuiteDates.toEpochMillis(date) }) },
        modifier = modifier,
        clearable = clearable,
        placeholder = placeholder,
        notBefore = notBefore,
        notAfter = notAfter,
        display = display
    )
}
