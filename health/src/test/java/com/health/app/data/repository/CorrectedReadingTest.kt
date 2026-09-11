package com.health.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.health.app.data.db.HealthDatabase
import com.health.app.data.model.ReadingType
import com.health.app.data.prefs.HealthPrefs
import com.health.app.logic.TempSite
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Correcting a reading, and the one thing a correction must not quietly do.
 *
 * A reading knows which illness it belongs to, and that link is not always derivable from its own
 * timestamp: starting an illness **adopts** the readings taken in the hours before it was declared,
 * because an illness is nearly always noticed after the first temperature was taken. So a reading
 * can legitimately sit inside an episode that began after it.
 *
 * Which makes "re-derive the episode on every edit" a tempting and wrong rule: it would evict
 * exactly those adopted readings the moment somebody fixed a typo in a note. The rule is that the
 * link is recomputed only when the **time** moves, which is the one edit that can really change
 * which illness a reading happened during.
 */
@RunWith(RobolectricTestRunner::class)
class CorrectedReadingTest {

    private lateinit var db: HealthDatabase
    private lateinit var repo: HealthRepository

    private val now = 1_700_000_000_000L
    private val hour = 60L * 60 * 1000
    private val day = 24L * hour

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

    private suspend fun person() = repo.addProfile("Ada", "Daughter", "2019-03-14", 0xFF00796BL)

    private suspend fun reading(id: String) =
        repo.observeReadings(db.healthDao().getReading(id)!!.profileId).first().first { it.id == id }

    @Test
    fun `a corrected value keeps the row, its id and its illness`() = runTest {
        val profileId = person()
        val id = repo.logTemperature(profileId, 384.0 / 10, TempSite.AXILLARY, takenAt = now - hour)
        val flu = repo.startEpisode(profileId, "Flu", startedAt = now)
        assertEquals("adopted into the illness declared after it", flu, db.healthDao().getReading(id)!!.episodeId)

        repo.updateReading(reading(id).copy(value = 37.9, note = "after calpol"))

        val corrected = db.healthDao().getReading(id)!!
        assertEquals(37.9, corrected.value, 0.0001)
        assertEquals("after calpol", corrected.note)
        assertEquals("still the armpit reading it always was", TempSite.AXILLARY.key, corrected.site)
        assertEquals("and still inside the illness that adopted it", flu, corrected.episodeId)
    }

    @Test
    fun `a corrected time re-files it against the illness it really happened in`() = runTest {
        val profileId = person()
        val flu = repo.startEpisode(profileId, "Flu", startedAt = now - 10 * day)
        repo.endEpisode(flu, endedAt = now - 7 * day)
        val cold = repo.startEpisode(profileId, "Cold", startedAt = now - day)

        // Recorded as today's, when it was really taken during the flu a week ago.
        val id = repo.logReading(profileId, ReadingType.WEIGHT, 17.4, takenAt = now)
        assertEquals(cold, db.healthDao().getReading(id)!!.episodeId)

        repo.updateReading(reading(id).copy(takenAt = now - 8 * day))

        assertEquals(flu, db.healthDao().getReading(id)!!.episodeId)
    }

    @Test
    fun `correcting a reading that is no longer there changes nothing`() = runTest {
        val profileId = person()
        val id = repo.logReading(profileId, ReadingType.HEART_RATE, 88.0)
        val model = reading(id)
        repo.deleteReading(id)

        repo.updateReading(model.copy(value = 92.0))

        assertEquals("a deleted row is not resurrected by an edit", null, db.healthDao().getReading(id))
    }
}
