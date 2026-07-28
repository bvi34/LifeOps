package com.lifeops.app.data.repository

import com.lifeops.app.data.model.BusyBlock

/**
 * Port for queuing/cancelling a busy block's start-of-block reminder, letting [BusyBlockRepository]
 * drive reminders without depending on Android's AlarmManager (or a [android.content.Context])
 * directly. The production implementation is exact-alarm-backed (see the app's
 * AlarmBusyBlockReminderScheduler); unit tests substitute a fake or the no-op below.
 */
interface BusyBlockReminderScheduler {
    /** Enqueue [block]'s next start-of-block reminder, replacing any pending one for that block. */
    fun schedule(block: BusyBlock)

    /** Cancel the pending reminder for the block with [blockId]. */
    fun cancel(blockId: String)
}

/** A do-nothing scheduler — the default when a caller (or test) doesn't care about reminders. */
object NoopBusyBlockReminderScheduler : BusyBlockReminderScheduler {
    override fun schedule(block: BusyBlock) {}
    override fun cancel(blockId: String) {}
}
