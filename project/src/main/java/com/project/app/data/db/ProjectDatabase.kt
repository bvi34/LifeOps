package com.project.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.project.app.data.db.dao.ProjectDao
import com.project.app.data.db.entities.BoardCardEntity
import com.project.app.data.db.entities.BoardColumnEntity
import com.project.app.data.db.entities.DocBlockEntity
import com.project.app.data.db.entities.DocEntity
import com.project.app.data.db.entities.DocRevisionBlockEntity
import com.project.app.data.db.entities.DocRevisionEntity
import com.project.app.data.db.entities.LoreEntryEntity
import com.project.app.data.db.entities.OutlineNodeEntity
import com.project.app.data.db.entities.ProjectEntity
import com.project.app.data.db.entities.TimelineEventEntity

/**
 * The schema version, in one place — the backup manifest reads it from here rather than repeating
 * the number, so it can't drift from the schema the copied file was written at.
 */
const val PROJECT_DB_VERSION = 3

/**
 * Project's own store: the shelf, and the five sections of everything on it.
 *
 * One database rather than one per section, because the sections are views of a single project and
 * the useful questions cross them — "which scenes have no document", "which cards are for cut
 * material", "how many words is this project" — and none of those can be asked across two SQLite
 * files without doing the join in Kotlin.
 *
 * Nothing here is synced. Project is not a peer on the suite's sync seam and does not read another
 * app's database: what it holds is authored in it, and there is no second writer to reconcile with.
 * That is why this file has no `syncVersion` column anywhere in it, and why adding one later would
 * be a change worth thinking about rather than a migration.
 */
/**
 * Version 2 keeps versions of a document.
 *
 * Purely additive: two new tables and nothing touched on the eight that were already there, because
 * what version 1 held is the writing itself and a migration is the last place to be rearranging it.
 * A phone that upgrades has no history for its existing documents, which is the truthful answer —
 * the versions start from the first destructive edit made after the upgrade, not from an invented
 * snapshot of the present dressed up as the past.
 */
internal val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `doc_revisions` (" +
                "`id` TEXT NOT NULL, `docId` TEXT NOT NULL, `reason` TEXT NOT NULL, " +
                "`wordCount` INTEGER NOT NULL, `savedAt` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
                "FOREIGN KEY(`docId`) REFERENCES `docs`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_doc_revisions_docId` ON `doc_revisions` (`docId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_doc_revisions_savedAt` ON `doc_revisions` (`savedAt`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `doc_revision_blocks` (" +
                "`id` TEXT NOT NULL, `revisionId` TEXT NOT NULL, `type` TEXT NOT NULL, " +
                "`text` TEXT NOT NULL, `checked` INTEGER NOT NULL, `sortOrder` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`), FOREIGN KEY(`revisionId`) REFERENCES `doc_revisions`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_doc_revision_blocks_revisionId` " +
                "ON `doc_revision_blocks` (`revisionId`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_doc_revision_blocks_sortOrder` " +
                "ON `doc_revision_blocks` (`sortOrder`)"
        )
    }
}

/**
 * Version 3 puts a due date on a board card.
 *
 * One nullable column, and null is the honest default: no card written before this existed had a
 * deadline, and inventing one from `createdAt` would fill a board with dates nobody chose.
 *
 * This is the first date anywhere in Project, and it deliberately arrives on the *card* rather than
 * on the outline or the project. A card is the work being done; a chapter is a part of the thing
 * being made, and giving structure a deadline is how an outline turns into a schedule. See
 * `logic/Due` for the rest of that argument.
 */
internal val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `board_cards` ADD COLUMN `dueOn` INTEGER")
    }
}

@Database(
    entities = [
        ProjectEntity::class,
        OutlineNodeEntity::class,
        DocEntity::class,
        DocBlockEntity::class,
        DocRevisionEntity::class,
        DocRevisionBlockEntity::class,
        LoreEntryEntity::class,
        TimelineEventEntity::class,
        BoardColumnEntity::class,
        BoardCardEntity::class
    ],
    version = PROJECT_DB_VERSION,
    exportSchema = true
)
abstract class ProjectDatabase : RoomDatabase() {

    abstract fun projectDao(): ProjectDao

    companion object {
        const val DB_NAME = "project.db"

        @Volatile
        private var instance: ProjectDatabase? = null

        fun getInstance(context: Context): ProjectDatabase =
            instance ?: synchronized(this) {
                instance ?: builder(context).build().also { instance = it }
            }

        /**
         * The one place this database is configured — and therefore the one place a migration is
         * ever added.
         *
         * [getInstance] opens the real file through it, and so does `ProjectMigrationTest`, which
         * builds a database from each exported schema and opens it here. That is the point of the
         * seam: a migration added below is a migration the upgrade test is already running, rather
         * than one it has to be told about separately and might not be.
         *
         * There is deliberately **no destructive fallback**. For most of the suite falling back
         * would cost a re-sync; here it would delete the only copy of somebody's writing, so a
         * missing migration must fail loudly on a developer's machine — which is what the test is
         * for — rather than quietly on a phone.
         */
        internal fun builder(
            context: Context,
            name: String = DB_NAME
        ): RoomDatabase.Builder<ProjectDatabase> =
            Room.databaseBuilder(context.applicationContext, ProjectDatabase::class.java, name)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)

        /** Close and drop the singleton so a restore can swap the underlying file. */
        fun closeInstance() {
            synchronized(this) {
                instance?.close()
                instance = null
            }
        }
    }
}
