package com.lifeops.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.lifeops.app.data.db.dao.*
import com.lifeops.app.data.db.entities.*
import com.lifeops.app.data.db.migrations.LIFEOPS_MIGRATIONS

/**
 * The schema version, in one place. [com.lifeops.app.backup.LifeOpsBackupContributor] records it in
 * the backup manifest as the version the copied `lifeops.db` was written at, and reads it from here
 * rather than repeating the number — the hand-copied one had drifted seven migrations behind.
 */
const val LIFEOPS_DB_VERSION = 56

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
        ResourceTransactionEntity::class,
        CostResourceEntity::class,
        TaskCostEntryEntity::class,
        OperationEntity::class,
        RunbookEntity::class,
        RunbookStepEntity::class,
        SubtaskEntity::class,
        TemplateEntity::class,
        TemplateTaskEntity::class,
        CounterEntity::class,
        CounterEventEntity::class,
        FoodItemEntity::class,
        RecipeEntity::class,
        RecipeIngredientEntity::class,
        FoodLogEntryEntity::class,
        WeeklyMenuItemEntity::class,
        BookEntity::class,
        BookNoteEntity::class,
        BookTimeEntryEntity::class,
        FutureOperationEntity::class,
        FutureOperationNoteEntity::class,
        WeatherLocationEntity::class,
        WeatherSnapshotEntity::class,
        WeatherAlertEntity::class,
        PersonEntity::class,
        PersonNoteEntity::class,
        PersonTombstoneEntity::class,
        TaskPersonEntity::class,
        TaskWeatherRequirementEntity::class,
        ActivityTemplateEntity::class,
        ActivityOverrideEntity::class,
        GameScoreEntity::class,
        WellnessCheckinEntity::class,
        TaskAttachmentEntity::class,
        BusyBlockEntity::class,
        PhoneActivityEventEntity::class,
        GameUnlockEntity::class,
        MilestoneEntity::class,
        BusyBlockPersonEntity::class,
        ObjectiveEntity::class,
        ObjectiveStepEntity::class
    ],
    version = LIFEOPS_DB_VERSION,
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
    abstract fun taskAttachmentDao(): TaskAttachmentDao
    abstract fun busyBlockDao(): BusyBlockDao
    abstract fun timeEntryDao(): TimeEntryDao
    abstract fun resourceTransactionDao(): ResourceTransactionDao
    abstract fun costResourceDao(): CostResourceDao
    abstract fun taskCostEntryDao(): TaskCostEntryDao
    abstract fun operationDao(): OperationDao
    abstract fun runbookDao(): RunbookDao
    abstract fun subtaskDao(): SubtaskDao
    abstract fun templateDao(): TemplateDao
    abstract fun counterDao(): CounterDao
    abstract fun foodItemDao(): FoodItemDao
    abstract fun recipeDao(): RecipeDao
    abstract fun foodLogDao(): FoodLogDao
    abstract fun weeklyMenuItemDao(): WeeklyMenuItemDao
    abstract fun bookDao(): BookDao
    abstract fun futureOperationDao(): FutureOperationDao
    abstract fun weatherDao(): WeatherDao
    abstract fun personDao(): PersonDao
    abstract fun activityTemplateDao(): ActivityTemplateDao
    abstract fun gameScoreDao(): GameScoreDao
    abstract fun wellnessCheckinDao(): WellnessCheckinDao
    abstract fun phoneActivityEventDao(): PhoneActivityEventDao
    abstract fun gameUnlockDao(): GameUnlockDao
    abstract fun milestoneDao(): MilestoneDao
    abstract fun objectiveDao(): ObjectiveDao

    companion object {
        @Volatile private var INSTANCE: LifeOpsDatabase? = null

        fun getInstance(context: Context): LifeOpsDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    LifeOpsDatabase::class.java,
                    "lifeops.db"
                )
                    .addMigrations(*LIFEOPS_MIGRATIONS)
                    .build()
                    .also { INSTANCE = it }
            }

        /**
         * Close and forget the singleton so the underlying `lifeops.db` file can be replaced
         * wholesale (used by the Operations Sandbox restore, which swaps the file rather than
         * merging rows, to guarantee a complete restore of every table). The next [getInstance]
         * rebuilds against the restored file; a LifeOps restart is expected after a restore.
         */
        fun closeInstance() {
            synchronized(this) {
                INSTANCE?.close()
                INSTANCE = null
            }
        }
    }
}
