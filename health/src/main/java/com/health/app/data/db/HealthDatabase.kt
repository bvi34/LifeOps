package com.health.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
const val HEALTH_DB_VERSION = 2

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

        /**
         * v2 makes Health a peer on the People sync seam: a profile gains the cross-peer
         * [ProfileEntity.personKey] and the [ProfileEntity.syncVersion] stamp that decides what gets
         * published. Existing profiles are seeded at version 1 so the people already being tracked
         * are offered to the household directory on the first round, and keyed from their row id so
         * they publish under a key the other peers will keep.
         *
         * Health never shipped at v1, so in practice this migration runs for nobody — it exists
         * because a schema that changes without one is a crash waiting for whoever did install it.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE profiles ADD COLUMN personKey TEXT")
                db.execSQL("ALTER TABLE profiles ADD COLUMN syncVersion INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE profiles SET personKey = id WHERE personKey IS NULL")
                db.execSQL("UPDATE profiles SET syncVersion = 1 WHERE syncVersion = 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_profiles_syncVersion ON profiles(syncVersion)")
            }
        }

        @Volatile
        private var instance: HealthDatabase? = null

        fun getInstance(context: Context): HealthDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    HealthDatabase::class.java,
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
