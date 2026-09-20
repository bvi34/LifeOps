package com.utilities.app.messages.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.utilities.app.look.toComposeColor
import com.utilities.app.messages.logic.ChatLook
import com.utilities.app.messages.logic.ChatMessage
import com.utilities.app.messages.logic.ChatPalette
import com.utilities.app.messages.logic.ChatPalettes
import com.utilities.app.messages.logic.ChatThread
import com.utilities.app.messages.logic.TimestampStyle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The conversation list and the thread, drawn in the household's own colours.
 *
 * Both screens paint themselves with a [ChatPalette] rather than with the Material scheme, and that
 * is the entire point of this half of the app: the threads are not a screen inside Utilities, they
 * are the phone's messaging app, seen beside everybody else's. So the surface is the one chosen on
 * the appearance screen, down to the background behind the list, and nothing here reads
 * `MaterialTheme.colorScheme` for anything but the chrome the shell owns.
 */

@Composable
fun ConversationsScreen(
    threads: List<ChatThread>,
    look: ChatLook,
    palette: ChatPalette,
    onOpen: (Long) -> Unit,
    modifier: Modifier = Modifier,
    empty: String = "No conversations yet."
) {
    val fontScale = look.look.textScale
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(palette.base.surface.toComposeColor())
    ) {
        if (threads.isEmpty()) {
            Text(
                empty,
                style = MaterialTheme.typography.bodyMedium,
                color = palette.base.muted.toComposeColor(),
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(32.dp)
            )
            return@Box
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(threads, key = { it.id }) { thread ->
                ThreadRow(thread = thread, look = look, palette = palette, scale = fontScale) { onOpen(thread.id) }
            }
        }
    }
}

@Composable
private fun ThreadRow(
    thread: ChatThread,
    look: ChatLook,
    palette: ChatPalette,
    scale: Float,
    onOpen: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = (12 + look.look.paddingDp).dp.coerceAtLeast(6.dp))
    ) {
        if (look.avatars) {
            Avatar(
                seed = thread.addresses.firstOrNull().orEmpty(),
                initial = thread.title.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                palette = palette
            )
            Spacer(Modifier.size(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                thread.title,
                style = MaterialTheme.typography.titleSmall.copy(fontSize = 16.sp * scale),
                fontWeight = if (thread.unread > 0) FontWeight.Bold else FontWeight.Medium,
                color = palette.base.text.toComposeColor(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                thread.snippet,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp * scale),
                color = palette.base.muted.toComposeColor(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.size(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                shortTime(thread.at),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp * scale),
                color = palette.base.muted.toComposeColor()
            )
            if (thread.unread > 0) {
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(palette.sent.toComposeColor()),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (thread.unread > 9) "9+" else thread.unread.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.onSent.toComposeColor()
                    )
                }
            }
        }
    }
}

/**
 * One conversation.
 *
 * The list is reversed rather than scrolled to the bottom, which is the difference between a thread
 * that opens *at* the newest message and one that opens at the top and then jumps. `reverseLayout`
 * also makes the keyboard appearing push the conversation up correctly for free.
 */
@Composable
fun ThreadScreen(
    view: MessagesViewModel.ThreadView,
    look: ChatLook,
    palette: ChatPalette,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
    fontFamily: FontFamily = FontFamily.Default
) {
    var draft by remember(view.thread.id) { mutableStateOf("") }
    val listState = rememberLazyListState()

    // A new message should be visible without anybody scrolling. Keyed on the count rather than the
    // list so that a re-read which changes nothing does not yank the view away from somebody who
    // has scrolled up to read something.
    LaunchedEffect(view.messages.size) {
        if (view.messages.isNotEmpty()) listState.animateScrollToItem(0)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(palette.base.surface.toComposeColor())
            .imePadding()
    ) {
        LazyColumn(
            state = listState,
            reverseLayout = !look.newestFirst,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 12.dp,
                vertical = 8.dp
            )
        ) {
            // Always newest-first in the data; `reverseLayout` decides which end of the screen
            // that is. Sorting one way and flipping the layout keeps the neighbour rule below
            // simple: index + 1 is older, whichever direction the thread runs.
            bubbles(view.messages.sortedByDescending { it.at }, look, palette, fontFamily)
        }

        Composer(
            draft = draft,
            look = look,
            palette = palette,
            onDraft = { draft = it },
            onSend = {
                val body = draft
                draft = ""
                onSend(body)
            }
        )
    }
}

/**
 * The bubbles.
 *
 * Written as an extension on the list scope rather than as a composable taking a scope, because the
 * timestamp rule needs each message's *neighbour* — a clock is drawn when a message starts a run
 * after a pause, which cannot be decided one bubble at a time.
 */
private fun androidx.compose.foundation.lazy.LazyListScope.bubbles(
    messages: List<ChatMessage>,
    look: ChatLook,
    palette: ChatPalette,
    fontFamily: FontFamily
) {
    items(messages.size, key = { index -> messages[index].id }) { index ->
        val message = messages[index]
        // The list is newest-first, so the message *before* this one in time is the next index.
        val previous = messages.getOrNull(index + 1)
        val showClock = when (look.timestamps) {
            TimestampStyle.NONE -> false
            TimestampStyle.EVERY -> true
            TimestampStyle.GROUPED -> previous == null || message.at - previous.at > PAUSE_MS
        }
        Bubble(
            message = message,
            look = look,
            palette = palette,
            fontFamily = fontFamily,
            showClock = showClock
        )
    }
}

@Composable
private fun Bubble(
    message: ChatMessage,
    look: ChatLook,
    palette: ChatPalette,
    fontFamily: FontFamily,
    showClock: Boolean
) {
    val scale = look.look.textScale
    val corner = look.bubbleCornerDp.dp
    // The tail is the one corner that stays square, which is how a bubble points at its sender
    // without a second shape being drawn.
    val tail = if (look.tails) 4.dp else corner
    val shape = if (message.outgoing) {
        RoundedCornerShape(topStart = corner, topEnd = corner, bottomStart = corner, bottomEnd = tail)
    } else {
        RoundedCornerShape(topStart = corner, topEnd = corner, bottomStart = tail, bottomEnd = corner)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = (3 + look.look.paddingDp / 2f).dp.coerceAtLeast(1.dp)),
        horizontalAlignment = if (message.outgoing) Alignment.End else Alignment.Start
    ) {
        if (showClock) {
            Text(
                longTime(message.at),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp * scale),
                color = palette.base.muted.toComposeColor(),
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(vertical = 6.dp)
            )
        }
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(shape)
                .background(
                    (if (message.outgoing) palette.sent else palette.received).toComposeColor()
                )
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(
                message.body,
                style = TextStyle(
                    fontSize = 15.sp * scale,
                    fontFamily = fontFamily,
                    color = (if (message.outgoing) palette.onSent else palette.onReceived).toComposeColor()
                )
            )
        }
        if (message.pending || message.failed) {
            Text(
                if (message.failed) "Not sent" else "Sending…",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp * scale),
                color = palette.base.muted.toComposeColor(),
                modifier = Modifier.padding(top = 2.dp, start = 4.dp, end = 4.dp)
            )
        }
    }
}

@Composable
private fun Composer(
    draft: String,
    look: ChatLook,
    palette: ChatPalette,
    onDraft: (String) -> Unit,
    onSend: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.base.surface.toComposeColor())
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = onDraft,
            placeholder = { Text("Message", color = palette.base.muted.toComposeColor()) },
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(look.bubbleCornerDp.dp),
            maxLines = 5,
            textStyle = TextStyle(
                fontSize = 15.sp * look.look.textScale,
                color = palette.base.text.toComposeColor()
            ),
            keyboardOptions = KeyboardOptions(
                imeAction = if (look.enterSends) ImeAction.Send else ImeAction.Default
            ),
            keyboardActions = KeyboardActions(onSend = { onSend() })
        )
        Spacer(Modifier.size(8.dp))
        IconButton(
            onClick = onSend,
            enabled = draft.isNotBlank(),
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(
                    if (draft.isNotBlank()) palette.sent.toComposeColor()
                    else palette.received.toComposeColor()
                )
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Send,
                contentDescription = "Send",
                tint = (if (draft.isNotBlank()) palette.onSent else palette.base.muted).toComposeColor()
            )
        }
    }
}

@Composable
private fun Avatar(seed: String, initial: String, palette: ChatPalette) {
    val colour = remember(seed, palette.base.surface) { ChatPalettes.avatar(seed, palette.base) }
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(colour.toComposeColor()),
        contentAlignment = Alignment.Center
    ) {
        Text(
            initial,
            style = MaterialTheme.typography.titleSmall,
            color = com.utilities.app.look.UtilityPalettes.contrastOn(colour).toComposeColor()
        )
    }
}

/** A gap this long starts a new run, and gets a clock over it. */
private const val PAUSE_MS = 20 * 60 * 1000L

private fun shortTime(at: Long): String {
    if (at <= 0L) return ""
    val now = System.currentTimeMillis()
    val format = if (now - at < 24 * 60 * 60 * 1000L) "HH:mm" else "d MMM"
    return SimpleDateFormat(format, Locale.getDefault()).format(Date(at))
}

private fun longTime(at: Long): String {
    if (at <= 0L) return ""
    return SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(Date(at))
}
