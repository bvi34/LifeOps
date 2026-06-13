package com.lifeops.app.data.repository

import android.content.Context
import com.google.gson.Gson
import com.lifeops.app.data.model.CustomPalette
import com.lifeops.app.data.model.ThemePreset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PreferencesRepository(context: Context) {
    private val prefs = context.getSharedPreferences("lifeops_settings", Context.MODE_PRIVATE)
    private val gson = Gson()

    var defaultReminderHour: Int
        get() = prefs.getInt("default_reminder_hour", 9).coerceIn(0, 23)
        set(value) { prefs.edit().putInt("default_reminder_hour", value.coerceIn(0, 23)).apply() }

    var sameWeekCarryRepairDone: Boolean
        get() = prefs.getBoolean("same_week_carry_repair_done", false)
        set(value) { prefs.edit().putBoolean("same_week_carry_repair_done", value).apply() }

    var savedSortOrder: String
        get() = prefs.getString("sort_order", "DEFAULT") ?: "DEFAULT"
        set(value) { prefs.edit().putString("sort_order", value).apply() }

    private val _themePresetFlow = MutableStateFlow(
        ThemePreset.from(prefs.getString("theme_preset", ThemePreset.DEFAULT.name) ?: ThemePreset.DEFAULT.name)
    )
    val themePresetFlow: StateFlow<ThemePreset> = _themePresetFlow.asStateFlow()

    var themePreset: ThemePreset
        get() = _themePresetFlow.value
        set(value) {
            prefs.edit().putString("theme_preset", value.name).apply()
            _themePresetFlow.value = value
        }

    private val _darkModeFlow = MutableStateFlow(prefs.getBoolean("dark_mode", true))
    val darkModeFlow: StateFlow<Boolean> = _darkModeFlow.asStateFlow()

    var isDarkMode: Boolean
        get() = _darkModeFlow.value
        set(value) {
            prefs.edit().putBoolean("dark_mode", value).apply()
            _darkModeFlow.value = value
        }

    private val _customPaletteFlow = MutableStateFlow(
        try {
            val json = prefs.getString("custom_palette", null)
            if (json != null) gson.fromJson(json, CustomPalette::class.java) else CustomPalette()
        } catch (_: Exception) { CustomPalette() }
    )
    val customPaletteFlow: StateFlow<CustomPalette> = _customPaletteFlow.asStateFlow()

    var customPalette: CustomPalette
        get() = _customPaletteFlow.value
        set(value) {
            prefs.edit().putString("custom_palette", gson.toJson(value)).apply()
            _customPaletteFlow.value = value
        }

    var smsWifeNumber: String
        get() = prefs.getString("sms_wife_number", "") ?: ""
        set(value) { prefs.edit().putString("sms_wife_number", value.trim()).apply() }

    // Display name of the picked contact, used for long-message task titles.
    // Empty when the number was entered manually rather than chosen from contacts.
    var smsWifeName: String
        get() = prefs.getString("sms_wife_name", "") ?: ""
        set(value) { prefs.edit().putString("sms_wife_name", value.trim()).apply() }
}
