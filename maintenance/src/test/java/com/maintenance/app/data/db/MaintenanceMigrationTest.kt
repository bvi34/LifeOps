package com.maintenance.app.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.maintenance.app.data.db.entities.RecallEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The upgrade path, run end to end on a database written by the *first* version of this app.
 *
 * This is the one test the backup story rests on. What Maintenance holds is the only copy of things
 * that are genuinely hard to reconstruct — a VIN off a door jamb, a parcel number off a tax bill,
 * eleven years of what the furnace cost — and the way that gets lost is not a dramatic bug. It is a
 * column added to an entity with no matching `ALTER TABLE`, which nobody notices until a phone that
 * has had the app since version 1 refuses to open it.
 *
 * The assertion that catches that is Room's own: opening a migrated database makes it compare the
 * schema it found against the schema its entities describe, and throw if they differ. So this test
 * builds a real v1 file, opens it through the production builder, and then reads the rows back —
 * because a migration that satisfies Room and drops the data would pass the first half alone.
 *
 * The v1 DDL below is reconstructed from the migrations rather than copied from `schemas/1.json`,
 * which does not exist: schema export was turned on later. That is safe precisely because both
 * migrations are additive — v1 is the current schema minus exactly what they add, and if that were
 * ever wrong, this test would fail rather than quietly agree with itself.
 */
@RunWith(RobolectricTestRunner::class)
class MaintenanceMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbName = "migration-test.db"

    @After
    fun tearDown() {
        context.getDatabasePath(dbName).also { file ->
            SQLiteDatabase.deleteDatabase(file)
        }
    }

    @Test
    fun `a database written at version 1 opens today, with its rows and the new defaults`() = runTest {
        writeVersionOne()

        val db = openThroughProduction()
        try {
            val dao = db.maintenanceDao()

            val asset = dao.getAsset("asset-1")
            assertEquals("The truck", asset?.name)
            assertEquals("vehicle", asset?.kind)
            // v3 added the stamp; a database that predates it has never asked NHTSA anything, and
            // "never" is a real answer the screen shows rather than a zero it hides.
            assertNull(asset?.recallsCheckedAt)

            assertEquals("1HGCM82633A004352", dao.attributesOf("asset-1").single().value)

            val plan = dao.getPlan("plan-1")
            assertEquals("Engine oil & filter", plan?.title)
            assertEquals(5_000L, plan?.everyMeter)
            // The two defaults the migrations promise: a plan made before the LifeOps seam existed
            // publishes (it opted in by default), and one made before packs existed is ordinary
            // upkeep typed by hand.
            assertTrue(plan!!.publishToLifeOps)
            assertEquals("upkeep", plan.kind)
            assertNull(plan.lifeOpsTaskId)
            assertNull(plan.sourcePack)
            assertNull(plan.atMeter)

            // And the table v3 introduced is really there, foreign key and all.
            dao.upsertRecalls(
                listOf(
                    RecallEntity(
                        assetId = "asset-1",
                        campaignNumber = "24V-123",
                        component = "SEAT BELTS",
                        summary = "The belt may not lock.",
                        consequence = null,
                        remedy = null,
                        manufacturer = null,
                        reportedOnEpochDay = null,
                        parkIt = false,
                        parkOutside = false,
                        fetchedAt = 1_700_000_000_000L,
                        acknowledgedAt = null
                    )
                )
            )
            assertEquals("24V-123", dao.getRecall("asset-1", "24V-123")?.campaignNumber)
        } finally {
            db.close()
        }
    }

    /** The production open path, migrations and all — not a rebuilt one that could drift from it. */
    private fun openThroughProduction(): MaintenanceDatabase =
        Room.databaseBuilder(context, MaintenanceDatabase::class.java, dbName)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build()

    /**
     * A real v1 file: the schema as it shipped, one asset with a VIN, and one plan on it.
     *
     * Written with raw SQLite rather than through Room, because Room can only ever create the
     * schema it currently describes — which is the thing being migrated *to*.
     */
    private fun writeVersionOne() {
        val file = context.getDatabasePath(dbName).apply { parentFile?.mkdirs() }
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            V1_SCHEMA.forEach(db::execSQL)
            db.execSQL(
                """
                INSERT INTO assets
                    (id, name, kind, make, model, year, purchasedAt, purchasePriceCents,
                     currentValueCents, notes, colorArgb, archived, sortOrder, createdAt, updatedAt)
                VALUES
                    ('asset-1', 'The truck', 'vehicle', 'Jeep', 'Wrangler', 2018, NULL, NULL,
                     NULL, NULL, 0, 0, 0, 1600000000000, 1600000000000)
                """.trimIndent()
            )
            db.execSQL(
                "INSERT INTO asset_attributes (assetId, key, value) " +
                    "VALUES ('asset-1', 'vin', '1HGCM82633A004352')"
            )
            db.execSQL(
                """
                INSERT INTO upkeep_plans
                    (id, assetId, title, notes, everyDays, everyMeter, lastDoneAt, lastDoneMeter,
                     active, sortOrder, createdAt, updatedAt)
                VALUES
                    ('plan-1', 'asset-1', 'Engine oil & filter', NULL, 365, 5000, NULL, NULL,
                     1, 0, 1600000000000, 1600000000000)
                """.trimIndent()
            )
            db.version = 1
        } finally {
            db.close()
        }
    }

    private companion object {

        /**
         * Version 1, in full: the current schema minus everything `MIGRATION_1_2` and
         * `MIGRATION_2_3` add — the seven `upkeep_plans` columns, the `assets` stamp, and the whole
         * `recalls` table. Every other table has been untouched since, which is what makes copying
         * them verbatim correct rather than lazy.
         */
        val V1_SCHEMA = listOf(
            """
            CREATE TABLE IF NOT EXISTS `assets` (
                `id` TEXT NOT NULL, `name` TEXT NOT NULL, `kind` TEXT NOT NULL, `make` TEXT,
                `model` TEXT, `year` INTEGER, `purchasedAt` INTEGER, `purchasePriceCents` INTEGER,
                `currentValueCents` INTEGER, `notes` TEXT, `colorArgb` INTEGER NOT NULL,
                `archived` INTEGER NOT NULL, `sortOrder` INTEGER NOT NULL,
                `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS `index_assets_kind` ON `assets` (`kind`)",
            "CREATE INDEX IF NOT EXISTS `index_assets_archived` ON `assets` (`archived`)",
            "CREATE INDEX IF NOT EXISTS `index_assets_sortOrder` ON `assets` (`sortOrder`)",
            """
            CREATE TABLE IF NOT EXISTS `asset_attributes` (
                `assetId` TEXT NOT NULL, `key` TEXT NOT NULL, `value` TEXT NOT NULL,
                PRIMARY KEY(`assetId`, `key`),
                FOREIGN KEY(`assetId`) REFERENCES `assets`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS `index_asset_attributes_assetId` ON `asset_attributes` (`assetId`)",
            """
            CREATE TABLE IF NOT EXISTS `upkeep_plans` (
                `id` TEXT NOT NULL, `assetId` TEXT NOT NULL, `title` TEXT NOT NULL, `notes` TEXT,
                `everyDays` INTEGER, `everyMeter` INTEGER, `lastDoneAt` INTEGER,
                `lastDoneMeter` INTEGER, `active` INTEGER NOT NULL, `sortOrder` INTEGER NOT NULL,
                `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`),
                FOREIGN KEY(`assetId`) REFERENCES `assets`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS `index_upkeep_plans_assetId` ON `upkeep_plans` (`assetId`)",
            "CREATE INDEX IF NOT EXISTS `index_upkeep_plans_active` ON `upkeep_plans` (`active`)",
            "CREATE INDEX IF NOT EXISTS `index_upkeep_plans_sortOrder` ON `upkeep_plans` (`sortOrder`)",
            """
            CREATE TABLE IF NOT EXISTS `service_records` (
                `id` TEXT NOT NULL, `assetId` TEXT NOT NULL, `planId` TEXT, `title` TEXT NOT NULL,
                `vendor` TEXT, `performedAt` INTEGER NOT NULL, `costCents` INTEGER NOT NULL,
                `meterValue` INTEGER, `notes` TEXT, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`),
                FOREIGN KEY(`assetId`) REFERENCES `assets`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS `index_service_records_assetId` ON `service_records` (`assetId`)",
            "CREATE INDEX IF NOT EXISTS `index_service_records_planId` ON `service_records` (`planId`)",
            "CREATE INDEX IF NOT EXISTS `index_service_records_performedAt` ON `service_records` (`performedAt`)",
            """
            CREATE TABLE IF NOT EXISTS `meter_readings` (
                `id` TEXT NOT NULL, `assetId` TEXT NOT NULL, `readAt` INTEGER NOT NULL,
                `value` INTEGER NOT NULL, `source` TEXT NOT NULL, PRIMARY KEY(`id`),
                FOREIGN KEY(`assetId`) REFERENCES `assets`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS `index_meter_readings_assetId` ON `meter_readings` (`assetId`)",
            "CREATE INDEX IF NOT EXISTS `index_meter_readings_readAt` ON `meter_readings` (`readAt`)",
            """
            CREATE TABLE IF NOT EXISTS `loans` (
                `id` TEXT NOT NULL, `assetId` TEXT NOT NULL, `label` TEXT NOT NULL, `lender` TEXT,
                `accountRef` TEXT, `principalCents` INTEGER NOT NULL, `annualRateBps` INTEGER NOT NULL,
                `termMonths` INTEGER NOT NULL, `paymentCents` INTEGER, `escrowCents` INTEGER NOT NULL,
                `startEpochDay` INTEGER, `notes` TEXT, `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`),
                FOREIGN KEY(`assetId`) REFERENCES `assets`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS `index_loans_assetId` ON `loans` (`assetId`)",
            """
            CREATE TABLE IF NOT EXISTS `coverages` (
                `id` TEXT NOT NULL, `assetId` TEXT NOT NULL, `kind` TEXT NOT NULL,
                `provider` TEXT NOT NULL, `policyNumber` TEXT, `premiumCents` INTEGER NOT NULL,
                `period` TEXT NOT NULL, `startsAt` INTEGER, `expiresAt` INTEGER, `notes` TEXT,
                `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`),
                FOREIGN KEY(`assetId`) REFERENCES `assets`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS `index_coverages_assetId` ON `coverages` (`assetId`)",
            "CREATE INDEX IF NOT EXISTS `index_coverages_expiresAt` ON `coverages` (`expiresAt`)"
        )
    }
}
