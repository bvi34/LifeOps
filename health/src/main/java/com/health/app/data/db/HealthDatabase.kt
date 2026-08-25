package com.health.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.health.app.data.db.dao.HealthDao
import com.health.app.data.db.entities.CareNoteEntity
import com.health.app.data.db.entities.DoseEntity
import com.health.app.data.db.entities.EpisodeEntity
import com.health.app.data.db.entities.MedicationEntity
import com.health.app.data.db.entities.ProfileEntity
import com.health.app.data.db.entities.ReadingEntity
import com.health.app.data.db.entities.SymptomEntity

/**
 * The schema version, in one place. The backup manifest records the version the copied `health.db`
 * was written at, so [com.health.app.backup.HealthBackupContributor] reads it from here rather than
 * repeating the number — a hand-copied version drifts the moment a migration lands, and a manifest
 * that lies about its schema is worse than no manifest. (Both LifeOps and Logistics learned this the
 * hard way; Health starts where they ended up.)
 */
const val HEALTH_DB_VERSION = 1

/**
 * Health's own store: people, and everything recorded about them. Nothing here is shared with, or
 * sourced from, another app's database — no other module in the suite owns household health data —
 * so unlike Logistics there is no cross-app catalog bridge, only this one file.
 *
 * The whole file is backed up wholesale by [com.health.app.backup.HealthBackupContributor], exactly
 * like LifeOps, Citation and Logistics, so the Operations Sandbox "back up everything" stays
 * complete as this schema grows.
 */
@Database(
    entities = [
        ProfileEntity::class,
        ReadingEntity::class,
        SymptomEntity::class,
        MedicationEntity::class,
        DoseEntity::class,
        EpisodeEntity::class,
        CareNoteEntity::class
    ],
    version = HEALTH_DB_VERSION,
    exportSchema = true
)
abstract class HealthDatabase : RoomDatabase() {

    abstract fun healthDao(): HealthDao

    companion object {
        const val DB_NAME = "health.db"

        @Volatile
        private var instance: HealthDatabase? = null

        fun getInstance(context: Context): HealthDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    HealthDatabase::class.java,
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
