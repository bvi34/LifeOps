package com.logistics.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.logistics.app.data.db.dao.PantryDao
import com.logistics.app.data.db.entities.GroceryItemEntity
import com.logistics.app.data.db.entities.ImportBatchEntity
import com.logistics.app.data.db.entities.PantryItemEntity
import com.logistics.app.data.db.entities.PantryTxnEntity

/**
 * Logistics' own store. It holds only what LifeOps doesn't: pantry stock, the movement ledger, and
 * import provenance. Foods and recipes are read from LifeOps' database (same process) via
 * [com.logistics.app.data.repository.LifeOpsCatalog], so nothing is duplicated here.
 *
 * The whole file is backed up wholesale by [com.logistics.app.backup.LogisticsBackupContributor],
 * exactly like LifeOps and Citation, so the Operations Sandbox "back up everything" stays complete.
 */
/**
 * The schema version, in one place. The backup manifest records the version the copied
 * `logistics.db` was written at, so [com.logistics.app.backup.LogisticsBackupContributor] reads it
 * from here rather than repeating the number — a hand-copied version drifts the moment a migration
 * lands, and a manifest that lies about its schema is worse than no manifest.
 */
const val LOGISTICS_DB_VERSION = 3

@Database(
    entities = [
        PantryItemEntity::class,
        PantryTxnEntity::class,
        ImportBatchEntity::class,
        GroceryItemEntity::class
    ],
    version = LOGISTICS_DB_VERSION,
    exportSchema = true
)
abstract class LogisticsDatabase : RoomDatabase() {

    abstract fun pantryDao(): PantryDao

    companion object {
        const val DB_NAME = "logistics.db"

        /** v2 adds pantry_txns.mealLogId so a meal's consumption rows can be grouped and replayed by
         *  the History screen. Existing rows keep NULL (they predate meal grouping). */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pantry_txns ADD COLUMN mealLogId TEXT DEFAULT NULL")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_pantry_txns_mealLogId ON pantry_txns(mealLogId)")
            }
        }

        /** v3 adds the grocery_items table — the shopping list Logistics builds from low stock,
         *  recipes, and hand-added lines, then purchases back into the pantry. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS grocery_items (
                        id TEXT NOT NULL PRIMARY KEY,
                        foodItemId TEXT,
                        name TEXT NOT NULL,
                        quantity REAL NOT NULL,
                        unit TEXT NOT NULL,
                        category TEXT,
                        source TEXT NOT NULL,
                        recipeId TEXT,
                        checked INTEGER NOT NULL,
                        note TEXT,
                        createdAt TEXT NOT NULL,
                        updatedAt TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_grocery_items_name ON grocery_items(name)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_grocery_items_foodItemId ON grocery_items(foodItemId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_grocery_items_checked ON grocery_items(checked)")
            }
        }

        @Volatile
        private var instance: LogisticsDatabase? = null

        fun getInstance(context: Context): LogisticsDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    LogisticsDatabase::class.java,
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
