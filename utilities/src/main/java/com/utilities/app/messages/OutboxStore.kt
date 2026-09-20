package com.utilities.app.messages

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.utilities.app.messages.logic.Outbox
import com.utilities.app.messages.logic.OutboxEntry

/**
 * The messages this app sent that the platform's store has not got — see [Outbox] for why that
 * happens at all and why it is temporary.
 *
 * Keyed by thread, in one small JSON document. It is deliberately not a database: the whole thing
 * is a handful of rows that exist for seconds at a time on a phone where this app is the default,
 * and for as long as a day on one where it is not. A Room schema, a migration and a DAO for that
 * would be machinery outliving its subject.
 *
 * Not carried by the backup, and that is the right call rather than an oversight: an echo is a
 * *claim that a message is missing from the store*, and on a new phone, restored from an archive,
 * every one of those claims is wrong.
 */
class OutboxStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    /** Everything queued for [threadId], oldest first, with anything stale already dropped. */
    fun entries(threadId: Long, now: Long = System.currentTimeMillis()): List<OutboxEntry> {
        val live = Outbox.live(read(threadId), now)
        if (live.size != read(threadId).size) write(threadId, live)
        return live.sortedBy { it.at }
    }

    fun add(threadId: Long, entry: OutboxEntry) {
        write(threadId, read(threadId) + entry)
    }

    /** Mark the most recent echo of this body as failed, so the bubble can say so. */
    fun markFailed(threadId: Long, body: String) {
        val current = read(threadId)
        val index = current.indexOfLast { it.body == body && !it.failed }
        if (index < 0) return
        write(threadId, current.toMutableList().also { it[index] = it[index].copy(failed = true) })
    }

    /** Drop the echoes the provider has caught up with. Called whenever a thread is read. */
    fun settle(threadId: Long, stored: List<com.utilities.app.messages.logic.ChatMessage>) {
        val current = read(threadId)
        if (current.isEmpty()) return
        val done = Outbox.settled(stored, current)
        if (done.isEmpty()) return
        write(threadId, current - done.toSet())
    }

    /** Forget everything. What the shelf does when the Messages takeover is switched off. */
    fun clear() = prefs.edit().clear().apply()

    private fun read(threadId: Long): List<OutboxEntry> {
        val raw = prefs.getString(key(threadId), null) ?: return emptyList()
        return runCatching {
            gson.fromJson<List<OutboxEntry>>(raw, TYPE) ?: emptyList()
        }.getOrDefault(emptyList())
    }

    private fun write(threadId: Long, entries: List<OutboxEntry>) {
        val editor = prefs.edit()
        if (entries.isEmpty()) editor.remove(key(threadId)) else editor.putString(key(threadId), gson.toJson(entries))
        editor.apply()
    }

    private fun key(threadId: Long) = "thread_$threadId"

    private companion object {
        /**
         * Named so it does *not* start with the prefix the backup contributor sweeps. See the class
         * note: an echo restored onto a new phone is a claim that is false by the time it arrives.
         */
        const val FILE_NAME = "outbox_utilities"

        val TYPE = object : TypeToken<List<OutboxEntry>>() {}.type
    }
}
