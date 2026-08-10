package com.advisor.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.advisor.app.data.db.dao.AdvisorDao
import com.advisor.app.data.db.entities.AdvisorMessageEntity
import com.advisor.app.data.db.entities.AppPermissionEntity

/**
 * Advisor's own tiny store. It holds only what Advisor itself owns — the per-app read permissions
 * the user has granted and the saved conversation. The knowledge it reasons over is **not** stored
 * here; that is read live from the other apps' databases at query time, so nothing is duplicated and
 * a stale copy can never leak data the user later revoked.
 *
 * The whole file is backed up wholesale by
 * [com.advisor.app.backup.AdvisorBackupContributor], the same way LifeOps/Citation/Logistics do.
 */
@Database(
    entities = [
        AppPermissionEntity::class,
        AdvisorMessageEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class AdvisorDatabase : RoomDatabase() {

    abstract fun advisorDao(): AdvisorDao

    companion object {
        const val DB_NAME = "advisor.db"

        @Volatile
        private var instance: AdvisorDatabase? = null

        fun getInstance(context: Context): AdvisorDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AdvisorDatabase::class.java,
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
