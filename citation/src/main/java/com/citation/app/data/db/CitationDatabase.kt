package com.citation.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Citation's **sovereign** database: books, chapters, notes, highlights, key watermarks, and sync
 * state. It lives in the app's default (backed-up) database directory — the auto-eviction job is
 * never pointed here, so notes and owned content are structurally safe from reclamation (the
 * disposable RR chapter bodies live in a separate file store instead).
 */
@Database(
    entities = [
        BookEntity::class,
        ChapterEntity::class,
        HighlightEntity::class,
        NoteEntity::class,
        KeyWatermarkEntity::class,
        SyncStateEntity::class,
        RrFictionEntity::class,
        RrChapterMetaEntity::class
    ],
    version = 3,
    exportSchema = true
)
abstract class CitationDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun chapterDao(): ChapterDao
    abstract fun highlightDao(): HighlightDao
    abstract fun noteDao(): NoteDao
    abstract fun syncStateDao(): SyncStateDao
    abstract fun royalRoadDao(): RoyalRoadDao

    companion object {
        @Volatile private var instance: CitationDatabase? = null

        /** v1 → v2: add the nullable `lastOpenedAt` column that powers the Read tab's resume. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN lastOpenedAt INTEGER")
            }
        }

        /** v2 → v3: add the notes `tagsJson` column (the free-form tag layer). Defaults to `[]`. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN tagsJson TEXT NOT NULL DEFAULT '[]'")
            }
        }

        fun get(context: Context): CitationDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    CitationDatabase::class.java,
                    "citation.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { instance = it }
            }

        /**
         * Close and forget the singleton so the underlying `citation.db` file can be replaced
         * wholesale (used by the sandbox restore, which swaps the file rather than merging rows).
         * The next [get] rebuilds against the restored file. Any DAO/repository already holding the
         * old handle is stale afterwards — a Citation restart is expected after a restore.
         */
        fun closeInstance() {
            synchronized(this) {
                instance?.close()
                instance = null
            }
        }
    }
}
