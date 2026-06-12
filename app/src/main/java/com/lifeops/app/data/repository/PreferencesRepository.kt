package com.lifeops.app.data.repository

import android.content.Context

class PreferencesRepository(context: Context) {
    private val prefs = context.getSharedPreferences("lifeops_settings", Context.MODE_PRIVATE)

    var defaultReminderHour: Int
        get() = prefs.getInt("default_reminder_hour", 9).coerceIn(0, 23)
        set(value) { prefs.edit().putInt("default_reminder_hour", value.coerceIn(0, 23)).apply() }

    var sameWeekCarryRepairDone: Boolean
        get() = prefs.getBoolean("same_week_carry_repair_done", false)
        set(value) { prefs.edit().putBoolean("same_week_carry_repair_done", value).apply() }

    var savedSortOrder: String
        get() = prefs.getString("sort_order", "DEFAULT") ?: "DEFAULT"
        set(value) { prefs.edit().putString("sort_order", value).apply() }
}
