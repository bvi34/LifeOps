package com.lifeops.app.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// Schema migrations 4 → 14.
//
// Grouped by version range rather than by subject: a migration belongs to the moment the
// schema changed, and that moment never moves. Splitting them any other way would mean
// hunting through several files to answer "what did version 5 actually do".

internal val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tasks ADD COLUMN isRecurring INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE tasks ADD COLUMN estimatedMinutes INTEGER")
        db.execSQL("ALTER TABLE tasks ADD COLUMN carriedCount INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE tasks ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0")
    }
}

internal val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tasks ADD COLUMN isManuallyAdded INTEGER NOT NULL DEFAULT 0")
    }
}

internal val MIGRATION_6_7 = object : Migration(6, 7) {
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

internal val MIGRATION_7_8 = object : Migration(7, 8) {
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

internal val MIGRATION_8_9 = object : Migration(8, 9) {
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

internal val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tasks ADD COLUMN source TEXT NOT NULL DEFAULT 'MANUAL'")
    }
}

internal val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS index_tasks_source ON tasks(source)")
    }
}

internal val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE week_snapshots ADD COLUMN unsuccessfulCount INTEGER NOT NULL DEFAULT 0")
    }
}

internal val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Per-aspect {minutes, name, colour} sealed at week-close, powering the Growth
        // Record rings. Additive + defaulted so the upgrade can't fail; existing weeks are
        // backfilled in Kotlin on first launch (see TaskRepository.backfillAspectHistory).
        db.execSQL("ALTER TABLE week_snapshots ADD COLUMN aspectHistory TEXT NOT NULL DEFAULT '{}'")
    }
}

internal val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE week_snapshots ADD COLUMN selfRating INTEGER")
        db.execSQL("ALTER TABLE week_snapshots ADD COLUMN selfRatingNote TEXT")
    }
}
