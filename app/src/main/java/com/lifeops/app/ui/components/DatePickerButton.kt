@file:OptIn(ExperimentalMaterial3Api::class)

package com.lifeops.app.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@Composable
fun DatePickerButton(
    label: String,
    selectedDateStr: String?,
    onDateSelected: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    var showPicker by remember { mutableStateOf(false) }
    val initialMillis = remember(selectedDateStr) { selectedDateStr?.let { dateStrToUtcMillis(it) } }
    val pickerState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)

    OutlinedButton(onClick = { showPicker = true }, modifier = modifier) {
        Icon(Icons.Default.CalendarToday, contentDescription = null, modifier = Modifier.width(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(selectedDateStr ?: "Set $label")
    }

    if (showPicker) {
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                Row {
                    TextButton(onClick = { onDateSelected(null); showPicker = false }) { Text("Clear") }
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = {
                        onDateSelected(pickerState.selectedDateMillis?.let { utcMillisToDateStr(it) })
                        showPicker = false
                    }) { Text("OK") }
                }
            }
        ) {
            DatePicker(state = pickerState)
        }
    }
}

internal fun dateStrToUtcMillis(dateStr: String): Long? = try {
    LocalDate.parse(dateStr).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
} catch (_: Exception) { null }

internal fun utcMillisToDateStr(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString()
