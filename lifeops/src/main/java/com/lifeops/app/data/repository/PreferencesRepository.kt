package com.lifeops.app.data.repository

import android.content.Context
import com.lifeops.app.data.model.CustomPalette
import com.lifeops.app.data.model.ThemePreset
import com.operations.suite.ui.SuiteAppearanceStore

class PreferencesRepository(context: Context) {
    private val prefs = context.getSharedPreferences("lifeops_settings", Context.MODE_PRIVATE)

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

    /**
     * The highest version of a People-seam peer's packets LifeOps has durably taken. Keyed by peer
     * so a third peer joining the folder needs no new preference, no migration, and no code here.
     */
    fun peopleSyncCursor(peer: String): Long = prefs.getLong("people_sync_cursor_$peer", 0L)

    fun setPeopleSyncCursor(peer: String, version: Long) {
        prefs.edit().putLong("people_sync_cursor_$peer", version).apply()
    }

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

    // --- Google Calendar sync (see GoogleCalendarSyncRepository) ---

    /** The device-calendar id (CalendarContract.Calendars._ID) chosen as the sync target. */
    var googleCalendarId: Long?
        get() = prefs.getLong("google_calendar_id", -1L).takeIf { it >= 0L }
        set(value) {
            prefs.edit().apply { if (value == null) remove("google_calendar_id") else putLong("google_calendar_id", value) }.apply()
        }

    /** Display name of the chosen calendar, cached so the settings screen has something to show
     *  before the calendar list reloads. */
    var googleCalendarDisplayName: String?
        get() = prefs.getString("google_calendar_display_name", null)
        set(value) {
            prefs.edit().apply { if (value == null) remove("google_calendar_display_name") else putString("google_calendar_display_name", value) }.apply()
        }

    /** Master switch for the periodic background sync worker; manual "Sync now" ignores this. */
    var googleCalendarSyncEnabled: Boolean
        get() = prefs.getBoolean("google_calendar_sync_enabled", false)
        set(value) { prefs.edit().putBoolean("google_calendar_sync_enabled", value).apply() }

    /** ISO-8601 instant of the last successful sync, for display; null = never synced. */
    var googleCalendarLastSyncedAt: String?
        get() = prefs.getString("google_calendar_last_synced_at", null)
        set(value) {
            prefs.edit().apply { if (value == null) remove("google_calendar_last_synced_at") else putString("google_calendar_last_synced_at", value) }.apply()
        }

    /**
     * Appearance is the *suite's* now, not LifeOps'. The preset, the light/dark mode and the custom
     * palette live in the Operations Sandbox's store so one choice paints every hosted app; these
     * three properties stay here only so LifeOps' own settings screen keeps editing them by their
     * old names. Writing either side is the same write — the sandbox's gear and LifeOps' Appearance
     * card are two doors into one setting.
     *
     * An existing install's values are carried over on first read (see SuiteAppearanceStore), so
     * nobody's theme resets; LifeOps' old `theme_preset` / `dark_mode` / `custom_palette` keys are
     * simply no longer read.
     */
    private val appearanceStore = SuiteAppearanceStore.get(context)

    var themePreset: ThemePreset
        get() = appearanceStore.preset
        set(value) { appearanceStore.preset = value }

    var isDarkMode: Boolean
        get() = appearanceStore.darkMode
        set(value) { appearanceStore.darkMode = value }

    var customPalette: CustomPalette
        get() = appearanceStore.palette
        set(value) { appearanceStore.palette = value }
}
