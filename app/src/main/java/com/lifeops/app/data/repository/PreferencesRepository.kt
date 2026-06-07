package com.lifeops.app.data.repository

import android.content.Context

class PreferencesRepository(context: Context) {
    private val prefs = context.getSharedPreferences("lifeops_settings", Context.MODE_PRIVATE)

    var defaultReminderHour: Int
        get() = prefs.getInt("default_reminder_hour", 9)
        set(value) { prefs.edit().putInt("default_reminder_hour", value).apply() }
}
