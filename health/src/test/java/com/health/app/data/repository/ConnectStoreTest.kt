package com.health.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.health.app.data.db.HealthDatabase
import com.health.app.data.model.ImportedRecord
import com.health.app.data.model.ReadingType
import com.health.app.data.prefs.HealthPrefs
import com.health.app.logic.ConnectKind
import com.health.app.logic.TempSite
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Filing what Health Connect held: every record kept once however often it arrives, the four vitals
 * mirrored into readings, deletions honoured, and nothing ever landing under the wrong person.
 */
@RunWith(RobolectricTestRunner::class)
class ConnectStoreTest {

    private lateinit var db: HealthDatabase
    private lateinit var repo: HealthRepository

    private val now = 1_700_000_000_000L
    private val hour = 60 * 60 * 1000L

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

    private fun record(
        id: String,
        kind: ConnectKind,
        value: Double?,
        at: Long = now,
        secondary: Double? = null,
        detail: Map<String, Any?> = emptyMap()
    ) = ImportedRecord(
        id = id, kind = kind, startAt = at, endAt = null, zoneOffsetSeconds = 0,
        value = value, secondaryValue = secondary, detail = detail, source = "com.example.watch", modifiedAt = at
    )

    @Test
    fun `a record delivered twice is kept once`() = runTest {
        val ada = repo.profiles.addProfile("Ada", "Me", "1990-01-01", 0xFF00796BL)
        val steps = record("s1", ConnectKind.STEPS, 1200.0)
        repo.connect.apply(ada, listOf(steps), emptyList())
        repo.connect.apply(ada, listOf(steps.copy(value = 1300.0)), emptyList())

        val totals = repo.connect.observeKindTotals(ada).first()
        assertEquals(1, totals.single().count)
        assertEquals(1300.0, repo.connect.observeRecords(ada, ConnectKind.STEPS).first().single().value!!, 0.0)
    }

    @Test
    fun `the four vitals are mirrored into readings, with the site the fever rules need`() = runTest {
        val ada = repo.profiles.addProfile("Ada", "Me", "1990-01-01", 0xFF00796BL)
        repo.connect.apply(
            ada,
            listOf(
                record("w", ConnectKind.WEIGHT, 70.2),
                record("bp", ConnectKind.BLOOD_PRESSURE, 118.0, secondary = 76.0),
                record("t", ConnectKind.BODY_TEMPERATURE, 38.3, detail = mapOf("site" to TempSite.EAR.key)),
                record("rhr", ConnectKind.RESTING_HEART_RATE, 58.0),
                record("spo2", ConnectKind.OXYGEN_SATURATION, 97.0),
                record("steps", ConnectKind.STEPS, 5000.0)
            ),
            emptyList()
        )

        val readings = repo.readings.observeReadings(ada).first()
        assertEquals(
            setOf(ReadingType.WEIGHT, ReadingType.BLOOD_PRESSURE, ReadingType.TEMPERATURE, ReadingType.HEART_RATE),
            readings.map { it.type }.toSet()
        )
        assertTrue(readings.all { ConnectStore.isMirrored(it.id) })
        assertEquals(TempSite.EAR, readings.first { it.type == ReadingType.TEMPERATURE }.site)
        assertEquals(76.0, readings.first { it.type == ReadingType.BLOOD_PRESSURE }.secondaryValue!!, 0.0)
    }

    @Test
    fun `a number Health wouldn't accept typed in is kept but not mirrored`() = runTest {
        val ada = repo.profiles.addProfile("Ada", "Me", "1990-01-01", 0xFF00796BL)
        repo.connect.apply(ada, listOf(record("t", ConnectKind.BODY_TEMPERATURE, 3.8)), emptyList())

        assertEquals(1, repo.connect.count(ada))
        assertTrue(repo.readings.observeReadings(ada).first().isEmpty())
    }

    @Test
    fun `a deletion in Health Connect removes the record and its mirrored reading`() = runTest {
        val ada = repo.profiles.addProfile("Ada", "Me", "1990-01-01", 0xFF00796BL)
        repo.connect.apply(ada, listOf(record("w", ConnectKind.WEIGHT, 70.2)), emptyList())
        repo.connect.apply(ada, emptyList(), listOf("w"))

        assertEquals(0, repo.connect.count(ada))
        assertTrue(repo.readings.observeReadings(ada).first().isEmpty())
    }

    @Test
    fun `replacing medical records drops what the source no longer has`() = runTest {
        val ada = repo.profiles.addProfile("Ada", "Me", "1990-01-01", 0xFF00796BL)
        repo.connect.apply(
            ada,
            listOf(record("m1", ConnectKind.MEDICAL_VACCINES, null), record("m2", ConnectKind.MEDICAL_VACCINES, null)),
            emptyList()
        )
        repo.connect.apply(ada, listOf(record("c1", ConnectKind.MEDICAL_CONDITIONS, null)), emptyList())

        val removed = repo.connect.replace(ada, listOf(ConnectKind.MEDICAL_VACCINES), listOf(record("m2", ConnectKind.MEDICAL_VACCINES, null)))

        assertEquals(1, removed)
        assertEquals(listOf("m2"), repo.connect.observeRecords(ada, ConnectKind.MEDICAL_VACCINES).first().map { it.id })
        assertEquals("another kind is left alone", 1, repo.connect.observeRecords(ada, ConnectKind.MEDICAL_CONDITIONS).first().size)
    }

    @Test
    fun `moving imports to another person takes the mirrored readings and nothing typed by hand`() = runTest {
        val ada = repo.profiles.addProfile("Ada", "Daughter", "2019-03-14", 0xFF00796BL)
        val sam = repo.profiles.addProfile("Sam", "Me", "1988-06-01", 0xFF5D4037L)
        repo.readings.logReading(ada, ReadingType.WEIGHT, 17.4, takenAt = now - hour)
        repo.connect.apply(ada, listOf(record("w", ConnectKind.WEIGHT, 81.0)), emptyList())

        repo.connect.reassign(ada, sam)

        assertEquals(0, repo.connect.count(ada))
        assertEquals(1, repo.connect.count(sam))
        assertEquals(listOf(17.4), repo.readings.observeReadings(ada).first().map { it.value })
        assertEquals(listOf(81.0), repo.readings.observeReadings(sam).first().map { it.value })
    }

    @Test
    fun `deleting everything imported leaves typed readings alone`() = runTest {
        val ada = repo.profiles.addProfile("Ada", "Me", "1990-01-01", 0xFF00796BL)
        repo.readings.logReading(ada, ReadingType.WEIGHT, 70.0, takenAt = now - hour)
        repo.connect.apply(ada, listOf(record("w", ConnectKind.WEIGHT, 70.2)), emptyList())

        repo.connect.deleteAll()

        assertEquals(0, repo.connect.count(ada))
        assertEquals(listOf(70.0), repo.readings.observeReadings(ada).first().map { it.value })
    }

    @Test
    fun `removing a person removes what was imported for them`() = runTest {
        val ada = repo.profiles.addProfile("Ada", "Me", "1990-01-01", 0xFF00796BL)
        repo.connect.apply(ada, listOf(record("s", ConnectKind.STEPS, 100.0)), emptyList())

        db.healthDao().deleteProfileCascade(ada)

        assertEquals(0, repo.connect.count(ada))
        assertNull(db.healthDao().getConnectRecord("s"))
    }
}
