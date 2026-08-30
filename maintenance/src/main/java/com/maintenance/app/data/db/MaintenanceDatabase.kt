package com.maintenance.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.maintenance.app.data.db.dao.MaintenanceDao
import com.maintenance.app.data.db.entities.AssetAttributeEntity
import com.maintenance.app.data.db.entities.AssetEntity
import com.maintenance.app.data.db.entities.CoverageEntity
import com.maintenance.app.data.db.entities.LoanEntity
import com.maintenance.app.data.db.entities.MeterReadingEntity
import com.maintenance.app.data.db.entities.ServiceRecordEntity
import com.maintenance.app.data.db.entities.UpkeepPlanEntity

/**
 * The schema version, in one place — the backup manifest reads it from here rather than repeating
 * the number, so it can't drift from the schema the copied file was written at.
 */
const val MAINTENANCE_DB_VERSION = 1

/**
 * Maintenance's own store: the things you own and everything owed on them.
 *
 * One database rather than one per concern, because the questions cross them — "what has this
 * truck cost me per mile", "what is owed against what it's worth", "what does the whole household
 * need this fortnight" — and none of those can be asked across two SQLite files without doing the
 * join in Kotlin.
 */
@Database(
    entities = [
        AssetEntity::class,
        AssetAttributeEntity::class,
        UpkeepPlanEntity::class,
        ServiceRecordEntity::class,
        MeterReadingEntity::class,
        LoanEntity::class,
        CoverageEntity::class
    ],
    version = MAINTENANCE_DB_VERSION,
    exportSchema = true
)
abstract class MaintenanceDatabase : RoomDatabase() {

    abstract fun maintenanceDao(): MaintenanceDao

    companion object {
        const val DB_NAME = "maintenance.db"

        @Volatile
        private var instance: MaintenanceDatabase? = null

        fun getInstance(context: Context): MaintenanceDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    MaintenanceDatabase::class.java,
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
