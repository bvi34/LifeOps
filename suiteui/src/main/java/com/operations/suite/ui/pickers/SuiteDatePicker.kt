@file:OptIn(ExperimentalMaterial3Api::class)

package com.operations.suite.ui.pickers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.operations.suitekit.SuiteVerdict
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
 *
 * **The calendar offers every day, and the app decides what it thinks of the one you tapped.** Pass
 * a [check] returning a [SuiteVerdict]: `Fine`, a `Note` that is allowed but said out loud, or a
 * `Refused` with the reason. Next Tuesday is a plan in LifeOps and a typo in Health; last Tuesday is
 * a late entry in Health and a closed week in LifeOps. One control, four answers, no forks — and
 * none of them a silently-greyed-out square the person is left to guess about.
 */

/** An app's opinion of a picked day. */
typealias SuiteDateCheck = (LocalDate) -> SuiteVerdict

/**
 * The dialog itself.
 *
 * [onPick] owns closing it. That is deliberate: the two-step "when" picker uses a confirmed date to
 * *advance* rather than to close, and a dialog that dismissed itself on confirm would take the
 * second step down with it. [onDismiss] means cancelled, and only that.
 *
 * [check] is asked about whatever day is currently selected, and its answer is shown under the
 * calendar: a note in the ordinary voice, a refusal in the error one, with confirm disabled until
 * the choice changes. The person sees the rule *and* the reason at the moment it applies.
 */
@Composable
fun SuiteDatePickerDialog(
    initial: LocalDate?,
    onPick: (LocalDate?) -> Unit,
    onDismiss: () -> Unit,
    clearable: Boolean = false,
    confirmLabel: String = "OK",
    check: SuiteDateCheck = { SuiteVerdict.Fine }
) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial?.let { SuiteDates.toPickerMillis(it) }
    )
    val picked = state.selectedDateMillis?.let { SuiteDates.fromPickerMillis(it) }
    val verdict = picked?.let(check) ?: SuiteVerdict.Fine

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Row {
                // Clearing is not a choice the check has an opinion about — there is no date to
                // object to — so it stays available even while confirm is blocked.
                if (clearable) {
                    TextButton(onClick = { onPick(null) }) { Text("Clear") }
                    Spacer(Modifier.width(4.dp))
                }
                TextButton(
                    enabled = verdict.allowed,
                    onClick = { onPick(picked) }
                ) { Text(confirmLabel) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    ) {
        Column {
            DatePicker(state = state)
            SuiteVerdictText(verdict, Modifier.padding(horizontal = 24.dp, bottom = 12.dp))
        }
    }
}

/**
 * A verdict's message, in the voice its severity earns: a refusal in the error colour, a note in the
 * ordinary one. `Fine` draws nothing at all — an empty reassurance is worse than silence, because it
 * trains people to stop reading the line that will one day say something.
 */
@Composable
fun SuiteVerdictText(verdict: SuiteVerdict, modifier: Modifier = Modifier) {
    val line = verdict.text ?: return
    Text(
        line,
        style = MaterialTheme.typography.bodySmall,
        color = if (verdict.allowed) MaterialTheme.colorScheme.onSurfaceVariant
        else MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Start,
        modifier = modifier
    )
}

/** The button shape: an outlined button showing the date, or "Set <label>" when there isn't one. */
@Composable
fun SuiteDateButton(
    label: String,
    date: LocalDate?,
    onDateChange: (LocalDate?) -> Unit,
    modifier: Modifier = Modifier,
    clearable: Boolean = true,
    check: SuiteDateCheck = { SuiteVerdict.Fine },
    display: (LocalDate) -> String = SuiteDates::toIso
) {
    var picking by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        OutlinedButton(onClick = { picking = true }) {
            Icon(Icons.Default.CalendarToday, contentDescription = null, modifier = Modifier.width(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(date?.let(display) ?: "Set $label")
        }
        // The remark outlives the dialog. "Recorded as added late" is not a thing to say once and
        // take away the instant somebody taps OK — it is a fact about the value now sitting there.
        date?.let { SuiteVerdictText(check(it), Modifier.padding(top = 2.dp)) }
    }

    if (picking) {
        SuiteDatePickerDialog(
            initial = date,
            onPick = { picking = false; onDateChange(it) },
            onDismiss = { picking = false },
            clearable = clearable,
            check = check
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
    check: SuiteDateCheck = { SuiteVerdict.Fine }
) {
    SuiteDateButton(
        label = label,
        date = SuiteDates.parseIso(isoDate),
        onDateChange = { onIsoDateChange(it?.let(SuiteDates::toIso)) },
        modifier = modifier,
        clearable = clearable,
        check = check
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
    check: SuiteDateCheck = { SuiteVerdict.Fine },
    display: (LocalDate) -> String = SuiteDates::formatDay
) {
    var picking by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = date?.let(display) ?: placeholder,
                onValueChange = {},
                label = { Text(label) },
                readOnly = true,
                enabled = false,
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            // A disabled text field swallows nothing, so the tap target is a transparent overlay
            // rather than the field itself — which keeps the disabled colours (and so the "not
            // editable here" reading) while still being tappable.
            Box(Modifier.matchParentSize().clickable { picking = true })
        }
        date?.let { SuiteVerdictText(check(it), Modifier.padding(start = 16.dp, top = 2.dp)) }
    }

    if (picking) {
        SuiteDatePickerDialog(
            initial = date,
            onPick = { picking = false; onDateChange(it) },
            onDismiss = { picking = false },
            // "Clear" only makes sense once there is something to clear.
            clearable = clearable && date != null,
            confirmLabel = "Set",
            check = check
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
    check: SuiteDateCheck = { SuiteVerdict.Fine },
    display: (LocalDate) -> String = SuiteDates::formatDay
) {
    SuiteDateField(
        label = label,
        date = millis?.let { SuiteDates.toLocalDate(it) },
        onDateChange = { onMillisChange(it?.let { date -> SuiteDates.toEpochMillis(date) }) },
        modifier = modifier,
        clearable = clearable,
        placeholder = placeholder,
        check = check,
        display = display
    )
}
