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
        TemplateTaskEntity::class
    ],
    version = 17,
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

    companion object {
        @Volatile private var INSTANCE: LifeOpsDatabase? = null

        fun getInstance(context: Context): LifeOpsDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    LifeOpsDatabase::class.java,
                    "lifeops.db"
                )
                    .addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
