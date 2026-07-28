package com.lifeops.app.worker

import android.content.Context
import com.lifeops.app.data.model.BusyBlock
import com.lifeops.app.data.repository.BusyBlockReminderScheduler

/**
 * Production [BusyBlockReminderScheduler] backed by [BusyBlockReminderWorker]/WorkManager. Thin
 * adapter: it unpacks the block and forwards to the worker's scheduling entry points, keeping all
 * the WorkManager (and Context) details out of [com.lifeops.app.data.repository.BusyBlockRepository].
 */
class WorkManagerBusyBlockReminderScheduler(
    private val context: Context
) : BusyBlockReminderScheduler {
    override fun schedule(block: BusyBlock) =
        BusyBlockReminderWorker.schedule(
            context,
            block.id,
            block.title,
            block.startMinutes,
            block.endMinutes,
            block.daysMask,
            block.specificDate
        )

    override fun cancel(blockId: String) =
        BusyBlockReminderWorker.cancel(context, blockId)
}
