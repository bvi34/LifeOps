package com.lifeops.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.lifeops.app.data.db.dao.*
import com.lifeops.app.data.db.entities.*

private val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tasks ADD COLUMN isRecurring INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE tasks ADD COLUMN estimatedMinutes INTEGER")
        db.execSQL("ALTER TABLE tasks ADD COLUMN carriedCount INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE tasks ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0")
    }
}

private val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tasks ADD COLUMN isManuallyAdded INTEGER NOT NULL DEFAULT 0")
    }
}

private val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS cost_resources (
                id TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                resetCycle TEXT NOT NULL DEFAULT 'monthly',
                capacity INTEGER,
                isActive INTEGER NOT NULL DEFAULT 1,
                sortIndex INTEGER NOT NULL DEFAULT 0,
                createdAt TEXT NOT NULL
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS task_cost_entries (
                id TEXT NOT NULL PRIMARY KEY,
                taskId TEXT NOT NULL,
                resourceId TEXT NOT NULL,
                amount INTEGER NOT NULL,
                note TEXT,
                recordedAt TEXT NOT NULL,
                FOREIGN KEY(taskId) REFERENCES tasks(id) ON DELETE CASCADE,
                FOREIGN KEY(resourceId) REFERENCES cost_resources(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS index_task_cost_entries_taskId ON task_cost_entries(taskId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_task_cost_entries_resourceId ON task_cost_entries(resourceId)")
    }
}

private val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Indices backing the time-range / status queries that previously did full table
        // scans (Reports getAllSince, Resources scoring, widget pending filter).
        // Names must match Room's auto-generated index names (index_<table>_<column>).
        db.execSQL("CREATE INDEX IF NOT EXISTS index_time_entries_recordedAt ON time_entries(recordedAt)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_task_cost_entries_recordedAt ON task_cost_entries(recordedAt)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_tasks_createdAt ON tasks(createdAt)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_tasks_completedAt ON tasks(completedAt)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_tasks_status ON tasks(status)")
    }
}

private val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS projects (
                id TEXT NOT NULL PRIMARY KEY,
                title TEXT NOT NULL,
                aspectId TEXT,
                categoryId TEXT,
                status TEXT NOT NULL DEFAULT 'active',
                description TEXT,
                createdAt TEXT NOT NULL,
                completedAt TEXT,
                FOREIGN KEY(aspectId) REFERENCES aspects(id) ON DELETE SET NULL,
                FOREIGN KEY(categoryId) REFERENCES categories(id) ON DELETE SET NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS index_projects_aspectId ON projects(aspectId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_projects_categoryId ON projects(categoryId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_projects_status ON projects(status)")
        db.execSQL("ALTER TABLE tasks ADD COLUMN projectId TEXT")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_tasks_projectId ON tasks(projectId)")
    }
}

private val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tasks ADD COLUMN source TEXT NOT NULL DEFAULT 'MANUAL'")
    }
}

private val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS index_tasks_source ON tasks(source)")
    }
}

private val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE week_snapshots ADD COLUMN unsuccessfulCount INTEGER NOT NULL DEFAULT 0")
    }
}

private val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Per-aspect {minutes, name, colour} sealed at week-close, powering the Growth
        // Record rings. Additive + defaulted so the upgrade can't fail; existing weeks are
        // backfilled in Kotlin on first launch (see TaskRepository.backfillAspectHistory).
        db.execSQL("ALTER TABLE week_snapshots ADD COLUMN aspectHistory TEXT NOT NULL DEFAULT '{}'")
    }
}

private val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE week_snapshots ADD COLUMN selfRating INTEGER")
        db.execSQL("ALTER TABLE week_snapshots ADD COLUMN selfRatingNote TEXT")
    }
}

private val MIGRATION_14_15 = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Slug on tasks — stable dedup key derived from title
        db.execSQL("ALTER TABLE tasks ADD COLUMN slug TEXT NOT NULL DEFAULT ''")
        db.execSQL("UPDATE tasks SET slug = LOWER(TRIM(title))")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_tasks_weekId_slug ON tasks(weekId, slug)")

        // Subtask provenance on time entries and notes
        db.execSQL("ALTER TABLE time_entries ADD COLUMN subtaskId TEXT")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_time_entries_subtaskId ON time_entries(subtaskId)")
        db.execSQL("ALTER TABLE task_notes ADD COLUMN subtaskId TEXT")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_task_notes_subtaskId ON task_notes(subtaskId)")

        // Runbook definitions
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS runbooks (
                id TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                createdAt TEXT NOT NULL
            )
        """.trimIndent())

        // Ordered step definitions per runbook
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS runbook_steps (
                id TEXT NOT NULL PRIMARY KEY,
                runbookId TEXT NOT NULL,
                label TEXT NOT NULL,
                stepOrder INTEGER NOT NULL,
                FOREIGN KEY(runbookId) REFERENCES runbooks(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS index_runbook_steps_runbookId ON runbook_steps(runbookId)")

        // Subtask instances stamped onto tasks
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS subtasks (
                id TEXT NOT NULL PRIMARY KEY,
                taskId TEXT NOT NULL,
                runbookId TEXT,
                label TEXT NOT NULL,
                stepOrder INTEGER NOT NULL,
                isChecked INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY(taskId) REFERENCES tasks(id) ON DELETE CASCADE,
                FOREIGN KEY(runbookId) REFERENCES runbooks(id) ON DELETE SET NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS index_subtasks_taskId ON subtasks(taskId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_subtasks_runbookId ON subtasks(runbookId)")
    }
}

private val MIGRATION_16_17 = object : Migration(16, 17) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Phase 9: flat subtask tick counter sealed at week-close
        db.execSQL("ALTER TABLE week_snapshots ADD COLUMN subtaskTickCount INTEGER NOT NULL DEFAULT 0")
    }
}

private val MIGRATION_17_18 = object : Migration(17, 18) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tasks ADD COLUMN carryForwardReason TEXT")
    }
}

private val MIGRATION_18_19 = object : Migration(18, 19) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Counters: standalone tally streams, optionally attached to a category (same
        // nullable-categoryId + SET_NULL pattern as projects/tasks). Purely additive —
        // touches no existing tables, so it can't disturb the current schema.
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS counters (
                id TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                categoryId TEXT,
                isArchived INTEGER NOT NULL,
                sortOrder INTEGER NOT NULL,
                createdAt TEXT NOT NULL,
                FOREIGN KEY(categoryId) REFERENCES categories(id) ON DELETE SET NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS index_counters_categoryId ON counters(categoryId)")

        // CounterEvent: one timestamped tick (delta defaults to 1 in Kotlin). weekKey is
        // stamped from occurredAt at insert time, so backdated events sit in the right week.
        // No SQL DEFAULTs here — these columns have no @ColumnInfo(defaultValue), so the
        // CREATE must match Room's entity-generated schema exactly to pass validation.
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS counter_events (
                id TEXT NOT NULL PRIMARY KEY,
                counterId TEXT NOT NULL,
                weekKey INTEGER NOT NULL,
                occurredAt TEXT NOT NULL,
                delta INTEGER NOT NULL,
                note TEXT,
                FOREIGN KEY(counterId) REFERENCES counters(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS index_counter_events_counterId_weekKey ON counter_events(counterId, weekKey)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_counter_events_occurredAt ON counter_events(occurredAt)")
    }
}

private val MIGRATION_19_20 = object : Migration(19, 20) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Optional counter a task ticks when completed. Nullable column + index, no FK — same
        // shape as projectId (MIGRATION_8_9); counters are archived, never deleted.
        db.execSQL("ALTER TABLE tasks ADD COLUMN counterId TEXT")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_tasks_counterId ON tasks(counterId)")
    }
}

private val MIGRATION_15_16 = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS templates (
                id TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                createdAt TEXT NOT NULL
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS template_tasks (
                id TEXT NOT NULL PRIMARY KEY,
                templateId TEXT NOT NULL,
                title TEXT NOT NULL,
                aspectName TEXT,
                categoryName TEXT,
                priority TEXT NOT NULL DEFAULT 'medium',
                estimatedMinutes INTEGER,
                runbookId TEXT,
                taskOrder INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY(templateId) REFERENCES templates(id) ON DELETE CASCADE,
                FOREIGN KEY(runbookId) REFERENCES runbooks(id) ON DELETE SET NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS index_template_tasks_templateId ON template_tasks(templateId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_template_tasks_runbookId ON template_tasks(runbookId)")
    }
}

private val MIGRATION_20_21 = object : Migration(20, 21) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Nutrition backend: USDA-seeded + custom foods, recipes built from them, and a food
        // log to drive recent/frequent search and ad-hoc-to-custom promotion. Purely additive.
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS food_items (
                id TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                brand TEXT,
                servingSize REAL NOT NULL,
                servingUnit TEXT NOT NULL,
                servingSizeGrams REAL,
                calories REAL NOT NULL,
                carbsG REAL NOT NULL,
                proteinG REAL NOT NULL,
                fatG REAL NOT NULL,
                fiberG REAL,
                sodiumMg REAL,
                source TEXT NOT NULL,
                fdcId INTEGER,
                createdAt TEXT NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS index_food_items_name ON food_items(name)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_food_items_brand ON food_items(brand)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_food_items_source ON food_items(source)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_food_items_fdcId ON food_items(fdcId)")

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS recipes (
                id TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                servings REAL NOT NULL,
                createdAt TEXT NOT NULL
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS recipe_ingredients (
                id TEXT NOT NULL PRIMARY KEY,
                recipeId TEXT NOT NULL,
                foodItemId TEXT NOT NULL,
                quantity REAL NOT NULL,
                unit TEXT NOT NULL,
                sortOrder INTEGER NOT NULL,
                FOREIGN KEY(recipeId) REFERENCES recipes(id) ON DELETE CASCADE,
                FOREIGN KEY(foodItemId) REFERENCES food_items(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS index_recipe_ingredients_recipeId ON recipe_ingredients(recipeId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_recipe_ingredients_foodItemId ON recipe_ingredients(foodItemId)")

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS food_log_entries (
                id TEXT NOT NULL PRIMARY KEY,
                foodItemId TEXT,
                name TEXT NOT NULL,
                quantity REAL NOT NULL,
                unit TEXT NOT NULL,
                calories REAL NOT NULL,
                carbsG REAL NOT NULL,
                proteinG REAL NOT NULL,
                fatG REAL NOT NULL,
                loggedAt TEXT NOT NULL,
                FOREIGN KEY(foodItemId) REFERENCES food_items(id) ON DELETE SET NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS index_food_log_entries_foodItemId ON food_log_entries(foodItemId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_food_log_entries_loggedAt ON food_log_entries(loggedAt)")
    }
}

private val MIGRATION_21_22 = object : Migration(21, 22) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Meals committed to a week before they're necessarily assigned to a day — recipeId is
        // nullable so a freeform name (e.g. "meatloaf") can be jotted down before a Recipe
        // exists for it. Purely additive table, no existing schema touched.
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS weekly_menu_items (
                id TEXT NOT NULL PRIMARY KEY,
                weekStartDate TEXT NOT NULL,
                recipeId TEXT,
                mealName TEXT NOT NULL,
                plannedServings REAL NOT NULL,
                assignedDate TEXT,
                mealType TEXT,
                createdAt TEXT NOT NULL,
                FOREIGN KEY(recipeId) REFERENCES recipes(id) ON DELETE SET NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS index_weekly_menu_items_weekStartDate ON weekly_menu_items(weekStartDate)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_weekly_menu_items_recipeId ON weekly_menu_items(recipeId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_weekly_menu_items_assignedDate ON weekly_menu_items(assignedDate)")

        // Tracks how a log entry came to exist (Planned/Adjusted/AdHoc) and whether the user has
        // confirmed it, plus a link back to the WeeklyMenuItem it was spawned from, if any.
        db.execSQL("ALTER TABLE food_log_entries ADD COLUMN source TEXT NOT NULL DEFAULT 'AD_HOC'")
        db.execSQL("ALTER TABLE food_log_entries ADD COLUMN confirmed INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE food_log_entries ADD COLUMN confirmedAt TEXT")
        db.execSQL("ALTER TABLE food_log_entries ADD COLUMN weeklyMenuItemId TEXT")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_food_log_entries_weeklyMenuItemId ON food_log_entries(weeklyMenuItemId)")
    }
}

private val MIGRATION_22_23 = object : Migration(22, 23) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Rebuilds `tasks` to fix a defaultValue mismatch that's been silently carried forward
        // since MIGRATION_4_5/14_15: those ALTER TABLE ... ADD COLUMN statements ran on devices
        // before this file declared DEFAULT 0 / DEFAULT '' for isRecurring, carriedCount,
        // sortOrder and slug, so SQLite never recorded a default for those columns on disk —
        // even though the migration source here looks correct today, migrations are immutable
        // once shipped, so already-upgraded installs kept the defaultless columns forever.
        // Room's startup schema validation then fails every launch with "Migration didn't
        // properly handle: tasks" because TaskEntity's @ColumnInfo(defaultValue=...) no longer
        // matches what's actually on disk. SQLite can't ALTER a column to add a default, so the
        // only fix is to recreate the table with the schema Room actually expects.
        db.execSQL("""
            CREATE TABLE tasks_new (
                id TEXT NOT NULL PRIMARY KEY,
                weekId TEXT NOT NULL,
                title TEXT NOT NULL,
                aspectId TEXT,
                categoryId TEXT,
                priority TEXT NOT NULL,
                dueDate TEXT,
                hardDeadline INTEGER NOT NULL,
                status TEXT NOT NULL,
                resourceValue INTEGER NOT NULL,
                completedAt TEXT,
                carriedFromTaskId TEXT,
                createdAt TEXT NOT NULL,
                isRecurring INTEGER NOT NULL DEFAULT 0,
                estimatedMinutes INTEGER,
                carriedCount INTEGER NOT NULL DEFAULT 0,
                sortOrder INTEGER NOT NULL DEFAULT 0,
                isManuallyAdded INTEGER NOT NULL DEFAULT 0,
                projectId TEXT,
                source TEXT NOT NULL DEFAULT 'MANUAL',
                slug TEXT NOT NULL DEFAULT '',
                carryForwardReason TEXT,
                counterId TEXT,
                FOREIGN KEY(weekId) REFERENCES weeks(id) ON DELETE CASCADE,
                FOREIGN KEY(aspectId) REFERENCES aspects(id) ON DELETE SET NULL,
                FOREIGN KEY(categoryId) REFERENCES categories(id) ON DELETE SET NULL
            )
        """.trimIndent())
        db.execSQL("""
            INSERT INTO tasks_new (
                id, weekId, title, aspectId, categoryId, priority, dueDate, hardDeadline,
                status, resourceValue, completedAt, carriedFromTaskId, createdAt, isRecurring,
                estimatedMinutes, carriedCount, sortOrder, isManuallyAdded, projectId, source,
                slug, carryForwardReason, counterId
            )
            SELECT
                id, weekId, title, aspectId, categoryId, priority, dueDate, hardDeadline,
                status, resourceValue, completedAt, carriedFromTaskId, createdAt, isRecurring,
                estimatedMinutes, carriedCount, sortOrder, isManuallyAdded, projectId, source,
                slug, carryForwardReason, counterId
            FROM tasks
        """.trimIndent())
        db.execSQL("DROP TABLE tasks")
        db.execSQL("ALTER TABLE tasks_new RENAME TO tasks")

        db.execSQL("CREATE INDEX IF NOT EXISTS index_tasks_weekId ON tasks(weekId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_tasks_aspectId ON tasks(aspectId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_tasks_categoryId ON tasks(categoryId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_tasks_createdAt ON tasks(createdAt)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_tasks_completedAt ON tasks(completedAt)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_tasks_status ON tasks(status)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_tasks_projectId ON tasks(projectId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_tasks_source ON tasks(source)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_tasks_counterId ON tasks(counterId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_tasks_weekId_slug ON tasks(weekId, slug)")
    }
}

private val MIGRATION_23_24 = object : Migration(23, 24) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Rebuilds `food_log_entries` to add the FK on weeklyMenuItemId that the entity has
        // always declared. MIGRATION_21_22 added that column with a plain
        // ALTER TABLE ... ADD COLUMN, but SQLite can't attach a foreign-key constraint via
        // ALTER TABLE, so the on-disk table never actually got it — Room's schema validation
        // then fails every launch because FoodLogEntryEntity expects a FK to weekly_menu_items
        // that isn't there. Only a table rebuild can add it.
        db.execSQL("""
            CREATE TABLE food_log_entries_new (
                id TEXT NOT NULL PRIMARY KEY,
                foodItemId TEXT,
                name TEXT NOT NULL,
                quantity REAL NOT NULL,
                unit TEXT NOT NULL,
                calories REAL NOT NULL,
                carbsG REAL NOT NULL,
                proteinG REAL NOT NULL,
                fatG REAL NOT NULL,
                loggedAt TEXT NOT NULL,
                source TEXT NOT NULL DEFAULT 'AD_HOC',
                confirmed INTEGER NOT NULL DEFAULT 0,
                confirmedAt TEXT,
                weeklyMenuItemId TEXT,
                FOREIGN KEY(foodItemId) REFERENCES food_items(id) ON DELETE SET NULL,
                FOREIGN KEY(weeklyMenuItemId) REFERENCES weekly_menu_items(id) ON DELETE SET NULL
            )
        """.trimIndent())
        db.execSQL("""
            INSERT INTO food_log_entries_new (
                id, foodItemId, name, quantity, unit, calories, carbsG, proteinG, fatG,
                loggedAt, source, confirmed, confirmedAt, weeklyMenuItemId
            )
            SELECT
                id, foodItemId, name, quantity, unit, calories, carbsG, proteinG, fatG,
                loggedAt, source, confirmed, confirmedAt, weeklyMenuItemId
            FROM food_log_entries
        """.trimIndent())
        db.execSQL("DROP TABLE food_log_entries")
        db.execSQL("ALTER TABLE food_log_entries_new RENAME TO food_log_entries")

        db.execSQL("CREATE INDEX IF NOT EXISTS index_food_log_entries_foodItemId ON food_log_entries(foodItemId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_food_log_entries_loggedAt ON food_log_entries(loggedAt)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_food_log_entries_weeklyMenuItemId ON food_log_entries(weeklyMenuItemId)")
    }
}

private val MIGRATION_24_25 = object : Migration(24, 25) {
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

private val MIGRATION_25_26 = object : Migration(25, 26) {
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

private val MIGRATION_26_27 = object : Migration(26, 27) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Future projects gain an archive lifecycle mirroring projects.status: 'active'
        // ideas can be archived, or promoted into a real project and archived alongside.
        db.execSQL("ALTER TABLE future_projects ADD COLUMN status TEXT NOT NULL DEFAULT 'active'")
    }
}

private val MIGRATION_27_28 = object : Migration(27, 28) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Promoted projects remember which future project they came from, so the project
        // detail can surface the original brainstorming notes.
        db.execSQL("ALTER TABLE projects ADD COLUMN sourceFutureProjectId TEXT")
    }
}

private val MIGRATION_28_29 = object : Migration(28, 29) {
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

private val MIGRATION_29_30 = object : Migration(29, 30) {
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

private val MIGRATION_30_31 = object : Migration(30, 31) {
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

private val MIGRATION_31_32 = object : Migration(31, 32) {
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

private val MIGRATION_32_33 = object : Migration(32, 33) {
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

private val MIGRATION_33_34 = object : Migration(33, 34) {
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

private val MIGRATION_34_35 = object : Migration(34, 35) {
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

private val MIGRATION_35_36 = object : Migration(35, 36) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Recurrence cadence. recurrenceIntervalWeeks carries a NOT NULL DEFAULT 1 to match the
        // entity's @ColumnInfo(defaultValue = "1") — existing recurring tasks stay weekly.
        // recurrenceDayOfMonth is nullable with no default (monthly-by-date mode; null = week-interval).
        db.execSQL("ALTER TABLE tasks ADD COLUMN recurrenceIntervalWeeks INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE tasks ADD COLUMN recurrenceDayOfMonth INTEGER")
    }
}

private val MIGRATION_37_38 = object : Migration(37, 38) {
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

private val MIGRATION_36_37 = object : Migration(36, 37) {
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

private val MIGRATION_38_39 = object : Migration(38, 39) {
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

private val MIGRATION_39_40 = object : Migration(39, 40) {
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

private val MIGRATION_40_41 = object : Migration(40, 41) {
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

private val MIGRATION_41_42 = object : Migration(41, 42) {
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

private val MIGRATION_42_43 = object : Migration(42, 43) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Opt-in start-of-block reminder on busy blocks. NOT NULL DEFAULT 0 matches the entity's
        // @ColumnInfo(defaultValue = "0"); purely additive, existing blocks stay reminder-off.
        db.execSQL("ALTER TABLE busy_blocks ADD COLUMN reminderEnabled INTEGER NOT NULL DEFAULT 0")
    }
}

private val MIGRATION_43_44 = object : Migration(43, 44) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Book provenance from Citation reading telemetry: source kind + derived category. Both
        // nullable (existing LifeOps-created books have neither); purely additive.
        db.execSQL("ALTER TABLE books ADD COLUMN sourceType TEXT")
        db.execSQL("ALTER TABLE books ADD COLUMN category TEXT")
    }
}

private val MIGRATION_44_45 = object : Migration(44, 45) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Milestones: rare, once-in-a-lifetime accomplishments, optionally attached to an aspect
        // and/or a person (nullable FKs, ON DELETE SET NULL — same shape as tasks.projectId). Their
        // points are minted immediately on creation, not at week-close. Purely additive; no existing
        // schema is touched. No SQL DEFAULTs — the entity carries no @ColumnInfo(defaultValue), so
        // Room's generated schema has none and this CREATE must match exactly or startup validation
        // fails (the same rule the counters/people migrations call out).
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS milestones (
                id TEXT NOT NULL PRIMARY KEY,
                title TEXT NOT NULL,
                description TEXT,
                points INTEGER NOT NULL,
                aspectId TEXT,
                personId TEXT,
                achievedAt TEXT NOT NULL,
                createdAt TEXT NOT NULL,
                FOREIGN KEY(aspectId) REFERENCES aspects(id) ON DELETE SET NULL,
                FOREIGN KEY(personId) REFERENCES persons(id) ON DELETE SET NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS index_milestones_aspectId ON milestones(aspectId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_milestones_personId ON milestones(personId)")
    }
}

private val MIGRATION_45_46 = object : Migration(45, 46) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // The Citation record on a book: the source's own id (O'Reilly product id / ISBN / RR id)
        // and the title Citation reports. Kept separate from the user-editable `title` so a rename in
        // LifeOps survives sync. Both nullable (LifeOps-only books have neither); purely additive.
        db.execSQL("ALTER TABLE books ADD COLUMN sourceId TEXT")
        db.execSQL("ALTER TABLE books ADD COLUMN citationTitle TEXT")
    }
}

private val MIGRATION_46_47 = object : Migration(46, 47) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Calendar people-tagging + Google Calendar sync linkage. All additive.
        // No SQL DEFAULTs on the join table — BusyBlockPersonEntity carries no
        // @ColumnInfo(defaultValue), matching the task_people precedent (MIGRATION_29_30).
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS busy_block_people (
                busyBlockId TEXT NOT NULL,
                personId TEXT NOT NULL,
                PRIMARY KEY(busyBlockId, personId),
                FOREIGN KEY(busyBlockId) REFERENCES busy_blocks(id) ON DELETE CASCADE,
                FOREIGN KEY(personId) REFERENCES persons(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS index_busy_block_people_busyBlockId ON busy_block_people(busyBlockId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_busy_block_people_personId ON busy_block_people(personId)")

        // googleEventId/googleCalendarId: which CalendarContract event (own-schedule blocks only)
        // this block maps to, so a later sync updates in place instead of duplicating.
        db.execSQL("ALTER TABLE busy_blocks ADD COLUMN googleEventId INTEGER")
        db.execSQL("ALTER TABLE busy_blocks ADD COLUMN googleCalendarId INTEGER")
    }
}

private val MIGRATION_47_48 = object : Migration(47, 48) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Relationship-balance analytics (RelationshipAnalytics): which relationship bucket a
        // person is in, so booked time can be compared within it (e.g. one child vs a sibling).
        // Nullable/no SQL DEFAULT — a person with no relationship set is simply excluded.
        db.execSQL("ALTER TABLE persons ADD COLUMN relationship TEXT")
    }
}

@Database(
    entities = [
        AspectEntity::class,
        CategoryEntity::class,
        WeekEntity::class,
        TaskEntity::class,
        WeekSnapshotEntity::class,
        GameResourceEntity::class,
        GameResourceMappingEntity::class,
        NotificationEntity::class,
        TaskNoteEntity::class,
        TimeEntryEntity::class,
        ResourceTransactionEntity::class,
        CostResourceEntity::class,
        TaskCostEntryEntity::class,
        ProjectEntity::class,
        RunbookEntity::class,
        RunbookStepEntity::class,
        SubtaskEntity::class,
        TemplateEntity::class,
        TemplateTaskEntity::class,
        CounterEntity::class,
        CounterEventEntity::class,
        FoodItemEntity::class,
        RecipeEntity::class,
        RecipeIngredientEntity::class,
        FoodLogEntryEntity::class,
        WeeklyMenuItemEntity::class,
        BookEntity::class,
        BookNoteEntity::class,
        BookTimeEntryEntity::class,
        FutureProjectEntity::class,
        FutureProjectNoteEntity::class,
        WeatherLocationEntity::class,
        WeatherSnapshotEntity::class,
        WeatherAlertEntity::class,
        PersonEntity::class,
        PersonNoteEntity::class,
        TaskPersonEntity::class,
        TaskWeatherRequirementEntity::class,
        ActivityTemplateEntity::class,
        ActivityOverrideEntity::class,
        GameScoreEntity::class,
        WellnessCheckinEntity::class,
        TaskAttachmentEntity::class,
        BusyBlockEntity::class,
        PhoneActivityEventEntity::class,
        GameUnlockEntity::class,
        MilestoneEntity::class,
        BusyBlockPersonEntity::class
    ],
    version = 48,
    exportSchema = true
)
abstract class LifeOpsDatabase : RoomDatabase() {
    abstract fun aspectDao(): AspectDao
    abstract fun categoryDao(): CategoryDao
    abstract fun weekDao(): WeekDao
    abstract fun taskDao(): TaskDao
    abstract fun weekSnapshotDao(): WeekSnapshotDao
    abstract fun gameResourceDao(): GameResourceDao
    abstract fun gameResourceMappingDao(): GameResourceMappingDao
    abstract fun notificationDao(): NotificationDao
    abstract fun taskNoteDao(): TaskNoteDao
    abstract fun taskAttachmentDao(): TaskAttachmentDao
    abstract fun busyBlockDao(): BusyBlockDao
    abstract fun timeEntryDao(): TimeEntryDao
    abstract fun resourceTransactionDao(): ResourceTransactionDao
    abstract fun costResourceDao(): CostResourceDao
    abstract fun taskCostEntryDao(): TaskCostEntryDao
    abstract fun projectDao(): ProjectDao
    abstract fun runbookDao(): RunbookDao
    abstract fun subtaskDao(): SubtaskDao
    abstract fun templateDao(): TemplateDao
    abstract fun counterDao(): CounterDao
    abstract fun foodItemDao(): FoodItemDao
    abstract fun recipeDao(): RecipeDao
    abstract fun foodLogDao(): FoodLogDao
    abstract fun weeklyMenuItemDao(): WeeklyMenuItemDao
    abstract fun bookDao(): BookDao
    abstract fun futureProjectDao(): FutureProjectDao
    abstract fun weatherDao(): WeatherDao
    abstract fun personDao(): PersonDao
    abstract fun activityTemplateDao(): ActivityTemplateDao
    abstract fun gameScoreDao(): GameScoreDao
    abstract fun wellnessCheckinDao(): WellnessCheckinDao
    abstract fun phoneActivityEventDao(): PhoneActivityEventDao
    abstract fun gameUnlockDao(): GameUnlockDao
    abstract fun milestoneDao(): MilestoneDao

    companion object {
        @Volatile private var INSTANCE: LifeOpsDatabase? = null

        fun getInstance(context: Context): LifeOpsDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    LifeOpsDatabase::class.java,
                    "lifeops.db"
                )
                    .addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23, MIGRATION_23_24, MIGRATION_24_25, MIGRATION_25_26, MIGRATION_26_27, MIGRATION_27_28, MIGRATION_28_29, MIGRATION_29_30, MIGRATION_30_31, MIGRATION_31_32, MIGRATION_32_33, MIGRATION_33_34, MIGRATION_34_35, MIGRATION_35_36, MIGRATION_36_37, MIGRATION_37_38, MIGRATION_38_39, MIGRATION_39_40, MIGRATION_40_41, MIGRATION_41_42, MIGRATION_42_43, MIGRATION_43_44, MIGRATION_44_45, MIGRATION_45_46, MIGRATION_46_47, MIGRATION_47_48)
                    .build()
                    .also { INSTANCE = it }
            }

        /**
         * Close and forget the singleton so the underlying `lifeops.db` file can be replaced
         * wholesale (used by the Operations Sandbox restore, which swaps the file rather than
         * merging rows, to guarantee a complete restore of every table). The next [getInstance]
         * rebuilds against the restored file; a LifeOps restart is expected after a restore.
         */
        fun closeInstance() {
            synchronized(this) {
                INSTANCE?.close()
                INSTANCE = null
            }
        }
    }
}
