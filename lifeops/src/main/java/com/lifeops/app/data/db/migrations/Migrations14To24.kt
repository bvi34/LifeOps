package com.lifeops.app.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// Schema migrations 14 → 24.
//
// Grouped by version range rather than by subject: a migration belongs to the moment the
// schema changed, and that moment never moves. Splitting them any other way would mean
// hunting through several files to answer "what did version 15 actually do".

internal val MIGRATION_14_15 = object : Migration(14, 15) {
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

internal val MIGRATION_15_16 = object : Migration(15, 16) {
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

internal val MIGRATION_16_17 = object : Migration(16, 17) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Phase 9: flat subtask tick counter sealed at week-close
        db.execSQL("ALTER TABLE week_snapshots ADD COLUMN subtaskTickCount INTEGER NOT NULL DEFAULT 0")
    }
}

internal val MIGRATION_17_18 = object : Migration(17, 18) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tasks ADD COLUMN carryForwardReason TEXT")
    }
}

internal val MIGRATION_18_19 = object : Migration(18, 19) {
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

internal val MIGRATION_19_20 = object : Migration(19, 20) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Optional counter a task ticks when completed. Nullable column + index, no FK — same
        // shape as projectId (MIGRATION_8_9); counters are archived, never deleted.
        db.execSQL("ALTER TABLE tasks ADD COLUMN counterId TEXT")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_tasks_counterId ON tasks(counterId)")
    }
}

internal val MIGRATION_20_21 = object : Migration(20, 21) {
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

internal val MIGRATION_21_22 = object : Migration(21, 22) {
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

internal val MIGRATION_22_23 = object : Migration(22, 23) {
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

internal val MIGRATION_23_24 = object : Migration(23, 24) {
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
