package com.lifeops.app.config

object SmsConfig {
    const val TITLE_LENGTH_THRESHOLD = 80
    val DEFAULT_SMS_ASPECT_ID: String? = null
    val DEFAULT_SMS_CATEGORY_ID: String? = null
    const val SEND_ACK = false

    // Post a notification at each processing gate so you can debug without adb.
    // Flip to false once SMS ingestion is confirmed working.
    const val DEBUG_NOTIFICATIONS = true
}
