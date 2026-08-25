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
const val CITATION_DB_VERSION = 7

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
        CollectionEntity::class,
        CollectionMemberEntity::class,
        OpdsCatalogEntity::class,
        BookmarkEntity::class,
        ReadingPaceEntity::class
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
    abstract fun collectionDao(): CollectionDao
    abstract fun opdsCatalogDao(): OpdsCatalogDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun readingPaceDao(): ReadingPaceDao

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

        /**
         * v5 → v6: the book stops being a title and an author.
         *
         * `books` gains the shelf metadata a library screen needs (publisher, date, blurb,
         * subjects, series, cover, chapter count) plus the publisher's nested contents; `chapters`
         * gains the structured view over its unchanged text. All of it is **derived** — every
         * column here is recoverable by re-parsing the stored source file — so each is nullable or
         * defaulted and a book imported before this migration simply reads as a book with no
         * structure, exactly as it did yesterday. Nothing needs backfilling for the app to work.
         *
         * Three new tables arrive with it: user-made shelves, their membership, and saved OPDS
         * catalogs. Catalog *credentials* are deliberately not among them — they live in the
         * Keystore-backed store, so a copied database is not a way into someone's server.
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN publisher TEXT")
                db.execSQL("ALTER TABLE books ADD COLUMN published TEXT")
                db.execSQL("ALTER TABLE books ADD COLUMN description TEXT")
                db.execSQL("ALTER TABLE books ADD COLUMN subjectsJson TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE books ADD COLUMN series TEXT")
                db.execSQL("ALTER TABLE books ADD COLUMN seriesIndex REAL")
                db.execSQL("ALTER TABLE books ADD COLUMN coverPath TEXT")
                db.execSQL("ALTER TABLE books ADD COLUMN chapterCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE books ADD COLUMN tocJson TEXT")

                db.execSQL("ALTER TABLE chapters ADD COLUMN blocksJson TEXT")
                db.execSQL("ALTER TABLE chapters ADD COLUMN anchorsJson TEXT")

                // Books already in the library know their chapter count; fill it so progress reads
                // correctly on the first run rather than after each book is next opened.
                db.execSQL(
                    "UPDATE books SET chapterCount = " +
                        "(SELECT COUNT(*) FROM chapters WHERE chapters.bookKey = books.key)"
                )

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `collections` (" +
                        "`id` TEXT NOT NULL, `name` TEXT NOT NULL, `position` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `collection_members` (" +
                        "`collectionId` TEXT NOT NULL, `bookKey` TEXT NOT NULL, `addedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`collectionId`, `bookKey`), " +
                        "FOREIGN KEY(`collectionId`) REFERENCES `collections`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, " +
                        "FOREIGN KEY(`bookKey`) REFERENCES `books`(`key`) ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_collection_members_bookKey` ON `collection_members` (`bookKey`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_collection_members_collectionId` ON `collection_members` (`collectionId`)")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `opds_catalogs` (" +
                        "`id` TEXT NOT NULL, `name` TEXT NOT NULL, `url` TEXT NOT NULL, " +
                        "`position` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, " +
                        "`lastOpenedAt` INTEGER, PRIMARY KEY(`id`))"
                )
            }
        }

        /**
         * v6 → v7: saved places, and how fast this reader reads.
         *
         * `bookmarks` is sovereign — it is something the reader made — so it nulls rather than
         * cascades when a book is removed, matching how notes and highlights already outlive their
         * source; the frozen snippet keeps it legible either way. `reading_pace` is the opposite:
         * pure observation, safe to lose, and it rebuilds itself within an hour of reading.
         *
         * `books.progressFraction` comes with them: the library cannot compute character-accurate
         * progress without loading every chapter, so the reader writes what it measured and the
         * shelf reads it back. Books never opened since carry 0 and fall back to the chapter-count
         * estimate, which is what the shelf showed before.
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN progressFraction REAL NOT NULL DEFAULT 0")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `bookmarks` (" +
                        "`key` TEXT NOT NULL, `bookKey` TEXT, `chapterOrdinal` INTEGER NOT NULL, " +
                        "`charOffset` INTEGER NOT NULL, `snippet` TEXT NOT NULL, " +
                        "`chapterTitle` TEXT NOT NULL, `label` TEXT, `createdAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`key`), " +
                        "FOREIGN KEY(`bookKey`) REFERENCES `books`(`key`) ON UPDATE NO ACTION ON DELETE SET NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_bookmarks_bookKey` ON `bookmarks` (`bookKey`)")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `reading_pace` (" +
                        "`bookKey` TEXT NOT NULL, `characters` INTEGER NOT NULL, " +
                        "`millis` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`bookKey`))"
                )
            }
        }

        fun get(context: Context): CitationDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    CitationDatabase::class.java,
                    "citation.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
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
