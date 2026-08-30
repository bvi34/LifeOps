package com.maintenance.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
const val MAINTENANCE_DB_VERSION = 2

/**
 * Maintenance's own store: the things you own and everything owed on them.
 *
 * One database rather than one per concern, because the questions cross them — "what has this
 * truck cost me per mile", "what is owed against what it's worth", "what does the whole household
 * need this fortnight" — and none of those can be asked across two SQLite files without doing the
 * join in Kotlin.
 */
/**
 * An upkeep plan learns to put itself on the LifeOps week: whether it should, which task currently
 * stands for it, and which occurrence that task was published for. All three are additive and
 * nullable-or-defaulted, so an install made before the seam existed opens with every plan
 * publishing (the default) and nothing yet published.
 */
private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // NOT NULL with a SQL DEFAULT matching @ColumnInfo(defaultValue), or Room's post-migration
        // schema validation refuses to open the database.
        db.execSQL("ALTER TABLE upkeep_plans ADD COLUMN publishToLifeOps INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE upkeep_plans ADD COLUMN lifeOpsTaskId TEXT")
        db.execSQL("ALTER TABLE upkeep_plans ADD COLUMN publishedDueDay INTEGER")
    }
}

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
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
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
