package com.people.app.ui.checkin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.people.app.data.model.CheckIn
import com.people.app.ui.common.SectionCard

/**
 * The daily check-in, on the person's page.
 *
 * It answers one question — *has today been recorded?* — and gets out of the way. Everything else
 * (the form, the day being filled in, the history) is on the check-in screen, because this page
 * belongs to who somebody is rather than to what one day of theirs was like.
 */
data class CheckInSectionState(
    val personName: String,
    /** How many questions the form asks. Zero means there is no form yet. */
    val questions: Int,
    val today: CheckIn?,
    /** Days in a row recorded, counting back — see `CheckIns.streak`. */
    val streak: Int
)

@Composable
fun CheckInSection(state: CheckInSectionState, onOpen: () -> Unit) {
    SectionCard(
        title = "Daily check-in",
        trailing = { if (state.streak > 1) Badge { Text("${state.streak}") } }
    ) {
        when {
            state.questions == 0 -> {
                Text(
                    "A few questions you write once and answer each day — what ${state.personName} " +
                        "had for lunch, the activity they enjoyed, how the day went. The form is " +
                        "theirs alone; nobody else's has to look like it.",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "Kept in People and nowhere else — check-ins are not published over the sync seam.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            state.today != null -> {
                Text("Today is recorded.", style = MaterialTheme.typography.bodyMedium)
                Text(
                    state.today.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            else -> {
                Text("Not recorded today.", style = MaterialTheme.typography.bodyMedium)
                Text(
                    buildString {
                        append(state.questions).append(" question")
                        if (state.questions != 1) append("s")
                        // A run that ends yesterday is still a run — today has not been missed until
                        // it is over. See `CheckIns.streak`.
                        if (state.streak > 0) {
                            append(" · ").append(state.streak).append(" day")
                            if (state.streak != 1) append("s")
                            append(" in a row so far")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.questions == 0) {
                Button(onClick = onOpen, modifier = Modifier.fillMaxWidth()) { Text("Set up a check-in") }
            } else {
                Button(onClick = onOpen) {
                    Text(if (state.today == null) "Check in" else "Open today")
                }
                OutlinedButton(onClick = onOpen) { Text("Past days") }
            }
        }
    }
}
