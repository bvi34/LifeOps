package com.lifeops.app.worker

import android.content.Context
import com.lifeops.app.data.repository.HabitReminderScheduler

/**
 * Production [HabitReminderScheduler] backed by [HabitReminderWorker]/WorkManager. Thin adapter: it
 * just forwards to the worker's scheduling entry points, keeping all the WorkManager (and Context)
 * details out of [com.lifeops.app.data.repository.CounterRepository].
 */
class WorkManagerHabitReminderScheduler(
    private val context: Context
) : HabitReminderScheduler {
    override fun schedule(counterId: String, name: String, hour: Int) =
        HabitReminderWorker.schedule(context, counterId, name, hour)

    override fun cancel(counterId: String) =
        HabitReminderWorker.cancel(context, counterId)

    override fun cancelAll() =
        HabitReminderWorker.cancelAll(context)
}
