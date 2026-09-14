package com.maintenance.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.maintenance.app.data.db.MaintenanceDatabase
import com.maintenance.app.data.model.Asset
import com.maintenance.app.logic.AssetKind
import com.maintenance.app.logic.PlanKind
import com.maintenance.app.logic.Recall
import com.maintenance.app.logic.SchedulePacks
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The store's own rules, against a real database.
 *
 * Everything above this layer is pure and tested by reasoning about it. These are the rules that
 * only SQLite can be asked about: that a cleared field is *deleted* rather than stored as an empty
 * string, that deleting a truck really takes its plans and recalls with it, and that the two writes
 * which reach back into the LifeOps week hand back the right task ids. A fake DAO would answer all
 * of those with whatever this test assumed, which is why there isn't one.
 */
@RunWith(RobolectricTestRunner::class)
class MaintenanceRepositoryTest {

    private lateinit var db: MaintenanceDatabase
    private lateinit var repo: MaintenanceRepository

    private val now = 1_700_000_000_000L

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        // In memory, but a real Room database: the same entities, the same generated SQL, the same
        // foreign keys. That is the point — none of what is asserted below is Kotlin's behaviour.
        db = Room.inMemoryDatabaseBuilder(context, MaintenanceDatabase::class.java).build()
        repo = MaintenanceRepository(db.maintenanceDao())
    }

    @After
    fun tearDown() = db.close()

    // ------------------------------------------------------------------ the asset and its fields

    @Test
    fun `adding an asset stores what was filled in, tidied, and nothing that was not`() = runTest {
        val id = repo.assets.addAsset(
            name = "  The truck  ",
            kind = AssetKind.VEHICLE,
            make = "Jeep",
            model = " ",
            year = 2018,
            attributes = mapOf(
                "vin" to " 1hgcm826-33a004352 ",
                "licensePlate" to "8ABC123",
                "trim" to "   ",
                // A key this kind never asked for is not this asset's business.
                "parcelNumber" to "042-118-03"
            )
        )

        val asset = db.maintenanceDao().getAsset(id)!!
        assertEquals("The truck", asset.name)
        assertEquals("Jeep", asset.make)
        assertNull("a blank model is absent, not empty", asset.model)

        val stored = db.maintenanceDao().attributesOf(id).associate { it.key to it.value }
        // Normalised the way the field itself would: a VIN is filed as it reads on the title.
        assertEquals("1HGCM82633A004352", stored["vin"])
        assertEquals("8ABC123", stored["licensePlate"])
        assertFalse("a blank trim was written as a row", stored.containsKey("trim"))
        assertFalse("a home's field was stored on a vehicle", stored.containsKey("parcelNumber"))
    }

    @Test
    fun `clearing a field deletes it, so absent and blank cannot both mean no VIN`() = runTest {
        val id = repo.assets.addAsset(
            name = "The truck",
            kind = AssetKind.VEHICLE,
            attributes = mapOf("vin" to "1HGCM82633A004352", "color" to "Blue")
        )

        repo.assets.updateAsset(asset(id).copy(name = "The truck"), mapOf("vin" to "", "color" to "Blue"))

        val stored = db.maintenanceDao().attributesOf(id).associate { it.key to it.value }
        assertFalse("a cleared VIN was kept as an empty string", stored.containsKey("vin"))
        assertEquals("Blue", stored["color"])
    }

    @Test
    fun `a decode fills what is blank and argues with nothing you typed`() = runTest {
        val id = repo.assets.addAsset(
            name = "The truck",
            kind = AssetKind.VEHICLE,
            make = "Jeep",
            attributes = mapOf("trim" to "Rubicon")
        )

        repo.week.applyVehicleFacts(
            id,
            com.maintenance.app.logic.VehicleFacts(
                make = "Chrysler",
                model = "Wrangler",
                year = 2018,
                trim = "Unlimited Sport",
                bodyClass = "Sport Utility Vehicle (SUV)",
                driveType = "4WD/4-Wheel Drive",
                engineCylinders = 6,
                displacementLitres = 3.6,
                fuel = "Gasoline",
                transmission = "Automatic"
            )
        )

        val asset = db.maintenanceDao().getAsset(id)!!
        assertEquals("what was typed wins over what was decoded", "Jeep", asset.make)
        assertEquals("Wrangler", asset.model)
        assertEquals(2018, asset.year)

        val stored = db.maintenanceDao().attributesOf(id).associate { it.key to it.value }
        assertEquals("Rubicon", stored["trim"])
        assertEquals("Sport Utility Vehicle (SUV)", stored["bodyStyle"])
        assertEquals("3.6L V6", stored["engine"])
        assertEquals("Gasoline", stored["fuel"])
        assertEquals("Automatic", stored["transmission"])
        assertEquals("4WD", stored["driveType"])
    }

    // ------------------------------------------------------------------ the seam, running backwards

    @Test
    fun `a reading satisfies the odometer prompt and hands back its task`() = runTest {
        val id = repo.assets.addAsset(name = "The truck", kind = AssetKind.VEHICLE)
        val planId = repo.upkeep.addPlan(id, "Odometer reading", everyDays = 7, everyMeter = null, kind = PlanKind.METER_READING)
        db.maintenanceDao().setPlanLink(planId, "task-odo", 19_000L)

        val toTick = repo.meter.addReading(id, value = 42_100, readAt = now)

        assertEquals(listOf("task-odo"), toTick)
        val plan = db.maintenanceDao().getPlan(planId)!!
        assertEquals(now, plan.lastDoneAt)
        assertEquals(42_100L, plan.lastDoneMeter)
    }

    @Test
    fun `running the recall check satisfies its prompt and hands back its task`() = runTest {
        val id = repo.assets.addAsset(name = "The truck", kind = AssetKind.VEHICLE)
        val planId = repo.upkeep.addPlan(
            id, "Check recalls", everyDays = 180, everyMeter = null, kind = PlanKind.RECALL_CHECK
        )
        db.maintenanceDao().setPlanLink(planId, "task-recall", 19_000L)

        val toTick = repo.recalls.saveRecalls(id, listOf(recall("24V-123")), fetchedAt = now)

        assertEquals(listOf("task-recall"), toTick)
        assertEquals(now, db.maintenanceDao().getPlan(planId)!!.lastDoneAt)
        assertEquals(now, db.maintenanceDao().getAsset(id)!!.recallsCheckedAt)
    }

    @Test
    fun `no open recalls still counts as having checked`() = runTest {
        // The result you most want to be able to trust. A prompt that only moved on when something
        // was wrong would ask again next week for having had nothing wrong.
        val id = repo.assets.addAsset(name = "The truck", kind = AssetKind.VEHICLE)
        val planId = repo.upkeep.addPlan(
            id, "Check recalls", everyDays = 180, everyMeter = null, kind = PlanKind.RECALL_CHECK
        )
        db.maintenanceDao().setPlanLink(planId, "task-recall", 19_000L)

        val toTick = repo.recalls.saveRecalls(id, emptyList(), fetchedAt = now)

        assertEquals(listOf("task-recall"), toTick)
        assertEquals(now, db.maintenanceDao().getPlan(planId)!!.lastDoneAt)
        assertEquals(now, db.maintenanceDao().getAsset(id)!!.recallsCheckedAt)
    }

    @Test
    fun `a recall you have dealt with stays dealt with when the list is fetched again`() = runTest {
        val id = repo.assets.addAsset(name = "The truck", kind = AssetKind.VEHICLE)
        repo.recalls.saveRecalls(id, listOf(recall("24V-123")), fetchedAt = now)
        repo.recalls.setRecallAcknowledged(id, "24V-123", acknowledged = true)

        // NHTSA re-sends every open campaign every time; the campaign is the identity.
        repo.recalls.saveRecalls(id, listOf(recall("24V-123"), recall("25V-001")), fetchedAt = now + 1)

        assertNotNull(db.maintenanceDao().getRecall(id, "24V-123")!!.acknowledgedAt)
        assertNull(db.maintenanceDao().getRecall(id, "25V-001")!!.acknowledgedAt)
    }

    @Test
    fun `ticking a prompt off in the week moves it on without writing a service record`() = runTest {
        val id = repo.assets.addAsset(name = "The truck", kind = AssetKind.VEHICLE)
        val prompt = repo.upkeep.addPlan(
            id, "Check recalls", everyDays = 180, everyMeter = null, kind = PlanKind.RECALL_CHECK
        )
        val work = repo.upkeep.addPlan(id, "Engine oil & filter", everyDays = 365, everyMeter = null)

        assertTrue(repo.completeFromWeek(prompt, now))
        assertTrue(repo.completeFromWeek(work, now))

        val records = db.maintenanceDao().observeRecordsFor(id).first()
        assertEquals(listOf("Engine oil & filter"), records.map { it.title })
        assertEquals(now, db.maintenanceDao().getPlan(prompt)!!.lastDoneAt)
    }

    // ------------------------------------------------------------------ the cascade

    @Test
    fun `deleting an asset hands back its published tasks and takes everything else with it`() = runTest {
        val id = repo.assets.addAsset(name = "The truck", kind = AssetKind.VEHICLE, attributes = mapOf("color" to "Blue"))
        val planId = repo.upkeep.addPlan(id, "Engine oil & filter", everyDays = 365, everyMeter = null)
        db.maintenanceDao().setPlanLink(planId, "task-oil", 19_000L)
        repo.recalls.saveRecalls(id, listOf(recall("24V-123")), fetchedAt = now)
        repo.meter.addReading(id, value = 42_100, readAt = now)

        val stranded = repo.assets.deleteAsset(id)

        // The cascade cannot reach into LifeOps, so the caller is handed what to take off the week.
        assertEquals(listOf("task-oil"), stranded)
        assertNull(db.maintenanceDao().getAsset(id))
        assertNull(db.maintenanceDao().getPlan(planId))
        assertNull(db.maintenanceDao().getRecall(id, "24V-123"))
        assertTrue(db.maintenanceDao().attributesOf(id).isEmpty())
        assertTrue(db.maintenanceDao().readingsOf(id).isEmpty())
    }

    @Test
    fun `applying a pack twice adds nothing the second time`() = runTest {
        val id = repo.assets.addAsset(name = "The truck", kind = AssetKind.VEHICLE)

        val first = repo.week.applyPack(id, SchedulePacks.GENERIC_VEHICLE)
        val after = db.maintenanceDao().plansOf(id)
        val second = repo.week.applyPack(id, SchedulePacks.GENERIC_VEHICLE)

        assertEquals(SchedulePacks.GENERIC_VEHICLE.items.size, first.toCreate.size)
        assertEquals(SchedulePacks.GENERIC_VEHICLE.items.size, after.size)
        assertTrue(second.isNoOp)
        assertEquals(after.size, db.maintenanceDao().plansOf(id).size)
        // And the pack brings the standing recall check with it.
        assertTrue(after.any { it.kind == PlanKind.RECALL_CHECK.key })
    }

    // ------------------------------------------------------------------ helpers

    private suspend fun asset(id: String): Asset = db.maintenanceDao().getAsset(id)!!.let { row ->
        Asset(
            id = row.id,
            name = row.name,
            kind = AssetKind.of(row.kind),
            make = row.make,
            model = row.model,
            year = row.year,
            purchasedAt = row.purchasedAt,
            purchasePriceCents = row.purchasePriceCents,
            currentValueCents = row.currentValueCents,
            notes = row.notes,
            colorArgb = row.colorArgb,
            archived = row.archived,
            sortOrder = row.sortOrder,
            createdAt = row.createdAt,
            updatedAt = row.updatedAt
        )
    }

    private fun recall(campaign: String) = Recall(
        campaignNumber = campaign,
        component = "SEAT BELTS",
        summary = "The belt may not lock."
    )
}
