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
/**
 * The schema version, in one place. [com.citation.app.backup.CitationBackupContributor] records it
 * in the backup manifest as the version the copied `citation.db` was written at, and reads it from
 * here rather than repeating the number — the same version stated twice drifts the moment a
 * migration lands.
 */
const val CITATION_DB_VERSION = 5

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
    version = CITATION_DB_VERSION,
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

        /**
         * v3 → v4: added AO3 catalog tables for a borrowed-serial scraping model. That approach was
         * abandoned for AO3's official EPUB download (AO3 works are now owned EPUB snapshots in the
         * `books`/`chapters` tables like any EPUB), so these tables are dropped again in v4 → v5. This
         * step is kept so a device that already ran v4 has a table to drop, and a fresh v3 device
         * takes the same create-then-drop path.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
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

        /** v4 → v5: drop the abandoned AO3 borrowed-serial tables (AO3 is now an owned EPUB snapshot). */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `ao3_chapters`")
                db.execSQL("DROP TABLE IF EXISTS `ao3_works`")
            }
        }

        fun get(context: Context): CitationDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    CitationDatabase::class.java,
                    "citation.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    // A restore can swap in a `citation.db` written by a *newer* Citation build than
                    // the one now installed (e.g. reinstalling an older APK, then restoring). Room's
                    // default reaction to that downgrade is to throw on open — a permanent boot-crash.
                    // There is no data-preserving way to run a schema backwards, so degrade instead of
                    // crash: drop and recreate the schema at this build's version. The owned EPUB/PDF
                    // and sync-envelope files under `filesDir/sovereign` are restored separately and
                    // are unaffected; only the (newer, unreadable) sovereign DB rows are lost.
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .build().also { instance = it }
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
