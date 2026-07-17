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
        FutureProjectNoteEntity::class
    ],
    version = 28,
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

    companion object {
        @Volatile private var INSTANCE: LifeOpsDatabase? = null

        fun getInstance(context: Context): LifeOpsDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    LifeOpsDatabase::class.java,
                    "lifeops.db"
                )
                    .addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23, MIGRATION_23_24, MIGRATION_24_25, MIGRATION_25_26, MIGRATION_26_27, MIGRATION_27_28)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
