package com.lifeops.app.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// Schema migrations 34 → 44.
//
// Grouped by version range rather than by subject: a migration belongs to the moment the
// schema changed, and that moment never moves. Splitting them any other way would mean
// hunting through several files to answer "what did version 35 actually do".

internal val MIGRATION_34_35 = object : Migration(34, 35) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Wellness check-ins: the daytime energy/sensory pop-ups and the morning sleep report share
        // one standalone log table, no FK (same shape as game_scores). Nullable columns carry no SQL
        // DEFAULT — the entity has no @ColumnInfo(defaultValue), so Room's generated schema has none
        // and this CREATE must match exactly or startup validation fails.
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS wellness_checkins (
                id TEXT NOT NULL PRIMARY KEY,
                kind TEXT NOT NULL,
                recordedAt TEXT NOT NULL,
                weekKey INTEGER NOT NULL,
                dayKey TEXT NOT NULL,
                energy INTEGER,
                sensory INTEGER,
                tired INTEGER,
                sleepMinutes INTEGER,
                note TEXT
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS index_wellness_checkins_weekKey ON wellness_checkins(weekKey)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_wellness_checkins_recordedAt ON wellness_checkins(recordedAt)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_wellness_checkins_dayKey ON wellness_checkins(dayKey)")
    }
}

internal val MIGRATION_35_36 = object : Migration(35, 36) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Recurrence cadence. recurrenceIntervalWeeks carries a NOT NULL DEFAULT 1 to match the
        // entity's @ColumnInfo(defaultValue = "1") — existing recurring tasks stay weekly.
        // recurrenceDayOfMonth is nullable with no default (monthly-by-date mode; null = week-interval).
        db.execSQL("ALTER TABLE tasks ADD COLUMN recurrenceIntervalWeeks INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE tasks ADD COLUMN recurrenceDayOfMonth INTEGER")
    }
}

internal val MIGRATION_36_37 = object : Migration(36, 37) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Task image attachments: base64 JPEG stored inline (no FK-less file paths), cascade-deleted
        // with the task. caption is nullable with no default, matching the entity. This CREATE must
        // mirror Room's generated schema exactly or startup validation fails.
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS task_attachments (
                id TEXT NOT NULL PRIMARY KEY,
                taskId TEXT NOT NULL,
                imageData TEXT NOT NULL,
                caption TEXT,
                createdAt TEXT NOT NULL,
                FOREIGN KEY(taskId) REFERENCES tasks(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS index_task_attachments_taskId ON task_attachments(taskId)")
    }
}

internal val MIGRATION_37_38 = object : Migration(37, 38) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // In-app free/busy blocks. personId is a nullable FK to persons (cascade); NULL = the
        // user's own schedule. This CREATE must mirror Room's generated schema exactly.
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS busy_blocks (
                id TEXT NOT NULL PRIMARY KEY,
                title TEXT NOT NULL,
                startMinutes INTEGER NOT NULL,
                endMinutes INTEGER NOT NULL,
                daysMask INTEGER NOT NULL,
                specificDate TEXT,
                personId TEXT,
                createdAt TEXT NOT NULL,
                FOREIGN KEY(personId) REFERENCES persons(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS index_busy_blocks_personId ON busy_blocks(personId)")
    }
}

internal val MIGRATION_38_39 = object : Migration(38, 39) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Counters gain a habit flag and an optional per-habit daily reminder hour. isHabit is
        // NOT NULL with DEFAULT 0 (SQLite requires a default to ALTER-add a NOT NULL column to a
        // populated table) and the entity carries a matching @ColumnInfo(defaultValue = "0") so
        // Room's schema validation passes. reminderHour is nullable — no default, and null means
        // "no reminder". Purely additive; existing counters stay non-habit with no reminder.
        db.execSQL("ALTER TABLE counters ADD COLUMN isHabit INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE counters ADD COLUMN reminderHour INTEGER")
    }
}

internal val MIGRATION_39_40 = object : Migration(39, 40) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Sleep-tracking foundation. `phone_activity_events` is the raw device stream (screen on/off,
        // charging) the overnight reconstruction reads back — standalone log table, no FK, same shape
        // as counter_events; no SQL DEFAULTs since the entity carries no @ColumnInfo(defaultValue), so
        // this CREATE must match Room's generated schema exactly.
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS phone_activity_events (
                id TEXT NOT NULL PRIMARY KEY,
                type TEXT NOT NULL,
                occurredAt INTEGER NOT NULL,
                dayKey TEXT NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS index_phone_activity_events_occurredAt ON phone_activity_events(occurredAt)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_phone_activity_events_dayKey ON phone_activity_events(dayKey)")

        // wellness_checkins gains the rest of the overnight reconstruction alongside the existing
        // sleepMinutes. All nullable with no default — SLEEP rows only, null when hand-entered.
        db.execSQL("ALTER TABLE wellness_checkins ADD COLUMN sleepBedtime TEXT")
        db.execSQL("ALTER TABLE wellness_checkins ADD COLUMN sleepWakeTime TEXT")
        db.execSQL("ALTER TABLE wellness_checkins ADD COLUMN sleepInterruptions INTEGER")
        db.execSQL("ALTER TABLE wellness_checkins ADD COLUMN longestSleepMinutes INTEGER")
    }
}

internal val MIGRATION_40_41 = object : Migration(40, 41) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Counter ticks gain the weather conditions captured when they were logged, sitting
        // alongside the existing occurredAt time. All nullable with no SQL DEFAULT — the entity
        // carries no @ColumnInfo(defaultValue) for these, so Room's generated schema has none and
        // these ALTERs must match exactly. Purely additive; existing events keep null weather.
        db.execSQL("ALTER TABLE counter_events ADD COLUMN weatherTempF INTEGER")
        db.execSQL("ALTER TABLE counter_events ADD COLUMN weatherFeelsLikeF INTEGER")
        db.execSQL("ALTER TABLE counter_events ADD COLUMN weatherHumidityPct INTEGER")
        db.execSQL("ALTER TABLE counter_events ADD COLUMN weatherWindMph INTEGER")
        db.execSQL("ALTER TABLE counter_events ADD COLUMN weatherConditions TEXT")
        db.execSQL("ALTER TABLE counter_events ADD COLUMN weatherLocationName TEXT")
        db.execSQL("ALTER TABLE counter_events ADD COLUMN weatherObservedAt TEXT")
    }
}

internal val MIGRATION_41_42 = object : Migration(41, 42) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Store unlocks (DESIGN.md §9): one row per permanently-bought store item, keyed by its
        // catalog id. Standalone log table, no FK — same shape as game_scores. No SQL DEFAULTs (the
        // entity carries no @ColumnInfo defaults), so this CREATE must match Room's schema exactly.
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS game_unlocks (
                id TEXT NOT NULL PRIMARY KEY,
                unlockedAt TEXT NOT NULL
            )
        """.trimIndent())
    }
}

internal val MIGRATION_42_43 = object : Migration(42, 43) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Opt-in start-of-block reminder on busy blocks. NOT NULL DEFAULT 0 matches the entity's
        // @ColumnInfo(defaultValue = "0"); purely additive, existing blocks stay reminder-off.
        db.execSQL("ALTER TABLE busy_blocks ADD COLUMN reminderEnabled INTEGER NOT NULL DEFAULT 0")
    }
}

internal val MIGRATION_43_44 = object : Migration(43, 44) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Book provenance from Citation reading telemetry: source kind + derived category. Both
        // nullable (existing LifeOps-created books have neither); purely additive.
        db.execSQL("ALTER TABLE books ADD COLUMN sourceType TEXT")
        db.execSQL("ALTER TABLE books ADD COLUMN category TEXT")
    }
}
