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

    /**
     * The aspect that reading time earns into at week-close (null = reading rewards are off). Both
     * reading categories (Learning / Fun) fold into this one aspect — the category split is a report
     * dimension, not a separate economy. See [com.lifeops.app.util.ReadingRewards].
     */
    var readingAspectId: String?
        get() = prefs.getString("reading_aspect_id", null)
        set(value) {
            prefs.edit().apply { if (value == null) remove("reading_aspect_id") else putString("reading_aspect_id", value) }.apply()
        }

    /** Resource points earned per engaged hour of reading (flat rate; default 5). */
    var readingPointsPerHour: Int
        get() = prefs.getInt("reading_points_per_hour", com.lifeops.app.util.ReadingRewards.DEFAULT_POINTS_PER_HOUR)
        set(value) { prefs.edit().putInt("reading_points_per_hour", value.coerceAtLeast(0)).apply() }

    /**
     * The highest Citation up-packet version LifeOps has durably ingested. The Citation sync consumer
     * pulls everything above this cursor, then advances it — so each reading-time / note packet is
     * ingested exactly once even though Citation keeps resending unacked packets every round.
     */
    var citationSyncAckedVersion: Long
        get() = prefs.getLong("citation_sync_acked_version", 0L)
        set(value) { prefs.edit().putLong("citation_sync_acked_version", value).apply() }

    var defaultReminderHour: Int
        get() = prefs.getInt("default_reminder_hour", 9).coerceIn(0, 23)
        set(value) { prefs.edit().putInt("default_reminder_hour", value.coerceIn(0, 23)).apply() }

    // Master switch for the wellness pop-ups/notifications (daytime check-ins + morning sleep).
    var wellnessRemindersEnabled: Boolean
        get() = prefs.getBoolean("wellness_reminders_enabled", true)
        set(value) { prefs.edit().putBoolean("wellness_reminders_enabled", value).apply() }

    // Whether the background sleep tracker runs (a foreground service capturing screen/charging
    // events overnight, shown as a low-priority ongoing notification). When off, the morning sleep
    // prompt falls back to the screen-time estimate / hand entry.
    var sleepTrackingEnabled: Boolean
        get() = prefs.getBoolean("sleep_tracking_enabled", true)
        set(value) { prefs.edit().putBoolean("sleep_tracking_enabled", value).apply() }

    // The three daytime check-in slot hours (device-local). Stored as a comma list; always three,
    // clamped 0-23 and de-duplicated/sorted on read so scheduling and report gating stay coherent.
    var wellnessSlotHours: List<Int>
        get() = (prefs.getString("wellness_slot_hours", "10,15,21") ?: "10,15,21")
            .split(",").mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 0..23 }.distinct().sorted()
            .ifEmpty { listOf(10, 15, 21) }
        set(value) {
            val cleaned = value.filter { it in 0..23 }.distinct().sorted().ifEmpty { listOf(10, 15, 21) }
            prefs.edit().putString("wellness_slot_hours", cleaned.joinToString(",")).apply()
        }

    var sameWeekCarryRepairDone: Boolean
        get() = prefs.getBoolean("same_week_carry_repair_done", false)
        set(value) { prefs.edit().putBoolean("same_week_carry_repair_done", value).apply() }

    // Highest week index (DateUtil.weekIndexFor) that catch-up close has sealed. -1 means
    // "never run". Recorded marker of how far the week timeline has been closed; the catch-up
    // loop itself is driven by the open week in the DB, so this stays a durable record.
    var lastClosedWeek: Int
        get() = prefs.getInt("last_closed_week", -1)
        set(value) { prefs.edit().putInt("last_closed_week", value).apply() }

    // One-time backfill of WeekSnapshot.aspectHistory (per-aspect minutes/name/colour) for
    // weeks closed before that column existed, so historical Growth rings are deletion-safe.
    var growthAspectHistoryBackfillDone: Boolean
        get() = prefs.getBoolean("growth_aspect_history_backfill_done", false)
        set(value) { prefs.edit().putBoolean("growth_aspect_history_backfill_done", value).apply() }

    // The week (DateUtil.currentWeekStart().toString()) the last dev/sandbox run was started. The
    // dev run is once a week: a fresh week clears the gate. Empty means "never run".
    var lastDevRunWeek: String
        get() = prefs.getString("last_dev_run_week", "") ?: ""
        set(value) { prefs.edit().putString("last_dev_run_week", value).apply() }

    var savedSortOrder: String
        get() = prefs.getString("sort_order", "DEFAULT") ?: "DEFAULT"
        set(value) { prefs.edit().putString("sort_order", value).apply() }

    // First-run welcome: shown once, then never again.
    var onboardingShown: Boolean
        get() = prefs.getBoolean("onboarding_shown", false)
        set(value) { prefs.edit().putBoolean("onboarding_shown", value).apply() }

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

}
