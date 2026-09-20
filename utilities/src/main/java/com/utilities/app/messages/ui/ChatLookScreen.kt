package com.utilities.app.messages.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.utilities.app.data.LookStore
import com.utilities.app.look.UtilityPalette
import com.utilities.app.look.rememberPalette
import com.utilities.app.look.toComposeColor
import com.utilities.app.messages.logic.ChatPalettes
import com.utilities.app.messages.logic.TimestampStyle
import com.utilities.app.ui.LookEditor
import com.utilities.app.ui.SwitchRow
import kotlin.math.roundToInt

/**
 * How the threads look.
 *
 * The top of the screen is the shared [LookEditor] — the same colours, warmth, face and roundness
 * the keyboard has — and the bottom is the handful of things only a conversation has. Written this
 * way round on purpose: somebody who came here to change the colour should not have to scroll past
 * six bubble settings to find it, and somebody who wants the two surfaces to match has one button
 * at the end of the part they have in common.
 */
@Composable
fun ChatLookScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val looks = remember { LookStore.get(context) }
    val chat by looks.chat.collectAsState()
    val base = rememberPalette(chat.look)
    val palette = remember(chat, base) {
        ChatPalettes.resolve(chat, base.surface, base.text, base.accent)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        LookEditor(
            look = chat.look,
            palette = base,
            onChange = { next -> looks.updateChat { it.copy(look = next) } },
            matchLabel = "Use the keyboard's colours",
            onMatch = { looks.matchChatToKeyboard() },
            preview = { BubblePreview(chat.bubbleCornerDp, chat.tails, palette.sent, palette.onSent, palette.received, palette.onReceived, it) }
        )

        Spacer(Modifier.height(24.dp))
        Heading("Bubbles")

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Roundness", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 12.dp))
            Slider(
                value = chat.bubbleCornerDp,
                onValueChange = { value -> looks.updateChat { it.copy(bubbleCornerDp = value) } },
                valueRange = 0f..28f,
                modifier = Modifier.weight(1f)
            )
            Text(
                "${chat.bubbleCornerDp.roundToInt()}dp",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        SwitchRow(
            title = "Tails",
            detail = "The square corner that points a bubble at whoever sent it.",
            checked = chat.tails,
            onChange = { value -> looks.updateChat { it.copy(tails = value) } }
        )
        SwitchRow(
            title = "Avatars",
            detail = "A coloured circle per person, with the colour derived from the number so it " +
                "is the same on every phone.",
            checked = chat.avatars,
            onChange = { value -> looks.updateChat { it.copy(avatars = value) } }
        )

        Spacer(Modifier.height(16.dp))
        Heading("Timestamps")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            TimestampStyle.entries.forEach { style ->
                FilterChip(
                    selected = chat.timestamps == style,
                    onClick = { looks.updateChat { it.copy(timestamps = style) } },
                    label = { Text(style.label, style = MaterialTheme.typography.labelMedium) }
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Heading("Behaviour")
        SwitchRow(
            title = "Return sends",
            detail = "Off by default: on a phone, the return key is where a thumb goes to break a " +
                "line, and the cost of getting this wrong is sending half a sentence.",
            checked = chat.enterSends,
            onChange = { value -> looks.updateChat { it.copy(enterSends = value) } }
        )
        SwitchRow(
            title = "Newest at the top",
            detail = "Against the convention, and genuinely wanted by people who open a long thread " +
                "only ever to read the last message in it.",
            checked = chat.newestFirst,
            onChange = { value -> looks.updateChat { it.copy(newestFirst = value) } }
        )
    }
}

@Composable
private fun BubblePreview(
    corner: Float,
    tails: Boolean,
    sent: Int,
    onSent: Int,
    received: Int,
    onReceived: Int,
    palette: UtilityPalette
) {
    val tail = if (tails) 4.dp else corner.dp
    Column(Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .clip(
                    RoundedCornerShape(
                        topStart = corner.dp,
                        topEnd = corner.dp,
                        bottomStart = tail,
                        bottomEnd = corner.dp
                    )
                )
                .background(received.toComposeColor())
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text("Are we still on for Thursday?", color = onReceived.toComposeColor())
        }
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .align(Alignment.End)
                .clip(
                    RoundedCornerShape(
                        topStart = corner.dp,
                        topEnd = corner.dp,
                        bottomStart = corner.dp,
                        bottomEnd = tail
                    )
                )
                .background(sent.toComposeColor())
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text("We are.", color = onSent.toComposeColor())
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "20:14",
            style = MaterialTheme.typography.labelSmall,
            color = palette.muted.toComposeColor(),
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
    }
}

@Composable
private fun Heading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}
