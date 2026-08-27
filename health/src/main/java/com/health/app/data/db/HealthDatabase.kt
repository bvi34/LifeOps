package com.health.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.health.app.data.db.dao.HealthDao
import com.health.app.data.db.entities.AllergyEntity
import com.health.app.data.db.entities.CabinetItemEntity
import com.health.app.data.db.entities.CareNoteEntity
import com.health.app.data.db.entities.ConditionEntity
import com.health.app.data.db.entities.DocumentEntity
import com.health.app.data.db.entities.DoseEntity
import com.health.app.data.db.entities.DrugFactsEntity
import com.health.app.data.db.entities.EpisodeEntity
import com.health.app.data.db.entities.ImmunizationEntity
import com.health.app.data.db.entities.InsuranceMemberEntity
import com.health.app.data.db.entities.InsurancePlanEntity
import com.health.app.data.db.entities.MedicationEntity
import com.health.app.data.db.entities.NetworkCheckEntity
import com.health.app.data.db.entities.ProfileEntity
import com.health.app.data.db.entities.ProfileTombstoneEntity
import com.health.app.data.db.entities.ProviderEntity
import com.health.app.data.db.entities.ProviderLinkEntity
import com.health.app.data.db.entities.ReadingEntity
import com.health.app.data.db.entities.SymptomEntity

/**
 * The schema version, in one place. The backup manifest records the version the copied `health.db`
 * was written at, so [com.health.app.backup.HealthBackupContributor] reads it from here rather than
 * repeating the number — a hand-copied version drifts the moment a migration lands, and a manifest
 * that lies about its schema is worse than no manifest. (Both LifeOps and Logistics learned this the
 * hard way; Health starts where they ended up.)
 */
const val HEALTH_DB_VERSION = 9

/**
 * Health's own store: people, everything recorded about them, the medicine cabinet those records
 * draw on, the coverage that pays for it, the care team that provides it, and — since v7 — the
 * standing record of what is true about a person between illnesses.
 * Nothing here is shared with, or
 * sourced from, another app's database — no other module in the suite owns household health data —
 * so unlike Logistics there is no cross-app catalog bridge, only this one file.
 *
 * The schema steps themselves live in [HealthMigrations] — nine of them, and more lines than this
 * whole file. A reader opening this one is looking for the table list or the singleton, not for what
 * v4 did to the medicine cabinet.
 *
 * The whole file is backed up wholesale by [com.health.app.backup.HealthBackupContributor], exactly
 * like LifeOps, Citation and Logistics, so the Operations Sandbox "back up everything" stays
 * complete as this schema grows.
 */
@Database(
    entities = [
        ProfileEntity::class,
        ProfileTombstoneEntity::class,
        ReadingEntity::class,
        SymptomEntity::class,
        MedicationEntity::class,
        DoseEntity::class,
        EpisodeEntity::class,
        CareNoteEntity::class,
        DrugFactsEntity::class,
        CabinetItemEntity::class,
        InsurancePlanEntity::class,
        InsuranceMemberEntity::class,
        ProviderEntity::class,
        ProviderLinkEntity::class,
        NetworkCheckEntity::class,
        AllergyEntity::class,
        ConditionEntity::class,
        ImmunizationEntity::class,
        DocumentEntity::class
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
                ).addMigrations(*HealthMigrations.ALL)
                    .build().also { instance = it }
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
