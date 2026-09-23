package com.lifeops.app.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// Schema migrations 55 → 65. Grouped by version range, like the files before it — see
// Migrations44To55.kt for why.

internal val MIGRATION_55_56 = object : Migration(55, 56) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Objectives: forward-looking goals with a due date, reached through ordered steps and
        // closed only by an outcome (see ObjectiveEntity / ObjectiveStepEntity). Both tables are
        // new, so there is nothing to backfill. Neither entity declares a @ColumnInfo(defaultValue),
        // so these CREATEs carry no DEFAULTs — Room's schema check compares them exactly.
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS objectives (
                id TEXT NOT NULL PRIMARY KEY,
                title TEXT NOT NULL,
                aspectId TEXT,
                dueDate TEXT NOT NULL,
                successCriteria TEXT NOT NULL,
                status TEXT NOT NULL,
                createdAt TEXT NOT NULL,
                updatedAt TEXT NOT NULL,
                closedAt TEXT,
                FOREIGN KEY(aspectId) REFERENCES aspects(id) ON DELETE SET NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS index_objectives_aspectId ON objectives(aspectId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_objectives_status ON objectives(status)")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS objective_steps (
                id TEXT NOT NULL PRIMARY KEY,
                objectiveId TEXT NOT NULL,
                position INTEGER NOT NULL,
                title TEXT NOT NULL,
                opensOn TEXT,
                afterPrevious INTEGER NOT NULL,
                dueDate TEXT,
                completedAt TEXT,
                FOREIGN KEY(objectiveId) REFERENCES objectives(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS index_objective_steps_objectiveId ON objective_steps(objectiveId)")
    }
}

internal val MIGRATION_56_57 = object : Migration(56, 57) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // A task can be the week's work on an objective step (TaskEntity.objectiveStepId), which
        // is how a step gets notes, photos and time. Nullable, no default, no FK — like operationId.
        db.execSQL("ALTER TABLE tasks ADD COLUMN objectiveStepId TEXT")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_tasks_objectiveStepId ON tasks(objectiveStepId)")
        // Notes on the objective itself (ObjectiveNoteEntity). New table, nothing to backfill.
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS objective_notes (
                id TEXT NOT NULL PRIMARY KEY,
                objectiveId TEXT NOT NULL,
                content TEXT NOT NULL,
                createdAt TEXT NOT NULL,
                FOREIGN KEY(objectiveId) REFERENCES objectives(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS index_objective_notes_objectiveId ON objective_notes(objectiveId)")
    }
}
