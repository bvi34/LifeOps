package com.health.app.ui.common

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.health.app.data.repository.RestorableDelete
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * How Health offers a delete back.
 *
 * Every delete in this app used to fire on one tap and take a record nobody could reconstruct with
 * it. The two answers to that are asking first and offering it back, and which one a delete gets is
 * decided by whether it can be put back *exactly* — see [RestorableDelete] for that line, and
 * [ConfirmDeleteDialog] for the deletes on the other side of it.
 *
 * Offering it back is the better answer wherever it is available, because it costs the common case
 * nothing. A household deleting a mis-typed reading taps once and moves on; the one who deleted the
 * wrong row gets a way out. A confirm dialog charges every one of them for the mistake of a few.
 */

/** One offer to put back something just deleted: what it was, and how to restore it. */
data class UndoOffer(val message: String, val restore: RestorableDelete)

/**
 * The undo offers one screen makes. Held by a view model, collected by [UndoHost].
 *
 * A buffer rather than a single value, because deleting three rows in a row is a normal thing to do
 * and the second one must not throw away the first one's way back.
 */
class UndoOffers {

    private val _offers = MutableSharedFlow<UndoOffer>(extraBufferCapacity = 4)

    val offers: SharedFlow<UndoOffer> = _offers.asSharedFlow()

    /**
     * Offer the delete back, if it was one that can be put back.
     *
     * A null [restore] means the row was already gone — nothing was deleted, so there is nothing to
     * offer and nothing to say.
     */
    fun offer(message: String, restore: RestorableDelete?) {
        restore?.let { _offers.tryEmit(UndoOffer(message, it)) }
    }
}

/**
 * Show each offer as a snackbar with an Undo action, and restore the ones taken up.
 *
 * [SnackbarDuration.Long] deliberately: the default four seconds is tuned for "message sent", and
 * this is the window in which somebody realises they have just deleted the wrong medicine. Every
 * screen that deletes anything hosts one of these next to its `Scaffold`.
 */
@Composable
fun UndoHost(
    offers: Flow<UndoOffer>,
    host: SnackbarHostState,
    onUndo: (UndoOffer) -> Unit
) {
    LaunchedEffect(offers, host) {
        offers.collect { offer ->
            val result = host.showSnackbar(
                message = offer.message,
                actionLabel = "Undo",
                withDismissAction = true,
                duration = SnackbarDuration.Long
            )
            if (result == SnackbarResult.ActionPerformed) onUndo(offer)
        }
    }
}
