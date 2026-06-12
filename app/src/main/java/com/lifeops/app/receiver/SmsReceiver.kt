package com.lifeops.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsMessage
import com.lifeops.app.LifeOpsApp
import com.lifeops.app.config.SmsConfig
import com.lifeops.app.data.model.TaskSource
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
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun processIntent(context: Context, intent: Intent) {
        val extras = intent.extras ?: return
        @Suppress("UNCHECKED_CAST")
        val pdus = extras.get("pdus") as? Array<*> ?: return
        val format = extras.getString("format") ?: "3gpp"

        val messages = pdus.mapNotNull { pdu ->
            SmsMessage.createFromPdu(pdu as? ByteArray ?: return@mapNotNull null, format)
        }
        if (messages.isEmpty()) return

        val sender = messages.first().originatingAddress ?: return
        val body = messages.joinToString("") { it.messageBody }

        val app = context.applicationContext as? LifeOpsApp ?: return
        val wifeNumber = app.preferencesRepository.smsWifeNumber
        if (wifeNumber.isBlank()) return
        if (!numbersMatch(sender, wifeNumber)) return

        val trimmedBody = body.trim()
        val prefixRegex = Regex("^to\\s*do:\\s*", RegexOption.IGNORE_CASE)
        if (!prefixRegex.containsMatchIn(trimmedBody)) return

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

        val week = app.weekRepository.getOrCreateCurrentWeek()
        app.taskRepository.createTaskWithNotes(
            weekId = week.id,
            title = title,
            notes = notes,
            source = TaskSource.SMS,
            aspectId = SmsConfig.DEFAULT_SMS_ASPECT_ID,
            categoryId = SmsConfig.DEFAULT_SMS_CATEGORY_ID
        )

        if (SmsConfig.SEND_ACK) {
            try {
                @Suppress("DEPRECATION")
                android.telephony.SmsManager.getDefault()
                    .sendTextMessage(sender, null, "Added: $title", null, null)
            } catch (_: Exception) {}
        }
    }

    private fun numbersMatch(a: String, b: String): Boolean {
        val digitsA = a.filter { it.isDigit() }.takeLast(10)
        val digitsB = b.filter { it.isDigit() }.takeLast(10)
        return digitsA.length == 10 && digitsA == digitsB
    }
}
