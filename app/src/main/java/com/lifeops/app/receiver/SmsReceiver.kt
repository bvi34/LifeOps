package com.lifeops.app.receiver

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

class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "android.provider.Telephony.SMS_RECEIVED") return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                processIntent(context, intent)
            } catch (e: Exception) {
                Log.e(TAG, "SMS processing failed", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun processIntent(context: Context, intent: Intent) {
        val extras = intent.extras ?: run { Log.d(TAG, "no extras"); return }

        @Suppress("UNCHECKED_CAST")
        val pdus = extras.get("pdus") as? Array<*> ?: run { Log.d(TAG, "no pdus"); return }
        val format = extras.getString("format") ?: "3gpp"

        val messages = pdus.mapNotNull { pdu ->
            SmsMessage.createFromPdu(pdu as? ByteArray ?: return@mapNotNull null, format)
        }
        if (messages.isEmpty()) { Log.d(TAG, "no messages"); return }

        val sender = messages.first().originatingAddress ?: run { Log.d(TAG, "null originating address"); return }
        val body = messages.joinToString("") { it.messageBody }
        Log.d(TAG, "SMS from $sender, body preview: ${body.take(60)}")

        val app = context.applicationContext as? LifeOpsApp ?: run { Log.e(TAG, "applicationContext not LifeOpsApp"); return }
        val wifeNumber = app.preferencesRepository.smsWifeNumber
        if (wifeNumber.isBlank()) { Log.d(TAG, "no whitelist number configured"); return }
        if (!numbersMatch(sender, wifeNumber)) {
            Log.d(TAG, "number mismatch: sender=${sender}, stored=${wifeNumber}")
            return
        }

        val trimmedBody = body.trim()
        val prefixRegex = Regex("^to\\s*do:\\s*", RegexOption.IGNORE_CASE)
        if (!prefixRegex.containsMatchIn(trimmedBody)) {
            Log.d(TAG, "body does not start with 'To Do:'")
            return
        }

        val content = prefixRegex.replace(trimmedBody, "")
        val lines = content.split("\n").map { it.trim() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return

        val title: String
        val notes: List<String>
        if (lines.first().length > SmsConfig.TITLE_LENGTH_THRESHOLD) {
            title = "Wife Request"
            notes = listOf(content.trim())
        } else {
            title = lines.first()
            notes = lines.drop(1)
                .map { it.removePrefix("-").trim() }
                .filter { it.isNotBlank() }
        }

        Log.i(TAG, "Creating task: \"$title\" with ${notes.size} notes")
        val week = app.weekRepository.getOrCreateCurrentWeek()
        app.taskRepository.createTaskWithNotes(
            weekId = week.id,
            title = title,
            notes = notes,
            source = TaskSource.SMS,
            aspectId = SmsConfig.DEFAULT_SMS_ASPECT_ID,
            categoryId = SmsConfig.DEFAULT_SMS_CATEGORY_ID
        )
        Log.i(TAG, "Task created successfully")
        try { LifeOpsWidget().updateAll(context) } catch (_: Exception) {}

        if (SmsConfig.SEND_ACK) {
            try {
                @Suppress("DEPRECATION")
                android.telephony.SmsManager.getDefault()
                    .sendTextMessage(sender, null, "Added: $title", null, null)
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

    companion object {
        private const val TAG = "SmsReceiver"
    }
}
