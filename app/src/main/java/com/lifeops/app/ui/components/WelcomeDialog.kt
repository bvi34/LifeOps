package com.lifeops.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private data class WelcomeStep(val title: String, val body: String)

private val welcomeSteps = listOf(
    WelcomeStep(
        "Welcome to LifeOps",
        "A weekly operations log for your life. Plan a week, do the work, log the hours, close the week. It keeps an honest record — not of what you meant to do, but of what you did."
    ),
    WelcomeStep(
        "This Week",
        "Add tasks, set a priority and an estimate, then log time as you work — timer, Pomodoro, or by hand. Logged time is the currency here: it powers your scores and your rings."
    ),
    WelcomeStep(
        "Close the week",
        "When the week's done, close it. LifeOps snapshots it, carries forward what you keep, and starts the next week. Completed work earns resources you can track in Reports."
    ),
    WelcomeStep(
        "The Growth Record",
        "Each closed week becomes one permanent ring. Effort shows as colour; a skipped week leaves a grey scar. Once drawn, a ring never changes — it can't be faked or back-dated."
    ),
    WelcomeStep(
        "Make it yours",
        "Set up your aspects, theme, and backups in Settings. Everything stays on your device, so back up regularly. That's the tour — now go log a week."
    )
)

/** One-time first-run welcome. A short, swipe-free, stepped intro to the whole app. */
@Composable
fun WelcomeDialog(onDismiss: () -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    val current = welcomeSteps[step]
    val isLast = step == welcomeSteps.lastIndex

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(current.title, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(current.body, style = MaterialTheme.typography.bodyMedium)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    welcomeSteps.indices.forEach { i ->
                        val active = i == step
                        Box(
                            modifier = Modifier
                                .size(if (active) 8.dp else 6.dp)
                                .background(
                                    if (active) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                                    CircleShape
                                )
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (isLast) onDismiss() else step++ }) {
                Text(if (isLast) "Get started" else "Next")
            }
        },
        dismissButton = {
            if (step > 0) TextButton(onClick = { step-- }) { Text("Back") }
            else TextButton(onClick = onDismiss) { Text("Skip") }
        }
    )
}
