package com.project.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.project.app.data.db.dao.ProjectDao
import com.project.app.data.db.entities.BoardCardEntity
import com.project.app.data.db.entities.BoardColumnEntity
import com.project.app.data.db.entities.DocBlockEntity
import com.project.app.data.db.entities.DocEntity
import com.project.app.data.db.entities.LoreEntryEntity
import com.project.app.data.db.entities.OutlineNodeEntity
import com.project.app.data.db.entities.ProjectEntity
import com.project.app.data.db.entities.TimelineEventEntity

/**
 * The schema version, in one place — the backup manifest reads it from here rather than repeating
 * the number, so it can't drift from the schema the copied file was written at.
 */
const val PROJECT_DB_VERSION = 1

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
@Database(
    entities = [
        ProjectEntity::class,
        OutlineNodeEntity::class,
        DocEntity::class,
        DocBlockEntity::class,
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

        /** Close and drop the singleton so a restore can swap the underlying file. */
        fun closeInstance() {
            synchronized(this) {
                instance?.close()
                instance = null
            }
        }
    }
}
