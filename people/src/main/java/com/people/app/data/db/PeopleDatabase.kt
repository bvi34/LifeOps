package com.people.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.people.app.data.db.dao.PeopleDao
import com.people.app.data.db.entities.ImportantDateEntity
import com.people.app.data.db.entities.PersonEntity
import com.people.app.data.db.entities.PersonNoteEntity

/**
 * The schema version, in one place — the backup manifest reads it from here rather than repeating
 * the number, so it can't drift from the schema the copied file was written at.
 */
const val PEOPLE_DB_VERSION = 1

/**
 * People's own store. It holds the household directory and nothing about what other apps do with it:
 * no tasks, no readings, no calendar. Peers keep their own rows and reconcile with this one over the
 * sync seam (`sync/`), which is why this database is the *directory* rather than the *authority* —
 * both ends can edit, and merge decides.
 */
@Database(
    entities = [PersonEntity::class, PersonNoteEntity::class, ImportantDateEntity::class],
    version = PEOPLE_DB_VERSION,
    exportSchema = true
)
abstract class PeopleDatabase : RoomDatabase() {

    abstract fun peopleDao(): PeopleDao

    companion object {
        const val DB_NAME = "people.db"

        @Volatile
        private var instance: PeopleDatabase? = null

        fun getInstance(context: Context): PeopleDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    PeopleDatabase::class.java,
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
