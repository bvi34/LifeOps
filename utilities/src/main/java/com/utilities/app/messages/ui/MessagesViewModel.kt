package com.utilities.app.messages.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.utilities.app.messages.MessageNotifier
import com.utilities.app.messages.MessageSender
import com.utilities.app.messages.MessageStore
import com.utilities.app.messages.MessagesRole
import com.utilities.app.messages.OutboxStore
import com.utilities.app.messages.logic.ChatMessage
import com.utilities.app.messages.logic.ChatThread
import com.utilities.app.messages.logic.Outbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The threads, and the one open one.
 *
 * ## Reading, not subscribing
 *
 * There is no observer on the message provider and that is deliberate. `ContentObserver` on the
 * SMS store fires for every column change in every thread — a read flag, a delivery report — and
 * each one would cost a full re-read of the conversation list. What actually needs to be live is
 * narrow and known: the screen was opened, a message was sent, or one arrived while this app is the
 * default and therefore *told us itself*. So the refresh is explicit, from those three places, and
 * the list is not recomputed because somebody's carrier updated a timestamp.
 *
 * ## The echoes
 *
 * A thread is the provider's messages with this app's unrecorded sends folded in — see
 * [Outbox] for why those exist at all. Folding happens on every read, and the echoes the store has
 * caught up with are deleted in the same pass, so the bridge takes itself down.
 */
class MessagesViewModel(context: Context) : ViewModel() {

    private val app = context.applicationContext
    private val store = MessageStore(app)
    private val outbox = OutboxStore(app)
    private val sender = MessageSender(app)

    private val _threads = MutableStateFlow<List<ChatThread>>(emptyList())
    val threads: StateFlow<List<ChatThread>> = _threads.asStateFlow()

    private val _open = MutableStateFlow<ThreadView?>(null)
    val open: StateFlow<ThreadView?> = _open.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    /** Whether this app may write to the store — which decides how a send is recorded. */
    val isDefault: Boolean get() = MessagesRole.isDefault(app)

    fun refresh() {
        if (!MessagesRole.canRead(app)) {
            _threads.value = emptyList()
            return
        }
        viewModelScope.launch {
            _loading.value = true
            _threads.value = withContext(Dispatchers.IO) { store.threads() }
            _loading.value = false
            _open.value?.let { openThread(it.thread.id) }
        }
    }

    /**
     * Open a thread.
     *
     * Marking it read happens here rather than on the way out: somebody who opens a thread has read
     * it, and waiting until they leave means a notification that survives being looked at. It is
     * attempted whether or not this app is the default, because the call fails quietly when it may
     * not write and the alternative is a permission check that duplicates the platform's.
     */
    fun openThread(threadId: Long) {
        viewModelScope.launch {
            val view = withContext(Dispatchers.IO) {
                val stored = store.messages(threadId)
                outbox.settle(threadId, stored)
                val pending = outbox.entries(threadId)
                val thread = _threads.value.firstOrNull { it.id == threadId }
                    ?: ChatThread(
                        id = threadId,
                        addresses = stored.map { it.address }.distinct(),
                        title = stored.firstOrNull()?.address?.let { store.displayName(it) }.orEmpty(),
                        snippet = "",
                        at = stored.lastOrNull()?.at ?: 0L,
                        unread = 0
                    )
                store.markRead(threadId)
                ThreadView(thread = thread, messages = Outbox.merge(stored, pending, threadId))
            }
            MessageNotifier(app).clear(threadId)
            _open.value = view
            // The list's unread badge is now wrong for this thread; cheaper to fix in place than to
            // re-read fifty conversations to learn one number.
            _threads.value = _threads.value.map { if (it.id == threadId) it.copy(unread = 0) else it }
        }
    }

    fun closeThread() {
        _open.value = null
    }

    /** Send [body] to whoever the open thread is with. */
    fun send(body: String) {
        val view = _open.value ?: return
        val address = view.thread.addresses.firstOrNull() ?: return
        if (body.isBlank()) return
        if (!MessagesRole.canSend(app)) {
            _status.value = "Sending needs the SMS permission."
            return
        }
        if (view.thread.group) {
            // A group thread over SMS is several one-to-one sends the platform stitches together as
            // MMS, and MMS is the half of this app that is not written. Refusing is the honest
            // answer; quietly sending to the first person in the list would be the worst one.
            _status.value = "Group messages need MMS, which Utilities cannot send yet."
            return
        }
        viewModelScope.launch {
            val threadId = withContext(Dispatchers.IO) { sender.send(address, body, isDefault) }
            if (threadId == null) {
                _status.value = "That message could not be sent."
            } else {
                openThread(threadId)
            }
        }
    }

    /** Start a thread with a number typed in, or one a link arrived with. */
    fun openWith(address: String) {
        viewModelScope.launch {
            val threadId = withContext(Dispatchers.IO) { store.threadFor(address) }
            if (threadId == null) {
                _status.value = "That does not look like a number this phone can text."
            } else {
                openThread(threadId)
            }
        }
    }

    fun clearStatus() {
        _status.value = null
    }

    /** A thread and its messages, resolved together so the screen never has half of one. */
    data class ThreadView(val thread: ChatThread, val messages: List<ChatMessage>)

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MessagesViewModel(context) as T
    }
}
