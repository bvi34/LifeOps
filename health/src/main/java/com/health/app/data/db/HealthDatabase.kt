package com.health.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.health.app.data.db.dao.HealthDao
import com.health.app.data.db.entities.AllergyEntity
import com.health.app.data.db.entities.CabinetItemEntity
import com.health.app.data.db.entities.CareNoteEntity
import com.health.app.data.db.entities.ConditionEntity
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
const val HEALTH_DB_VERSION = 8

/**
 * Health's own store: people, everything recorded about them, the medicine cabinet those records
 * draw on, the coverage that pays for it, the care team that provides it, and — since v7 — the
 * standing record of what is true about a person between illnesses.
 * Nothing here is shared with, or
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
        ImmunizationEntity::class
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

        /**
         * v3 gives the seam the two things it needs to let the *directory* decide who Health tracks.
         *
         * [ProfileEntity.household] is the directory's tick, copied here so Health's own packets
         * don't flip it back on; existing profiles are seeded **true**, because a profile that
         * already exists is somebody already being tracked and un-ticking them behind the user's
         * back would be a strange way to introduce a feature.
         *
         * `profile_tombstones` is what makes a removal stick — see [ProfileTombstoneEntity].
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE profiles ADD COLUMN household INTEGER NOT NULL DEFAULT 1")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS profile_tombstones (" +
                        "personKey TEXT NOT NULL PRIMARY KEY, " +
                        "name TEXT NOT NULL, " +
                        "removedAt INTEGER NOT NULL, " +
                        "syncVersion INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_profile_tombstones_syncVersion " +
                        "ON profile_tombstones(syncVersion)"
                )
            }
        }

        /**
         * v4 turns the Meds tab into a medicine cabinet.
         *
         * Two new tables, and neither is scoped to a profile — which is the point. `drug_facts`
         * caches one looked-up product's monograph for the whole household, so the same bottle on
         * two people's lists stores the label once and refreshes for both; `cabinet_items` is the
         * physical stock, because a bottle is a household possession and duplicating it per person
         * would mean four expiry dates to get wrong.
         *
         * `medications` gains the two links to them plus its reminder setting. Every existing row
         * keeps working untouched: the links default to null (a medicine typed in by hand is still a
         * medicine), and `reminderMode` defaults to `"off"`, so nobody's phone starts buzzing about
         * a medicine they set up last year because they upgraded.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS drug_facts (" +
                        "rxcui TEXT NOT NULL PRIMARY KEY, " +
                        "name TEXT NOT NULL, " +
                        "genericName TEXT, " +
                        "brandName TEXT, " +
                        "doseForm TEXT, " +
                        "routes TEXT, " +
                        "ingredients TEXT, " +
                        "availableStrengths TEXT, " +
                        "schedule TEXT, " +
                        "productType TEXT, " +
                        "manufacturer TEXT, " +
                        "labelSetId TEXT, " +
                        "labelEffectiveTime TEXT, " +
                        "sectionsJson TEXT, " +
                        "sources TEXT, " +
                        "fetchedAt INTEGER NOT NULL)"
                )

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS cabinet_items (" +
                        "id TEXT NOT NULL PRIMARY KEY, " +
                        "rxcui TEXT, " +
                        "name TEXT NOT NULL, " +
                        "brandName TEXT, " +
                        "strength TEXT, " +
                        "form TEXT, " +
                        "quantity REAL, " +
                        "quantityUnit TEXT NOT NULL, " +
                        "expiryDate TEXT, " +
                        "location TEXT, " +
                        "lowStockThreshold REAL, " +
                        "note TEXT, " +
                        "createdAt INTEGER NOT NULL, " +
                        "updatedAt INTEGER NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_cabinet_items_name ON cabinet_items(name)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_cabinet_items_rxcui ON cabinet_items(rxcui)")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_cabinet_items_expiryDate " +
                        "ON cabinet_items(expiryDate)"
                )

                db.execSQL("ALTER TABLE medications ADD COLUMN rxcui TEXT")
                db.execSQL("ALTER TABLE medications ADD COLUMN cabinetItemId TEXT")
                db.execSQL(
                    "ALTER TABLE medications ADD COLUMN reminderMode TEXT NOT NULL DEFAULT 'off'"
                )
                db.execSQL("ALTER TABLE medications ADD COLUMN reminderTimes TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_medications_rxcui ON medications(rxcui)")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_medications_cabinetItemId " +
                        "ON medications(cabinetItemId)"
                )
            }
        }

        /**
         * v5 lets the history be filled in afterwards, and stay honest about it.
         *
         * Doses, symptoms and care notes gain `createdAt` — when the *row* was written, as against
         * when the thing happened. They differ whenever somebody types up the 2am dose over
         * breakfast, and the difference is worth keeping: a record made at the time and a record
         * made from memory are both worth having and are not equally reliable.
         *
         * Nullable, with no backfill. Every row that predates the column was written by an app that
         * could only record the present, so its event time is almost certainly also its entry time —
         * but "almost certainly" is an assumption, and inventing one for thousands of existing rows
         * to make a badge tidy is exactly the kind of quiet fiction this app is supposed to refuse.
         * Null means "Health doesn't know when this was entered", and the history says so by saying
         * nothing. Readings have carried a non-null `createdAt` since v1.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE doses ADD COLUMN createdAt INTEGER")
                db.execSQL("ALTER TABLE symptoms ADD COLUMN createdAt INTEGER")
                db.execSQL("ALTER TABLE care_notes ADD COLUMN createdAt INTEGER")
            }
        }

        /**
         * v6 answers the two questions the medical half of the app never could: **who pays for
         * this**, and **who do we take her to**.
         *
         * Five tables, and the shape of them is the design. `insurance_plans` and `providers` are
         * household-scoped, exactly as `cabinet_items` is, because a family policy and a family
         * doctor are single objects several people share; `insurance_members` and `provider_links`
         * carry the per-person half — one member number each, one relationship each. Nothing is
         * scoped to a plan that shouldn't be: a doctor belongs to the household, not to the policy
         * that happens to cover them this year, which is what lets a carrier change without
         * re-entering every clinician in the house.
         *
         * `network_checks` is append-only and is never rewritten by a later check. That is what
         * makes "listed in March's directory, not in today's" a thing Health can say at all — with a
         * single overwritten flag, a doctor who left the network is indistinguishable from one who
         * was never in it, and those need different phone calls.
         *
         * Nothing existing is touched. A household that never opens the Care tab has five empty
         * tables and no other change at all.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS insurance_plans (" +
                        "id TEXT NOT NULL PRIMARY KEY, " +
                        "carrierName TEXT NOT NULL, " +
                        "planName TEXT, " +
                        "coverageKind TEXT NOT NULL, " +
                        "planType TEXT NOT NULL, " +
                        "groupNumber TEXT, " +
                        "payerId TEXT, " +
                        "rxBin TEXT, " +
                        "rxPcn TEXT, " +
                        "rxGroup TEXT, " +
                        "memberServicesPhone TEXT, " +
                        "nurseLinePhone TEXT, " +
                        "effectiveDate TEXT, " +
                        "endDate TEXT, " +
                        "directoryUrl TEXT, " +
                        "directoryBaseUrl TEXT, " +
                        "directoryStatus TEXT, " +
                        "directoryCheckedAt INTEGER, " +
                        "directoryDetail TEXT, " +
                        "frontImagePath TEXT, " +
                        "backImagePath TEXT, " +
                        "note TEXT, " +
                        "archived INTEGER NOT NULL DEFAULT 0, " +
                        "createdAt INTEGER NOT NULL, " +
                        "updatedAt INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_insurance_plans_carrierName " +
                        "ON insurance_plans(carrierName)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_insurance_plans_archived " +
                        "ON insurance_plans(archived)"
                )

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS insurance_members (" +
                        "id TEXT NOT NULL PRIMARY KEY, " +
                        "profileId TEXT NOT NULL, " +
                        "planId TEXT NOT NULL, " +
                        "memberId TEXT, " +
                        "personCode TEXT, " +
                        "subscriberName TEXT, " +
                        "relationshipToSubscriber TEXT, " +
                        "effectiveDate TEXT, " +
                        "endDate TEXT, " +
                        "primaryCoverage INTEGER NOT NULL DEFAULT 1, " +
                        "frontImagePath TEXT, " +
                        "backImagePath TEXT, " +
                        "note TEXT, " +
                        "createdAt INTEGER NOT NULL, " +
                        "updatedAt INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_insurance_members_profileId " +
                        "ON insurance_members(profileId)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_insurance_members_planId " +
                        "ON insurance_members(planId)"
                )

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS providers (" +
                        "id TEXT NOT NULL PRIMARY KEY, " +
                        "name TEXT NOT NULL, " +
                        "npi TEXT, " +
                        "specialty TEXT, " +
                        "practiceName TEXT, " +
                        "phone TEXT, " +
                        "addressLine TEXT, " +
                        "website TEXT, " +
                        "note TEXT, " +
                        "createdAt INTEGER NOT NULL, " +
                        "updatedAt INTEGER NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_providers_name ON providers(name)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_providers_npi ON providers(npi)")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS provider_links (" +
                        "id TEXT NOT NULL PRIMARY KEY, " +
                        "profileId TEXT NOT NULL, " +
                        "providerId TEXT NOT NULL, " +
                        "role TEXT NOT NULL, " +
                        "since TEXT, " +
                        "note TEXT, " +
                        "createdAt INTEGER NOT NULL, " +
                        "updatedAt INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_provider_links_profileId " +
                        "ON provider_links(profileId)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_provider_links_providerId " +
                        "ON provider_links(providerId)"
                )

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS network_checks (" +
                        "id TEXT NOT NULL PRIMARY KEY, " +
                        "providerId TEXT NOT NULL, " +
                        "planId TEXT, " +
                        "checkedAt INTEGER NOT NULL, " +
                        "outcome TEXT NOT NULL, " +
                        "directoryLabel TEXT, " +
                        "directoryUrl TEXT, " +
                        "matchedName TEXT, " +
                        "matchedNpi TEXT, " +
                        "matchCount INTEGER NOT NULL DEFAULT 0, " +
                        "networks TEXT, " +
                        "detail TEXT)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_network_checks_providerId " +
                        "ON network_checks(providerId)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_network_checks_planId ON network_checks(planId)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_network_checks_checkedAt " +
                        "ON network_checks(checkedAt)"
                )
            }
        }

        /**
         * v7 takes the two most safety-critical facts in the app out of a free-text note.
         *
         * A profile's `notes` column was documented as holding "allergies, conditions, the doctor's
         * number" — which meant the one thing a household most needs read back to it was stored in
         * the one shape nothing can read. A note cannot be listed, cannot be ordered by how badly it
         * went last time, and above all cannot be compared against the bottle somebody is holding at
         * 3am. `allergies` and `conditions` are that note made legible.
         *
         * **Nothing is parsed out of the existing note and nothing is deleted from it.** A migration
         * that tried to read "penicillin (hives), asthma, Dr Okafor 555-0101" into rows would be
         * guessing at exactly the data where a wrong guess is worst: a mis-parsed allergy is a
         * warning that never fires, or one that fires on the doctor's surname. The note stays
         * untouched and keeps saying what it always said; the household re-enters what it wants
         * checkable, and the record screen says so rather than pretending the tables are complete.
         *
         * `conditions` is also the home for the chronic things `episodes` could never hold — see
         * `logic/Conditions` for why an episode left open for nine years breaks the illness screen
         * rather than extending it.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS allergies (" +
                        "id TEXT NOT NULL PRIMARY KEY, " +
                        "profileId TEXT NOT NULL, " +
                        "substance TEXT NOT NULL, " +
                        "kind TEXT NOT NULL, " +
                        "severity TEXT NOT NULL, " +
                        "reaction TEXT, " +
                        "rxcui TEXT, " +
                        "noticedDate TEXT, " +
                        "note TEXT, " +
                        "createdAt INTEGER NOT NULL, " +
                        "updatedAt INTEGER NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_allergies_profileId ON allergies(profileId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_allergies_substance ON allergies(substance)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_allergies_rxcui ON allergies(rxcui)")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS conditions (" +
                        "id TEXT NOT NULL PRIMARY KEY, " +
                        "profileId TEXT NOT NULL, " +
                        "name TEXT NOT NULL, " +
                        "status TEXT NOT NULL, " +
                        "onsetDate TEXT, " +
                        "resolvedDate TEXT, " +
                        "providerId TEXT, " +
                        "monitorReadingType TEXT, " +
                        "note TEXT, " +
                        "createdAt INTEGER NOT NULL, " +
                        "updatedAt INTEGER NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_conditions_profileId ON conditions(profileId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_conditions_status ON conditions(status)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_conditions_providerId ON conditions(providerId)")
            }
        }

        /**
         * v8 adds the vaccination record — the one health document a household is repeatedly asked to
         * **produce** rather than consult, and the one it keeps as a folded card in a drawer.
         *
         * One table, and what is *not* in it is the design. There is no "due" column, no schedule and
         * no next-dose date, because an immunisation schedule varies by country, by birth year, by
         * risk group and by catch-up rules a clinician applies with judgement — see
         * `logic/Immunizations`. Health records what was given and declines to have an opinion about
         * what wasn't.
         *
         * `source` is the row's provenance and is stored beside the dose rather than inferred: a dose
         * somebody watched being given and a dose copied off a card years later are both worth having
         * and are not equally reliable. That is the same principle as v5's `createdAt`, applied to a
         * record that is very often transcribed from paper.
         *
         * Nothing existing is touched.
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS immunizations (" +
                        "id TEXT NOT NULL PRIMARY KEY, " +
                        "profileId TEXT NOT NULL, " +
                        "vaccine TEXT NOT NULL, " +
                        "cvxCode TEXT, " +
                        "givenDate TEXT, " +
                        "doseNumber INTEGER, " +
                        "source TEXT NOT NULL, " +
                        "providerId TEXT, " +
                        "lotNumber TEXT, " +
                        "site TEXT, " +
                        "note TEXT, " +
                        "createdAt INTEGER NOT NULL, " +
                        "updatedAt INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_immunizations_profileId ON immunizations(profileId)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_immunizations_vaccine ON immunizations(vaccine)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_immunizations_givenDate ON immunizations(givenDate)"
                )
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
                ).addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8
                )
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
