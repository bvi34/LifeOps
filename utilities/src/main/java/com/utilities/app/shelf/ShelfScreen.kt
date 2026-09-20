package com.utilities.app.shelf

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The shelf: what Utilities has taken over, what it has not, and the one thing to do next about
 * each.
 *
 * Every row says the same four things in the same order — what it replaces, what state it is in,
 * what that means in a sentence, and the next step — because the whole app is one idea repeated,
 * and a screen where each takeover invented its own layout would hide that.
 *
 * Opening a takeover's own screen (its settings, its threads) is the row's body; the next step is a
 * button on the end of it. Those are genuinely different actions and the most common mistake here
 * would be to make the whole row do the platform thing: somebody who has already switched the
 * keyboard on taps the row a hundred times more often to change its colour than to be shown the
 * system picker again.
 */
@Composable
fun ShelfScreen(
    statuses: List<TakeoverStatus>,
    onOpen: (Utility) -> Unit,
    onNextStep: (Utility) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            "The parts of this phone that report to somebody else, and what it takes to stop them.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))

        statuses.forEach { status ->
            TakeoverCard(
                status = status,
                onOpen = { onOpen(status.utility) },
                onNextStep = { onNextStep(status.utility) }
            )
            Spacer(Modifier.height(12.dp))
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "More to come. The dialler, the launcher, the clipboard and the share sheet are all " +
                "takeovers Android allows and none of them is wired up yet — each is one entry in " +
                "the shelf and a screen behind it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TakeoverCard(status: TakeoverStatus, onOpen: () -> Unit, onNextStep: () -> Unit) {
    val enabled = status.state != TakeoverState.UNAVAILABLE
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(enabled = enabled, onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = glyph(status.utility),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        status.utility.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        status.utility.blurb,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                StateDot(status.state)
            }

            Spacer(Modifier.height(10.dp))
            Text(status.detail, style = MaterialTheme.typography.bodyMedium)

            Spacer(Modifier.height(6.dp))
            Text(
                status.utility.leak,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (status.nextStep != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onNextStep) { Text(status.nextStep) }
                }
            }
        }
    }
}

/**
 * The state, as a dot.
 *
 * A dot rather than a word, and the words are in the sentence underneath. Three states across two
 * rows need to be comparable at a glance — "is anything half-done?" is the question this screen is
 * opened with — and three coloured labels of different widths are not comparable at a glance.
 */
@Composable
private fun StateDot(state: TakeoverState) {
    val colour = when (state) {
        TakeoverState.ON -> MaterialTheme.colorScheme.primary
        TakeoverState.PARTIAL -> MaterialTheme.colorScheme.tertiary
        TakeoverState.OFF -> MaterialTheme.colorScheme.outline
        TakeoverState.UNAVAILABLE -> Color.Transparent
    }
    Box(
        Modifier
            .size(12.dp)
            .clip(CircleShape)
            .background(colour)
    )
}

private fun glyph(utility: Utility): ImageVector = when (utility) {
    Utility.KEYBOARD -> Icons.Filled.Keyboard
    Utility.MESSAGES -> Icons.AutoMirrored.Filled.Chat
}
