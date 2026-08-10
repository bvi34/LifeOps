package com.advisor.app.data.memory

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The **dedicated long-term memory store** — deliberately its own database (`advisor_memory.db`),
 * separate from the operational `advisor.db` (permissions + conversation), because memory is the
 * part meant to grow without bound and be queried hard by tag. Keeping it apart means its schema and
 * lifecycle can evolve on their own, and it is backed up as its own archive entry.
 */
@Database(
    entities = [
        MemoryEntity::class,
        MemoryTagEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class AdvisorMemoryDatabase : RoomDatabase() {

    abstract fun memoryDao(): MemoryDao

    companion object {
        const val DB_NAME = "advisor_memory.db"

        @Volatile
        private var instance: AdvisorMemoryDatabase? = null

        fun getInstance(context: Context): AdvisorMemoryDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AdvisorMemoryDatabase::class.java,
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
