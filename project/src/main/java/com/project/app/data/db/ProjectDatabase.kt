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
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ProjectDatabase::class.java,
                    DB_NAME
                ).build().also { instance = it }
            }

        /** Close and drop the singleton so a restore can swap the underlying file. */
        fun closeInstance() {
            synchronized(this) {
                instance?.close()
                instance = null
            }
        }
    }
}
