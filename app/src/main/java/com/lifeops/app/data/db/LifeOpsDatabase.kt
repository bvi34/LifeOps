package com.lifeops.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.lifeops.app.data.db.dao.*
import com.lifeops.app.data.db.entities.*

private val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tasks ADD COLUMN isRecurring INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE tasks ADD COLUMN estimatedMinutes INTEGER")
        db.execSQL("ALTER TABLE tasks ADD COLUMN carriedCount INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE tasks ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0")
    }
}

@Database(
    entities = [
        AspectEntity::class,
        CategoryEntity::class,
        WeekEntity::class,
        TaskEntity::class,
        WeekSnapshotEntity::class,
        GameResourceEntity::class,
        GameResourceMappingEntity::class,
        NotificationEntity::class,
        TaskNoteEntity::class,
        TimeEntryEntity::class,
        ResourceTransactionEntity::class
    ],
    version = 5,
    exportSchema = true
)
abstract class LifeOpsDatabase : RoomDatabase() {
    abstract fun aspectDao(): AspectDao
    abstract fun categoryDao(): CategoryDao
    abstract fun weekDao(): WeekDao
    abstract fun taskDao(): TaskDao
    abstract fun weekSnapshotDao(): WeekSnapshotDao
    abstract fun gameResourceDao(): GameResourceDao
    abstract fun gameResourceMappingDao(): GameResourceMappingDao
    abstract fun notificationDao(): NotificationDao
    abstract fun taskNoteDao(): TaskNoteDao
    abstract fun timeEntryDao(): TimeEntryDao
    abstract fun resourceTransactionDao(): ResourceTransactionDao

    companion object {
        @Volatile private var INSTANCE: LifeOpsDatabase? = null

        fun getInstance(context: Context): LifeOpsDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    LifeOpsDatabase::class.java,
                    "lifeops.db"
                )
                    .addMigrations(MIGRATION_4_5)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
