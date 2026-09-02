package com.repository.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.repository.app.data.db.dao.RepositoryDao
import com.repository.app.data.db.entities.DocumentEntity

/** The schema version, in one place — the backup manifest reads it from here. */
const val REPOSITORY_DB_VERSION = 1

/**
 * Repository's own store: one table of rows that name files.
 *
 * One table is not an oversight. Everything else a document could carry — what it is about, who it
 * belongs to, when it happened — is either a label the owning app supplies or something a person
 * typed, and inventing tables to hold facts nobody has is how a filing app becomes a form.
 */
@Database(entities = [DocumentEntity::class], version = REPOSITORY_DB_VERSION, exportSchema = true)
abstract class RepositoryDatabase : RoomDatabase() {

    abstract fun repositoryDao(): RepositoryDao

    companion object {
        const val DB_NAME = "repository.db"

        @Volatile
        private var instance: RepositoryDatabase? = null

        fun getInstance(context: Context): RepositoryDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    RepositoryDatabase::class.java,
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
