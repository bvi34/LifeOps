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
        RrChapterMetaEntity::class,
        Ao3WorkEntity::class,
        Ao3ChapterMetaEntity::class
    ],
    version = 4,
    exportSchema = true
)
abstract class CitationDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun chapterDao(): ChapterDao
    abstract fun highlightDao(): HighlightDao
    abstract fun noteDao(): NoteDao
    abstract fun syncStateDao(): SyncStateDao
    abstract fun royalRoadDao(): RoyalRoadDao
    abstract fun ao3Dao(): Ao3Dao

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

        /**
         * v3 → v4: add the Archive of Our Own catalog tables (`ao3_works`/`ao3_chapters`), the AO3
         * mirror of `rr_fictions`/`rr_chapters`. Chapter bodies stay in the disposable file store;
         * only catalog + cached-flag rows live here.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Column definitions must match Room's generated schema exactly — Kotlin constructor
                // defaults are NOT SQL defaults, so these carry no DEFAULT clauses (mirrors rr_*).
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `ao3_works` (" +
                        "`workId` INTEGER NOT NULL, `title` TEXT NOT NULL, `author` TEXT, " +
                        "`isFavorite` INTEGER NOT NULL, `currentOrdinal` INTEGER NOT NULL, " +
                        "`expectedCount` INTEGER NOT NULL, `lastReadAt` INTEGER NOT NULL, " +
                        "`bookKey` TEXT, PRIMARY KEY(`workId`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `ao3_chapters` (" +
                        "`workId` INTEGER NOT NULL, `ordinal` INTEGER NOT NULL, `chapterId` INTEGER NOT NULL, " +
                        "`title` TEXT NOT NULL, `url` TEXT NOT NULL, `cached` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`workId`, `ordinal`))"
                )
            }
        }

        fun get(context: Context): CitationDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    CitationDatabase::class.java,
                    "citation.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build().also { instance = it }
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
