package com.citation.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.citation.core.speech.NarrationState
import com.citation.core.speech.NarrationStatus
import com.citation.core.speech.SleepTimer
import kotlinx.coroutines.flow.first

/**
 * The reader's bottom bar: where you are in the book, and the controls that move you.
 */

/**
 * The chapter bar, and the one control listening needs on the page.
 *
 * The play button lives here rather than behind the tools menu because that is where a reader
 * already looks to move through the book, and because reading and listening are the same act on the
 * same position — the voice starts at the **top of the page you are looking at**, not at some other
 * place the book remembers. (It does: [ReaderViewModel.readAloud] uses the live position, which both
 * reading modes keep pointed at the first line on screen.)
 */
@Composable
internal fun ReaderBottomBar(
    ordinal: Int,
    count: Int,
    percent: Int?,
    fraction: Float?,
    timeLeft: String?,
    narration: NarrationState,
    canReadAloud: Boolean,
    onPlayPause: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit
) {
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth()) {
            // The bar tracks the whole book by characters. Chapters are not the same size, so a bar
            // that filled by chapter count would lie about how much is left in exactly the books
            // where it matters most.
            LinearProgressIndicator(
                progress = { fraction ?: if (count > 0) (ordinal + 1f) / count else 0f },
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = onPrev, enabled = ordinal > 0) { Text("Previous") }
                if (canReadAloud) {
                    val speaking = narration.status == NarrationStatus.SPEAKING
                    val preparing = narration.status == NarrationStatus.PREPARING
                    IconButton(onClick = onPlayPause) {
                        Icon(
                            imageVector = if (speaking) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            // A neural voice takes a moment to load the first time; saying so beats a
                            // button that looks like it did nothing.
                            contentDescription = when {
                                speaking -> "Pause reading aloud"
                                preparing -> "Loading the voice"
                                else -> "Read aloud from the top of this page"
                            },
                            tint = if (preparing) MaterialTheme.colorScheme.secondary
                            else MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        buildString {
                            percent?.let { append("$it%  ·  ") }
                            append("Chapter ${ordinal + 1} / $count")
                        },
                        fontSize = 12.sp
                    )
                    // Shown only once the pace estimate has earned it; an invented number on the
                    // first page is worse than none, because a reader cannot tell it was invented.
                    // While the voice is going it says so instead — with the sleep timer when one is
                    // armed, which is the number that matters to somebody falling asleep.
                    val listening = narration.isActive
                    when {
                        listening -> Text(
                            SleepTimer.label(narration.sleepRemainingMillis)?.let { "Reading aloud · $it" }
                                ?: "Reading aloud",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        timeLeft != null -> Text(
                            timeLeft, fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
                OutlinedButton(onClick = onNext, enabled = ordinal < count - 1) { Text("Next") }
            }
        }
    }
}
