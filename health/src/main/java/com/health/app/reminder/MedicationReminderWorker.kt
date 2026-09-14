package com.health.app.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.health.app.HealthApp
import com.health.app.MainActivity
import com.health.app.data.model.Medication
import com.health.app.data.model.MedicationStatus
import com.health.app.logic.DoseReminder
import com.health.app.logic.ReminderMode
import kotlinx.coroutines.flow.first

/**
 * One medicine's reminder: fire it, then queue the next one.
 *
 * Self-perpetuating one-shot work rather than a periodic worker, the same shape LifeOps' habit and
 * wellness reminders use. Each medicine is enqueued under its own unique work name, so medicines
 * schedule, re-schedule and cancel independently — and a medicine that is deleted takes its pending
 * reminder with it instead of firing about a bottle nobody owns.
 *
 * WorkManager rather than an exact alarm, deliberately. A dose reminder is a "some time around
 * eight" nudge that has to survive a reboot, not a to-the-second alarm; exact alarms are a permission
 * this module would then have to justify, and they are not what this is. The cost is that a
 * reminder can arrive a few minutes late, which is why the when-due path re-checks the dose window
 * before it says anything (see [DoseReminder.shouldFireWhenDue]) rather than trusting the schedule
 * it was queued under.
 *
 * ### What it will not do
 *
 * It never tells anybody to take a medicine. It says the dose *the user wrote down* is due, and taps
 * through to the screen where the live dose window — which may by then say something different,
 * because somebody else in the house gave a dose from their own phone — has the final word. The
 * notification is a memory aid, not an instruction.
 */
class MedicationReminderWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val medicationId = inputData.getString(KEY_MEDICATION_ID) ?: return Result.failure()
        val targetMillis = inputData.getLong(KEY_TARGET_MILLIS, 0L)

        val repository = HealthApp.get(context).repository
        val medication = repository.medications.observeMedications(
            inputData.getString(KEY_PROFILE_ID) ?: return Result.success()
        ).first().firstOrNull { it.id == medicationId }

        // Deleted, paused, or its reminder turned off between being queued and firing. All three
        // mean the same thing: say nothing, and don't queue another.
        if (medication == null || !medication.reminderArmed) return Result.success()

        val status = repository.medications.observeMedicationStatuses(medication.profileId).first()
            .firstOrNull { it.medication.id == medicationId }

        val personName = repository.profiles.observeProfiles().first()
            .firstOrNull { it.id == medication.profileId }
            ?.name

        val shouldNotify = when (medication.reminderMode) {
            ReminderMode.OFF -> false
            ReminderMode.FIXED_TIMES -> true
            ReminderMode.WHEN_DUE -> status != null &&
                DoseReminder.shouldFireWhenDue(status.window, System.currentTimeMillis(), targetMillis)
        }

        if (shouldNotify) notify(medication, status, personName)

        // Queue the next one from here rather than from the scheduler, so the chain keeps itself
        // alive without the app ever being opened.
        MedicationReminderScheduler.schedule(context, medication, status)
        return Result.success()
    }

    private fun notify(medication: Medication, status: MedicationStatus?, personName: String?) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = CHANNEL_DESCRIPTION }
        )

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            medication.id.hashCode(),
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val title = personName?.let { "$it — ${medication.name}" } ?: medication.name
        val body = buildString {
            append(
                medication.doseAmount
                    ?.let { "${trim(it)} ${medication.doseUnit}".trim() }
                    ?.let { "$it is due" }
                    ?: "Due now"
            )
            // The stock warning belongs here and nowhere else in a notification: it is the one fact
            // that changes what you do *before* you walk to the cupboard.
            status?.cabinetStatus?.dosesRemaining?.takeIf { it <= 1 }?.let { left ->
                append(if (left == 0) " · none left in the cabinet" else " · last dose in the cabinet")
            }
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()

        manager.notify(NOTIFICATION_BASE_ID + (medication.id.hashCode() and 0xFFFF), notification)
    }

    private fun trim(value: Double): String =
        if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

    companion object {
        const val CHANNEL_ID = "health_medication_reminders"
        private const val CHANNEL_NAME = "Medication reminders"
        private const val CHANNEL_DESCRIPTION =
            "Nudges for the medicines and dose rules you set up in Health."

        private const val NOTIFICATION_BASE_ID = 81_000

        const val KEY_MEDICATION_ID = "medication_id"
        const val KEY_PROFILE_ID = "profile_id"
        const val KEY_TARGET_MILLIS = "target_millis"

        internal fun inputFor(medicationId: String, profileId: String, targetMillis: Long) =
            workDataOf(
                KEY_MEDICATION_ID to medicationId,
                KEY_PROFILE_ID to profileId,
                KEY_TARGET_MILLIS to targetMillis
            )
    }
}

/**
 * Turns a medicine's reminder setting into queued work, and takes it away again.
 *
 * The Android half of `logic/DoseReminder` — everything about *when* is computed there, against an
 * injected clock and unit-tested; this file only enqueues the instant that came back. That split is
 * what makes the awkward cases (a time that has already passed today, a dose window that moved while
 * the phone was asleep) provable rather than folklore.
 */
object MedicationReminderScheduler {

    private const val WORK_PREFIX = "health_med_reminder_"
    private const val TAG = "health_med_reminder"

    private fun workName(medicationId: String) = "$WORK_PREFIX$medicationId"

    /**
     * Queue [medication]'s next reminder, replacing whatever was pending for it.
     *
     * [status] carries the live dose window and is what a when-due reminder is computed from; pass
     * it when you already have it. Without it, a when-due reminder cannot be placed and is simply
     * cancelled — the next recorded dose re-arms it, which is the only event that could have moved
     * it anyway.
     */
    fun schedule(context: Context, medication: Medication, status: MedicationStatus?) {
        if (!medication.reminderArmed) {
            cancel(context, medication.id)
            return
        }

        val nowMillis = System.currentTimeMillis()
        val target = when (medication.reminderMode) {
            ReminderMode.OFF -> null
            ReminderMode.FIXED_TIMES -> DoseReminder.nextFixedTime(medication.reminderTimes, nowMillis)
            ReminderMode.WHEN_DUE -> status?.window?.let { DoseReminder.nextWhenDue(it, nowMillis) }
        }

        if (target == null) {
            cancel(context, medication.id)
            return
        }

        val request = OneTimeWorkRequestBuilder<MedicationReminderWorker>()
            .setInitialDelay(
                (target - nowMillis).coerceAtLeast(0L),
                java.util.concurrent.TimeUnit.MILLISECONDS
            )
            .setInputData(
                MedicationReminderWorker.inputFor(medication.id, medication.profileId, target)
            )
            .addTag(TAG)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(workName(medication.id), ExistingWorkPolicy.REPLACE, request)
    }

    /** Drop a medicine's pending reminder — turned off, paused, or the medicine itself deleted. */
    fun cancel(context: Context, medicationId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(medicationId))
    }

    /**
     * Re-arm every reminder in the database, discarding what was queued.
     *
     * Needed after a restore, where the database changed underneath a queue that still refers to the
     * medicines of the *previous* database — ids that may no longer exist, and reminders that were
     * never set on this device. Cheap enough to be the safe answer whenever the whole picture might
     * have moved.
     */
    suspend fun rescheduleAll(context: Context) {
        WorkManager.getInstance(context).cancelAllWorkByTag(TAG)
        val repository = HealthApp.get(context).repository
        val profiles = repository.profiles.observeProfiles().first()
        for (profile in profiles) {
            val statuses = repository.medications.observeMedicationStatuses(profile.id).first()
            for (status in statuses) {
                if (status.medication.reminderArmed) schedule(context, status.medication, status)
            }
        }
    }

    /**
     * Re-arm one medicine by id, looking up whatever the scheduler needs.
     *
     * This is what [com.health.app.HealthApp]'s repository hook calls after any edit that can move a
     * reminder — including a dose being recorded, which is exactly what a when-due reminder hangs
     * off. An id that no longer resolves is a deleted medicine, and its pending work is cancelled.
     */
    suspend fun reschedule(context: Context, medicationId: String) {
        val repository = HealthApp.get(context).repository
        val profiles = repository.profiles.observeProfiles().first()
        for (profile in profiles) {
            val status = repository.medications.observeMedicationStatuses(profile.id).first()
                .firstOrNull { it.medication.id == medicationId }
                ?: continue
            schedule(context, status.medication, status)
            return
        }
        cancel(context, medicationId)
    }

}
