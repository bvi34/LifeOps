package com.lifeops.app.receiver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsMessage
import android.util.Log
import androidx.glance.appwidget.updateAll
import com.lifeops.app.LifeOpsApp
import com.lifeops.app.config.SmsConfig
import com.lifeops.app.data.model.TaskSource
import com.lifeops.app.widget.LifeOpsWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "android.provider.Telephony.SMS_RECEIVED") return
        val pendingResult = goAsync()
        // Short correlation ID: last 4 digits of millis, unique per event within a session.
        val cid = (System.currentTimeMillis() % 10_000).toString().padStart(4, '0')
        CoroutineScope(Dispatchers.IO).launch {
            try {
                processIntent(context, intent, cid)
            } catch (e: Exception) {
                val msg = "[$cid] processing failed: ${e.javaClass.simpleName}: ${e.message}"
                Log.e(TAG, msg, e)
                dbgNotify(context, "SMS Error [$cid]", "${e.javaClass.simpleName}: ${e.message}")
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun processIntent(context: Context, intent: Intent, cid: String) {
        Log.d(TAG, "[$cid] receiver fired")
        dbgNotify(context, "SMS [$cid] fired", "Parsing PDUs...")

        val extras = intent.extras ?: run {
            Log.d(TAG, "[$cid] no extras"); dbgNotify(context, "SMS [$cid] stopped", "No extras in intent"); return
        }
        @Suppress("UNCHECKED_CAST")
        val pdus = extras.get("pdus") as? Array<*> ?: run {
            Log.d(TAG, "[$cid] no pdus"); dbgNotify(context, "SMS [$cid] stopped", "No PDUs in extras"); return
        }
        val format = extras.getString("format") ?: "3gpp"
        val messages = pdus.mapNotNull { pdu ->
            SmsMessage.createFromPdu(pdu as? ByteArray ?: return@mapNotNull null, format)
        }
        if (messages.isEmpty()) {
            Log.d(TAG, "[$cid] no messages decoded"); dbgNotify(context, "SMS [$cid] stopped", "PDU decode yielded 0 messages"); return
        }

        val sender = messages.first().originatingAddress ?: run {
            Log.d(TAG, "[$cid] null originatingAddress"); dbgNotify(context, "SMS [$cid] stopped", "originatingAddress is null"); return
        }
        val body = messages.joinToString("") { it.messageBody }
        Log.d(TAG, "[$cid] from=$sender body=${body.take(60)}")

        val app = context.applicationContext as? LifeOpsApp ?: run {
            Log.e(TAG, "[$cid] applicationContext is not LifeOpsApp"); return
        }
        val wifeNumber = app.preferencesRepository.smsWifeNumber
        if (wifeNumber.isBlank()) {
            Log.d(TAG, "[$cid] no whitelist number configured")
            dbgNotify(context, "SMS [$cid] stopped", "No sender number saved in Settings")
            return
        }
        if (!numbersMatch(sender, wifeNumber)) {
            val detail = "sender=$sender  stored=$wifeNumber"
            Log.d(TAG, "[$cid] number mismatch: $detail")
            dbgNotify(context, "SMS [$cid] wrong number", detail)
            return
        }

        val trimmedBody = body.trim()
        val prefixRegex = Regex("^to\\s*do:\\s*", RegexOption.IGNORE_CASE)
        if (!prefixRegex.containsMatchIn(trimmedBody)) {
            Log.d(TAG, "[$cid] no 'To Do:' prefix — body: ${trimmedBody.take(60)}")
            dbgNotify(context, "SMS [$cid] no prefix", "Body: ${trimmedBody.take(80)}")
            return
        }

        val content = prefixRegex.replace(trimmedBody, "")
        val lines = content.split("\n").map { it.trim() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return

        val title: String
        val notes: List<String>
        if (lines.first().length > SmsConfig.TITLE_LENGTH_THRESHOLD) {
            val name = app.preferencesRepository.smsWifeName.trim()
            title = if (name.isNotBlank()) "${name.take(20).trim()} Task" else "Wife Request"
            notes = listOf(content.trim())
        } else {
            title = lines.first()
            notes = lines.drop(1).map { it.removePrefix("-").trim() }.filter { it.isNotBlank() }
        }

        Log.i(TAG, "[$cid] creating task \"$title\" notes=${notes.size}")
        dbgNotify(context, "SMS [$cid] creating task", "\"$title\"  (${notes.size} notes)")

        val week = app.weekRepository.getOrCreateCurrentWeek()
        app.taskRepository.createTaskWithNotes(
            weekId = week.id,
            title = title,
            notes = notes,
            source = TaskSource.SMS,
            aspectId = SmsConfig.DEFAULT_SMS_ASPECT_ID,
            categoryId = SmsConfig.DEFAULT_SMS_CATEGORY_ID
        )
        Log.i(TAG, "[$cid] task created")
        dbgNotify(context, "SMS [$cid] ✓ task added", "\"$title\"")

        try { LifeOpsWidget().updateAll(context) } catch (_: Exception) {}

        if (SmsConfig.SEND_ACK) {
            try {
                @Suppress("DEPRECATION")
                android.telephony.SmsManager.getDefault().sendTextMessage(sender, null, "Added: $title", null, null)
            } catch (_: Exception) {}
        }
    }

    private fun numbersMatch(a: String, b: String): Boolean {
        val digitsA = a.filter { it.isDigit() }
        val digitsB = b.filter { it.isDigit() }
        if (digitsA.length < 7 || digitsB.length < 7) return false
        val compareLen = minOf(digitsA.length, digitsB.length, 10)
        return digitsA.takeLast(compareLen) == digitsB.takeLast(compareLen)
    }

    private fun dbgNotify(context: Context, title: String, body: String) {
        if (!SmsConfig.DEBUG_NOTIFICATIONS) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(DEBUG_CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(DEBUG_CHANNEL, "SMS Debug", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        nm.notify(
            notifId.incrementAndGet(),
            Notification.Builder(context, DEBUG_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(Notification.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .build()
        )
    }

    companion object {
        private const val TAG = "SmsReceiver"
        private const val DEBUG_CHANNEL = "sms_debug"
        private val notifId = AtomicInteger(8000)
    }
}
