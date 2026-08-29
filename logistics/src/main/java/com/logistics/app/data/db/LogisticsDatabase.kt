package com.logistics.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.logistics.app.data.db.dao.PantryDao
import com.logistics.app.data.db.dao.RecipeShotDao
import com.logistics.app.data.db.entities.GroceryItemEntity
import com.logistics.app.data.db.entities.ImportBatchEntity
import com.logistics.app.data.db.entities.PantryItemEntity
import com.logistics.app.data.db.entities.PantryTxnEntity
import com.logistics.app.data.db.entities.RecipeShotEntity

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
const val LOGISTICS_DB_VERSION = 4

@Database(
    entities = [
        PantryItemEntity::class,
        PantryTxnEntity::class,
        ImportBatchEntity::class,
        GroceryItemEntity::class,
        RecipeShotEntity::class
    ],
    version = LOGISTICS_DB_VERSION,
    exportSchema = true
)
abstract class LogisticsDatabase : RoomDatabase() {

    abstract fun pantryDao(): PantryDao

    abstract fun recipeShotDao(): RecipeShotDao

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

        /** v4 adds the recipe_shots table — the screenshots a recipe was read out of, or added to
         *  it afterwards. Only the file name lives here; the JPEG itself sits in
         *  `filesDir/recipe-shots/` (see [com.logistics.app.data.store.RecipeShotStore]), because a
         *  database copied whole by every backup is no place for megabytes of picture. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS recipe_shots (
                        id TEXT NOT NULL PRIMARY KEY,
                        recipeId TEXT NOT NULL,
                        fileName TEXT NOT NULL,
                        sortOrder INTEGER NOT NULL,
                        createdAt TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_recipe_shots_recipeId ON recipe_shots(recipeId)")
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
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build().also { instance = it }
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
