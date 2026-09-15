package com.lifeops.app.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// Schema migrations 24 → 34.
//
// Grouped by version range rather than by subject: a migration belongs to the moment the
// schema changed, and that moment never moves. Splitting them any other way would mean
// hunting through several files to answer "what did version 25 actually do".

internal val MIGRATION_24_25 = object : Migration(24, 25) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Collection: recipe library (reusing existing recipes table), books to read, and
        // long-form future-project notes. All new tables, no existing schema touched.
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS books (
                id TEXT NOT NULL PRIMARY KEY,
                title TEXT NOT NULL,
                author TEXT,
                status TEXT NOT NULL,
                createdAt TEXT NOT NULL,
                completedAt TEXT
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS book_notes (
                id TEXT NOT NULL PRIMARY KEY,
                bookId TEXT NOT NULL,
                content TEXT NOT NULL,
                createdAt TEXT NOT NULL,
                FOREIGN KEY(bookId) REFERENCES books(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS index_book_notes_bookId ON book_notes(bookId)")

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS book_time_entries (
                id TEXT NOT NULL PRIMARY KEY,
                bookId TEXT NOT NULL,
                durationMinutes INTEGER NOT NULL,
                note TEXT,
                recordedAt TEXT NOT NULL,
                FOREIGN KEY(bookId) REFERENCES books(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS index_book_time_entries_bookId ON book_time_entries(bookId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_book_time_entries_recordedAt ON book_time_entries(recordedAt)")

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS future_projects (
                id TEXT NOT NULL PRIMARY KEY,
                title TEXT NOT NULL,
                content TEXT NOT NULL,
                createdAt TEXT NOT NULL,
                updatedAt TEXT NOT NULL
            )
        """.trimIndent())
    }
}

internal val MIGRATION_25_26 = object : Migration(25, 26) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Future projects move from one long-form content blob to posted note segments, the
        // same shape as task_notes. Each project's existing content is folded into a single
        // "catch-up" note stamped with the project's updatedAt; the deterministic '-catchup'
        // id keeps re-runs (and legacy backup restores, see BackupRepository) from duplicating
        // it. The content column stays behind, emptied, purely for schema/backup compatibility.
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS future_project_notes (
                id TEXT NOT NULL PRIMARY KEY,
                projectId TEXT NOT NULL,
                content TEXT NOT NULL,
                createdAt TEXT NOT NULL,
                FOREIGN KEY(projectId) REFERENCES future_projects(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS index_future_project_notes_projectId ON future_project_notes(projectId)")
        db.execSQL("""
            INSERT OR REPLACE INTO future_project_notes (id, projectId, content, createdAt)
            SELECT id || '-catchup', id, content, updatedAt FROM future_projects WHERE TRIM(content) <> ''
        """.trimIndent())
        db.execSQL("UPDATE future_projects SET content = ''")
    }
}

internal val MIGRATION_26_27 = object : Migration(26, 27) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Future projects gain an archive lifecycle mirroring projects.status: 'active'
        // ideas can be archived, or promoted into a real project and archived alongside.
        db.execSQL("ALTER TABLE future_projects ADD COLUMN status TEXT NOT NULL DEFAULT 'active'")
    }
}

internal val MIGRATION_27_28 = object : Migration(27, 28) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Promoted projects remember which future project they came from, so the project
        // detail can surface the original brainstorming notes.
        db.execSQL("ALTER TABLE projects ADD COLUMN sourceFutureProjectId TEXT")
    }
}

internal val MIGRATION_28_29 = object : Migration(28, 29) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Phase 1 weather foundation: a source-agnostic local cache so opening LifeOps never
        // waits on the network. Three new standalone tables — no existing schema is touched.
        // NOTE: no SQL DEFAULTs. The entities' Kotlin `= 0` defaults are NOT @ColumnInfo
        // defaults, so Room's generated schema has none; the CREATE must match exactly (same
        // rule the counters migration 18_19 calls out) or startup validation fails.
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS weather_locations (
                id TEXT NOT NULL PRIMARY KEY,
                latitude REAL NOT NULL,
                longitude REAL NOT NULL,
                name TEXT NOT NULL,
                sortOrder INTEGER NOT NULL,
                createdAt TEXT NOT NULL
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS weather_snapshots (
                id TEXT NOT NULL PRIMARY KEY,
                locationId TEXT NOT NULL,
                fetchedAt TEXT NOT NULL,
                observedAt TEXT NOT NULL,
                temperatureF INTEGER NOT NULL,
                feelsLikeF INTEGER NOT NULL,
                humidityPct INTEGER,
                windSpeedMph INTEGER NOT NULL,
                windDirection TEXT,
                windGustMph INTEGER,
                uvIndex INTEGER,
                precipitationProbabilityPct INTEGER,
                shortForecast TEXT NOT NULL,
                hourlyJson TEXT NOT NULL,
                dailyJson TEXT NOT NULL,
                FOREIGN KEY(locationId) REFERENCES weather_locations(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS index_weather_snapshots_locationId ON weather_snapshots(locationId)")

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS weather_alerts (
                id TEXT NOT NULL PRIMARY KEY,
                locationId TEXT NOT NULL,
                event TEXT NOT NULL,
                severity TEXT NOT NULL,
                headline TEXT,
                description TEXT,
                instruction TEXT,
                onset TEXT,
                expires TEXT,
                areaDesc TEXT,
                fetchedAt TEXT NOT NULL,
                FOREIGN KEY(locationId) REFERENCES weather_locations(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS index_weather_alerts_locationId ON weather_alerts(locationId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_weather_alerts_expires ON weather_alerts(expires)")
    }
}

internal val MIGRATION_29_30 = object : Migration(29, 30) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // People: household profiles with weather-comfort preferences (Phase 4 groundwork),
        // timeline notes, and a task_people join marking which tasks involve whom. All additive.
        // No SQL DEFAULTs — the entities carry no @ColumnInfo(defaultValue), so Room's generated
        // schema has none and the CREATE must match exactly (same rule as the counters migration).
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS persons (
                id TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                heatToleranceMaxF INTEGER,
                coldToleranceMinF INTEGER,
                uvMax INTEGER,
                windMaxMph INTEGER,
                maxPrecipitationPct INTEGER,
                sunSensitivity TEXT NOT NULL,
                activityPreferences TEXT,
                isArchived INTEGER NOT NULL,
                sortOrder INTEGER NOT NULL,
                createdAt TEXT NOT NULL
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS person_notes (
                id TEXT NOT NULL PRIMARY KEY,
                personId TEXT NOT NULL,
                content TEXT NOT NULL,
                createdAt TEXT NOT NULL,
                FOREIGN KEY(personId) REFERENCES persons(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS index_person_notes_personId ON person_notes(personId)")

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS task_people (
                taskId TEXT NOT NULL,
                personId TEXT NOT NULL,
                PRIMARY KEY(taskId, personId),
                FOREIGN KEY(taskId) REFERENCES tasks(id) ON DELETE CASCADE,
                FOREIGN KEY(personId) REFERENCES persons(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS index_task_people_taskId ON task_people(taskId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_task_people_personId ON task_people(personId)")
    }
}

internal val MIGRATION_30_31 = object : Migration(30, 31) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Phase 3: optional 1:1 weather constraints per task. Side table (taskId PK) so the core
        // `tasks` schema is untouched. No SQL DEFAULTs — the entity carries no @ColumnInfo
        // defaults, so Room's generated schema has none and the CREATE must match exactly.
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS task_weather_requirements (
                taskId TEXT NOT NULL PRIMARY KEY,
                outdoorPreferred INTEGER NOT NULL,
                durationMinutes INTEGER,
                maxTempF INTEGER,
                minTempF INTEGER,
                avoidRain INTEGER NOT NULL,
                maxWindMph INTEGER,
                FOREIGN KEY(taskId) REFERENCES tasks(id) ON DELETE CASCADE
            )
        """.trimIndent())
    }
}

internal val MIGRATION_31_32 = object : Migration(31, 32) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Phase 4: saved activities with default weather requirements. Standalone table; built-ins
        // are seeded in Kotlin on first launch (ActivityTemplateRepository.ensureDefaults). No SQL
        // DEFAULTs — the entity carries no @ColumnInfo defaults, so the CREATE must match exactly.
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS activity_templates (
                id TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                outdoorPreferred INTEGER NOT NULL,
                durationMinutes INTEGER,
                maxTempF INTEGER,
                minTempF INTEGER,
                avoidRain INTEGER NOT NULL,
                maxWindMph INTEGER,
                isBuiltIn INTEGER NOT NULL,
                sortOrder INTEGER NOT NULL,
                createdAt TEXT NOT NULL
            )
        """.trimIndent())
    }
}

internal val MIGRATION_32_33 = object : Migration(32, 33) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Phase 5 learning: log of manual overrides of activity defaults. Standalone table, no FK.
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS activity_overrides (
                id TEXT NOT NULL PRIMARY KEY,
                activityId TEXT NOT NULL,
                field TEXT NOT NULL,
                templateValue INTEGER,
                userValue INTEGER,
                createdAt TEXT NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS index_activity_overrides_activityId ON activity_overrides(activityId)")
    }
}

internal val MIGRATION_33_34 = object : Migration(33, 34) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Game scoreboard: one row per finished run (DESIGN.md §6). Standalone log table, no FK — a
        // run's week key is stored as plain text, not a weeks() reference. No SQL DEFAULTs (the
        // entity carries no @ColumnInfo defaults) so the CREATE must match Room's schema exactly.
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS game_scores (
                id TEXT NOT NULL PRIMARY KEY,
                weekKey TEXT NOT NULL,
                pointInvestment INTEGER NOT NULL,
                score INTEGER NOT NULL,
                setReached INTEGER NOT NULL,
                waveReached INTEGER NOT NULL,
                totalWaves INTEGER NOT NULL,
                levelReached INTEGER NOT NULL,
                weapon TEXT NOT NULL,
                challengeMode TEXT NOT NULL,
                createdAt TEXT NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS index_game_scores_score ON game_scores(score)")
    }
}
