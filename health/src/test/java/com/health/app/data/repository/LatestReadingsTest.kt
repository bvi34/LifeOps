package com.health.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.health.app.data.db.HealthDatabase
import com.health.app.data.model.ReadingType
import com.health.app.data.prefs.HealthPrefs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * "When was that last taken?" — the question a condition that names a measurement is asked.
 *
 * Two ways to get this wrong, both of which read as plausible on screen: handing back the *first*
 * row of a kind rather than the most recent one, which quietly shows a household last winter's
 * weight next to a thyroid condition, and letting one person's readings answer for another's, which
 * is the worst thing this app can do with a number.
 */
@RunWith(RobolectricTestRunner::class)
class LatestReadingsTest {

    private lateinit var db: HealthDatabase
    private lateinit var repo: HealthRepository

    private val now = 1_700_000_000_000L
    private val day = 24L * 60 * 60 * 1000

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

    @Test
    fun `the most recent of each kind, not the first one recorded`() = runTest {
        val profileId = repo.addProfile("Ada", "Daughter", "2019-03-14", 0xFF00796BL)
        repo.logReading(profileId, ReadingType.WEIGHT, 16.2, takenAt = now - 30 * day)
        repo.logReading(profileId, ReadingType.WEIGHT, 17.4, takenAt = now - 3 * day)
        repo.logReading(profileId, ReadingType.OXYGEN, 97.0, takenAt = now - day)

        val latest = repo.observeLatestReadings(profileId).first()

        assertEquals(17.4, latest[ReadingType.WEIGHT]!!.value, 0.0001)
        assertEquals(now - 3 * day, latest[ReadingType.WEIGHT]!!.takenAt)
        assertEquals(97.0, latest[ReadingType.OXYGEN]!!.value, 0.0001)
        assertNull("nothing is invented for a kind nobody has recorded", latest[ReadingType.HEART_RATE])
    }

    @Test
    fun `one person's readings never answer for another's`() = runTest {
        val ada = repo.addProfile("Ada", "Daughter", "2019-03-14", 0xFF00796BL)
        val sam = repo.addProfile("Sam", "Son", "2016-01-09", 0xFF5D4037L)
        repo.logReading(ada, ReadingType.WEIGHT, 17.4, takenAt = now - day)
        repo.logReading(sam, ReadingType.WEIGHT, 24.8, takenAt = now)

        assertEquals(17.4, repo.observeLatestReadings(ada).first()[ReadingType.WEIGHT]!!.value, 0.0001)
        assertEquals(24.8, repo.observeLatestReadings(sam).first()[ReadingType.WEIGHT]!!.value, 0.0001)
    }
}
