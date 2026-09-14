package com.lifeops.app.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// Schema migrations 44 → 55.
//
// Grouped by version range rather than by subject: a migration belongs to the moment the
// schema changed, and that moment never moves. Splitting them any other way would mean
// hunting through several files to answer "what did version 45 actually do".

internal val MIGRATION_44_45 = object : Migration(44, 45) {
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

internal val MIGRATION_45_46 = object : Migration(45, 46) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // The Citation record on a book: the source's own id (O'Reilly product id / ISBN / RR id)
        // and the title Citation reports. Kept separate from the user-editable `title` so a rename in
        // LifeOps survives sync. Both nullable (LifeOps-only books have neither); purely additive.
        db.execSQL("ALTER TABLE books ADD COLUMN sourceId TEXT")
        db.execSQL("ALTER TABLE books ADD COLUMN citationTitle TEXT")
    }
}

internal val MIGRATION_46_47 = object : Migration(46, 47) {
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

internal val MIGRATION_47_48 = object : Migration(47, 48) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Relationship-balance analytics (RelationshipAnalytics): which relationship bucket a
        // person is in, so booked time can be compared within it (e.g. one child vs a sibling).
        // Nullable/no SQL DEFAULT — a person with no relationship set is simply excluded.
        db.execSQL("ALTER TABLE persons ADD COLUMN relationship TEXT")
    }
}

internal val MIGRATION_48_49 = object : Migration(48, 49) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Contact identity for Google Calendar attendee matching (GoogleCalendarSyncRepository):
        // an incoming event's attendee list is matched against Person.email to recognize who it's
        // with, auto-creating a person when nothing matches. Both nullable, no SQL DEFAULT.
        db.execSQL("ALTER TABLE persons ADD COLUMN email TEXT")
        db.execSQL("ALTER TABLE persons ADD COLUMN phone TEXT")
    }
}

internal val MIGRATION_49_50 = object : Migration(49, 50) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // The relative check-in (WellnessRepository.logCheckin): a daytime check-in now answers
        // "better/same/worse than last time" plus "initiative: yes/neutral/no" instead of
        // re-scoring energy and sensory load from scratch. Both nullable — SLEEP rows never carry
        // them, and CHECKIN rows written before this redesign don't either.
        db.execSQL("ALTER TABLE wellness_checkins ADD COLUMN trend TEXT")
        db.execSQL("ALTER TABLE wellness_checkins ADD COLUMN initiative TEXT")
        // Flags an energy value stepped from the previous reading rather than typed in. NOT NULL
        // with a SQL DEFAULT matching @ColumnInfo(defaultValue) so validation passes (the
        // task_people precedent, MIGRATION_29_30): every existing row was hand-entered.
        db.execSQL("ALTER TABLE wellness_checkins ADD COLUMN energyDerived INTEGER NOT NULL DEFAULT 0")
    }
}

internal val MIGRATION_50_51 = object : Migration(50, 51) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // A recipe is now something you can cook from, not just an ingredient vector: it carries
        // its method and, when it was grabbed from a link, where it came from. Both nullable —
        // every recipe written before this has neither, and a hand-entered one never has a source.
        db.execSQL("ALTER TABLE recipes ADD COLUMN instructions TEXT")
        db.execSQL("ALTER TABLE recipes ADD COLUMN sourceUrl TEXT")
    }
}

internal val MIGRATION_51_52 = object : Migration(51, 52) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // People sync (see com.lifeops.app.data.repository.PeopleSyncRepository). LifeOps keeps
        // owning this table and every foreign key into it — task_people, busy_block_people,
        // busy_blocks and milestones are untouched. What it gains is the bookkeeping to be a *peer*
        // on the People seam rather than the only place a household roster exists:
        //
        //  - personKey: the identity a person keeps across peers, as distinct from `id`, which is
        //    only this database's row id. Backfilled from `id` so existing people already have one,
        //    and the first sync publishes them under a key People will keep.
        //  - syncVersion: this peer's monotonic stamp. Every local edit bumps it and the outbound
        //    envelope is "every row above the other peer's ack", so the outbox is derived from the
        //    rows instead of being a second, separately-corruptible queue. Existing rows are seeded
        //    at 1 precisely so the household already in LifeOps flows into People on the first run.
        //  - updatedAt: the merge clock, epoch millis. Zero for rows that predate the seam, which
        //    is the honest answer — we do not know when they were last touched, and a fabricated
        //    timestamp would win merges it has no right to.
        db.execSQL("ALTER TABLE persons ADD COLUMN personKey TEXT")
        db.execSQL("ALTER TABLE persons ADD COLUMN syncVersion INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE persons ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
        db.execSQL("UPDATE persons SET personKey = id WHERE personKey IS NULL")
        db.execSQL("UPDATE persons SET syncVersion = 1 WHERE syncVersion = 0")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_persons_syncVersion ON persons(syncVersion)")

        // Deleting a person here is a real delete — task_people, busy_block_people, busy_blocks and
        // milestones all cascade off it, and that behaviour predates the seam. But a deleted row has
        // nothing left to publish, so without a trace of it the other peer would simply hand the
        // person back on the next round. This table is that trace: the key, kept long enough to be
        // published once as a withdrawal. People, whose removal is an archive rather than a delete,
        // needs no equivalent — its row is still there to speak for itself.
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS person_tombstones (
                personKey TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                deletedAt INTEGER NOT NULL,
                syncVersion INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_person_tombstones_syncVersion ON person_tombstones(syncVersion)"
        )
    }
}

internal val MIGRATION_52_53 = object : Migration(52, 53) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // The week's commitment: the handful of tasks whose completion decides whether the week
        // was a success, as distinct from the whole list. Three additive columns, no backfill —
        // every task written before this is simply not a commitment, and every week closed before
        // this sealed no bar (0/0), which the review reads as "no bar was set" rather than as a
        // missed one.
        db.execSQL("ALTER TABLE tasks ADD COLUMN isCommitment INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE week_snapshots ADD COLUMN commitmentTotal INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE week_snapshots ADD COLUMN commitmentCompleted INTEGER NOT NULL DEFAULT 0")
    }
}

internal val MIGRATION_53_54 = object : Migration(53, 54) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // "Projects" become "Operations" throughout LifeOps — the name collided with the suite's
        // own Project app (`:project`, the document/planning repository), and two different things
        // called the same word in one launcher is a bug in the vocabulary. Pure rename: every row,
        // every id and every link is carried across unchanged; nothing is created or dropped.
        //
        // Each table is rebuilt rather than renamed with ALTER TABLE ... RENAME. `operations` and
        // `tasks` need a column renamed too (sourceFutureProjectId, projectId), and SQLite only
        // learned RENAME COLUMN in 3.25 — minSdk is 26, whose bundled SQLite predates it. Rebuild
        // is also the pattern the rest of this file already uses (MIGRATION_22_23 onwards) and is
        // the only form that leaves a byte-identical schema for Room's startup validation.
        //
        // Room runs migrations with foreign_keys off, so dropping a parent before its children are
        // rebuilt is safe; the children are rebuilt against the new parents in the same
        // transaction. Order: projects → operations, then future_projects → future_operations and
        // its notes child, then tasks (whose projectId is a bare column, no FK).

        // ---- projects -> operations (sourceFutureProjectId -> sourceFutureOperationId) ----
        db.execSQL("""
            CREATE TABLE operations (
                id TEXT NOT NULL PRIMARY KEY,
                title TEXT NOT NULL,
                aspectId TEXT,
                categoryId TEXT,
                status TEXT NOT NULL DEFAULT 'active',
                description TEXT,
                createdAt TEXT NOT NULL,
                completedAt TEXT,
                sourceFutureOperationId TEXT,
                FOREIGN KEY(aspectId) REFERENCES aspects(id) ON DELETE SET NULL,
                FOREIGN KEY(categoryId) REFERENCES categories(id) ON DELETE SET NULL
            )
        """.trimIndent())
        db.execSQL("""
            INSERT INTO operations (
                id, title, aspectId, categoryId, status, description, createdAt, completedAt,
                sourceFutureOperationId
            )
            SELECT
                id, title, aspectId, categoryId, status, description, createdAt, completedAt,
                sourceFutureProjectId
            FROM projects
        """.trimIndent())
        db.execSQL("DROP TABLE projects")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_operations_aspectId ON operations(aspectId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_operations_categoryId ON operations(categoryId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_operations_status ON operations(status)")

        // ---- future_projects -> future_operations ----
        db.execSQL("""
            CREATE TABLE future_operations (
                id TEXT NOT NULL PRIMARY KEY,
                title TEXT NOT NULL,
                content TEXT NOT NULL,
                createdAt TEXT NOT NULL,
                updatedAt TEXT NOT NULL,
                status TEXT NOT NULL DEFAULT 'active'
            )
        """.trimIndent())
        db.execSQL("""
            INSERT INTO future_operations (id, title, content, createdAt, updatedAt, status)
            SELECT id, title, content, createdAt, updatedAt, status FROM future_projects
        """.trimIndent())

        // ---- future_project_notes -> future_operation_notes (projectId -> operationId) ----
        // Built against the new parent and filled from the old table before either old table is
        // dropped, so the notes are never without a row to hang off.
        db.execSQL("""
            CREATE TABLE future_operation_notes (
                id TEXT NOT NULL PRIMARY KEY,
                operationId TEXT NOT NULL,
                content TEXT NOT NULL,
                createdAt TEXT NOT NULL,
                FOREIGN KEY(operationId) REFERENCES future_operations(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("""
            INSERT INTO future_operation_notes (id, operationId, content, createdAt)
            SELECT id, projectId, content, createdAt FROM future_project_notes
        """.trimIndent())
        db.execSQL("DROP TABLE future_project_notes")
        db.execSQL("DROP TABLE future_projects")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_future_operation_notes_operationId ON future_operation_notes(operationId)")

        // ---- tasks.projectId -> tasks.operationId ----
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
                operationId TEXT,
                source TEXT NOT NULL DEFAULT 'MANUAL',
                slug TEXT NOT NULL DEFAULT '',
                carryForwardReason TEXT,
                counterId TEXT,
                recurrenceIntervalWeeks INTEGER NOT NULL DEFAULT 1,
                recurrenceDayOfMonth INTEGER,
                isCommitment INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY(weekId) REFERENCES weeks(id) ON DELETE CASCADE,
                FOREIGN KEY(aspectId) REFERENCES aspects(id) ON DELETE SET NULL,
                FOREIGN KEY(categoryId) REFERENCES categories(id) ON DELETE SET NULL
            )
        """.trimIndent())
        db.execSQL("""
            INSERT INTO tasks_new (
                id, weekId, title, aspectId, categoryId, priority, dueDate, hardDeadline,
                status, resourceValue, completedAt, carriedFromTaskId, createdAt, isRecurring,
                estimatedMinutes, carriedCount, sortOrder, isManuallyAdded, operationId, source,
                slug, carryForwardReason, counterId, recurrenceIntervalWeeks, recurrenceDayOfMonth,
                isCommitment
            )
            SELECT
                id, weekId, title, aspectId, categoryId, priority, dueDate, hardDeadline,
                status, resourceValue, completedAt, carriedFromTaskId, createdAt, isRecurring,
                estimatedMinutes, carriedCount, sortOrder, isManuallyAdded, projectId, source,
                slug, carryForwardReason, counterId, recurrenceIntervalWeeks, recurrenceDayOfMonth,
                isCommitment
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
        db.execSQL("CREATE INDEX IF NOT EXISTS index_tasks_operationId ON tasks(operationId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_tasks_source ON tasks(source)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_tasks_counterId ON tasks(counterId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_tasks_weekId_slug ON tasks(weekId, slug)")
    }
}

internal val MIGRATION_54_55 = object : Migration(54, 55) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Two additions, both about the half of a week the task board can't see.
        //
        // The check-in's sensory question becomes relative, the way energy already is: instead of
        // living only behind "Add exact ratings" as a 1-10 strip, it's asked every time as
        // better/neutral/worse and stepped from the last reading (WellnessRepository.deriveSensory),
        // so the sensory series the reports read stays continuous. sensoryTrend is nullable — SLEEP
        // rows never carry one, nor do check-ins written before this. sensoryDerived is NOT NULL
        // with a SQL DEFAULT matching @ColumnInfo(defaultValue), like energyDerived before it
        // (MIGRATION_49_50): every sensory value already stored was typed in by hand.
        db.execSQL("ALTER TABLE wellness_checkins ADD COLUMN sensoryTrend TEXT")
        db.execSQL("ALTER TABLE wellness_checkins ADD COLUMN sensoryDerived INTEGER NOT NULL DEFAULT 0")

        // The week-close ritual gains two questions beside the self-rating: whether a mental reset
        // was actually achieved, and how spent you are overall (1-10, the wellness scale). Both
        // nullable and unbackfilled — the prompts can be skipped, and a week closed before they
        // existed never answered them, which is not the same as answering "no" or "not tired".
        db.execSQL("ALTER TABLE week_snapshots ADD COLUMN mentalReset INTEGER")
        db.execSQL("ALTER TABLE week_snapshots ADD COLUMN exhaustion INTEGER")
    }
}
