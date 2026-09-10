package com.health.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.health.app.data.db.HealthDatabase
import com.health.app.data.model.ReadingType
import com.health.app.data.prefs.HealthPrefs
import com.health.app.logic.ConditionStatus
import com.health.app.logic.DocumentKind
import com.health.app.logic.TempSite
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What a delete offered back actually puts back.
 *
 * The claim [RestorableDelete] makes is a strong one — that undoing leaves the database
 * indistinguishable from before — and it is not a claim a screen can check. Two things could break
 * it without anybody noticing until a household needed it: a restore that writes the row under a
 * fresh id, which silently orphans everything that pointed at the old one, and a restore that puts
 * the row back but not the side effect the delete had, which is how a bottle ends up with the wrong
 * number of tablets in it.
 *
 * So these tests are about the *database after the undo*, not about the undo being offered.
 */
@RunWith(RobolectricTestRunner::class)
class RestorableDeleteTest {

    private lateinit var db: HealthDatabase
    private lateinit var repo: HealthRepository

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, HealthDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = HealthRepository(db.healthDao(), HealthPrefs(context))
    }

    @After
    fun tearDown() = db.close()

    private suspend fun person() = repo.addProfile(
        name = "Ada",
        relationship = "Daughter",
        birthDate = "2019-03-14",
        colorArgb = 0xFF00796BL
    )

    @Test
    fun `an undone reading comes back as the same row, not a copy of it`() = runTest {
        val profileId = person()
        val id = repo.logTemperature(profileId, 38.4, TempSite.ORAL, takenAt = 1_700_000_000_000L)

        val restore = repo.deleteReading(id)
        assertNull(db.healthDao().getReading(id))

        restore!!.undo()

        val back = db.healthDao().getReading(id)
        assertNotNull("the reading is back under its own id", back)
        assertEquals(38.4, back!!.value, 0.0001)
        assertEquals(1_700_000_000_000L, back.takenAt)
        assertEquals(TempSite.ORAL.key, back.site)
        assertEquals(ReadingType.TEMPERATURE.key, back.type)
    }

    @Test
    fun `an undone dose takes its stock back out of the bottle`() = runTest {
        val profileId = person()
        val itemId = repo.addCabinetItem(name = "Calpol", quantity = 100.0, quantityUnit = "mL")
        val medicationId = repo.addMedication(
            profileId = profileId,
            name = "Calpol",
            strength = null,
            form = null,
            doseAmount = 5.0,
            doseUnit = "mL",
            minIntervalHours = 4.0,
            maxDosesPer24h = 4,
            maxAmountPer24h = null,
            cabinetItemId = itemId
        )

        val doseId = repo.logDose(profileId, medicationId, "Calpol", amount = 5.0, unit = "mL")
        assertEquals("the dose came out of the bottle", 95.0, db.healthDao().getCabinetItem(itemId)!!.quantity!!, 0.0001)

        val restore = repo.deleteDose(doseId)
        assertEquals("deleting it put the stock back", 100.0, db.healthDao().getCabinetItem(itemId)!!.quantity!!, 0.0001)

        restore!!.undo()

        assertNotNull("the dose is back", db.healthDao().getDose(doseId))
        assertEquals(
            "and so is the hole it left in the bottle",
            95.0,
            db.healthDao().getCabinetItem(itemId)!!.quantity!!,
            0.0001
        )
    }

    @Test
    fun `an undone condition is found again by the document filed against it`() = runTest {
        val profileId = person()
        val conditionId = repo.addCondition(profileId, name = "Asthma", status = ConditionStatus.ACTIVE)
        val documentId = repo.addDocument(
            profileId = profileId,
            title = "Spirometry, March",
            kind = DocumentKind.LAB,
            fileName = "spirometry.pdf",
            conditionId = conditionId
        )

        val restore = repo.deleteCondition(conditionId)
        // The document is deliberately kept when its condition goes — a result is a fact about the
        // person, not about the row it was filed under — so it is still pointing at the old id.
        assertEquals(conditionId, db.healthDao().getDocument(documentId)!!.conditionId)

        restore!!.undo()

        assertNotNull("the condition is back under the id the document names", db.healthDao().getCondition(conditionId))
    }

    @Test
    fun `deleting something already gone offers nothing back`() = runTest {
        // Two people on two phones, or two taps on one: the second delete must not offer an undo for
        // a row it did not delete, because taking that offer up would put back nothing at all.
        val profileId = person()
        val id = repo.logTemperature(profileId, 37.2, TempSite.ORAL)

        assertNotNull(repo.deleteReading(id))
        assertNull(repo.deleteReading(id))
    }
}
