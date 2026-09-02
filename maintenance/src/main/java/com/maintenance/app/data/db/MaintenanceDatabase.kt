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
import com.maintenance.app.data.db.entities.RecallEntity
import com.maintenance.app.data.db.entities.ServiceRecordEntity
import com.maintenance.app.data.db.entities.UpkeepPlanEntity

/**
 * The schema version, in one place — the backup manifest reads it from here rather than repeating
 * the number, so it can't drift from the schema the copied file was written at.
 */
const val MAINTENANCE_DB_VERSION = 3

/**
 * Maintenance's own store: the things you own and everything owed on them.
 *
 * One database rather than one per concern, because the questions cross them — "what has this
 * truck cost me per mile", "what is owed against what it's worth", "what does the whole household
 * need this fortnight" — and none of those can be asked across two SQLite files without doing the
 * join in Kotlin.
 */
// The migrations are `internal` rather than private so `MaintenanceMigrationTest` can run the very
// objects that ship, rather than a copy of them that could drift.

/**
 * An upkeep plan learns to put itself on the LifeOps week: whether it should, which task currently
 * stands for it, and which occurrence that task was published for. All three are additive and
 * nullable-or-defaulted, so an install made before the seam existed opens with every plan
 * publishing (the default) and nothing yet published.
 */
internal val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // NOT NULL with a SQL DEFAULT matching @ColumnInfo(defaultValue), or Room's post-migration
        // schema validation refuses to open the database.
        db.execSQL("ALTER TABLE upkeep_plans ADD COLUMN publishToLifeOps INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE upkeep_plans ADD COLUMN lifeOpsTaskId TEXT")
        db.execSQL("ALTER TABLE upkeep_plans ADD COLUMN publishedDueDay INTEGER")
    }
}

/**
 * A schedule can now be applied from a pack rather than typed, which brings three things with it:
 * odometer milestones (as distinct from a cadence), what kind of thing a plan is (work, or a prompt
 * to read the meter), and where it came from. Vehicles also learn about recalls — a table of their
 * own, and a stamp on the asset for when NHTSA was last asked.
 *
 * All additive: an install made before any of this opens with plans that are ordinary upkeep, typed
 * by hand, with no milestones and no recalls — which is exactly what they were.
 */
internal val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE upkeep_plans ADD COLUMN atMeter TEXT")
        // NOT NULL with a SQL DEFAULT matching @ColumnInfo(defaultValue), or Room's post-migration
        // validation refuses to open the database.
        db.execSQL("ALTER TABLE upkeep_plans ADD COLUMN kind TEXT NOT NULL DEFAULT 'upkeep'")
        db.execSQL("ALTER TABLE upkeep_plans ADD COLUMN sourcePack TEXT")
        db.execSQL("ALTER TABLE upkeep_plans ADD COLUMN sourceItem TEXT")
        db.execSQL("ALTER TABLE assets ADD COLUMN recallsCheckedAt INTEGER")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS recalls (
                assetId TEXT NOT NULL,
                campaignNumber TEXT NOT NULL,
                component TEXT NOT NULL,
                summary TEXT NOT NULL,
                consequence TEXT,
                remedy TEXT,
                manufacturer TEXT,
                reportedOnEpochDay INTEGER,
                parkIt INTEGER NOT NULL,
                parkOutside INTEGER NOT NULL,
                fetchedAt INTEGER NOT NULL,
                acknowledgedAt INTEGER,
                PRIMARY KEY(assetId, campaignNumber),
                FOREIGN KEY(assetId) REFERENCES assets(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_recalls_assetId ON recalls(assetId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_recalls_acknowledgedAt ON recalls(acknowledgedAt)")
    }
}

@Database(
    entities = [
        AssetEntity::class,
        AssetAttributeEntity::class,
        UpkeepPlanEntity::class,
        ServiceRecordEntity::class,
        MeterReadingEntity::class,
        RecallEntity::class,
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
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { instance = it }
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
