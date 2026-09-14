package com.health.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.health.app.data.db.HealthDatabase
import com.health.app.data.model.ReadingType
import com.health.app.data.prefs.HealthPrefs
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Which illness a measurement filled in afterwards belongs to.
 *
 * This is what the missing **when** on the measurement dialog actually cost. A weight, an oxygen
 * saturation or a blood pressure could only ever be recorded as *now*, so one typed up on Sunday
 * for Friday was filed against whatever was going on at the moment of typing — today's illness, or
 * no illness at all. The number was right and the story it belonged to was wrong, which is the
 * failure nobody notices, because nothing about the row looks incorrect.
 *
 * The rule being tested is the repository's, not the dialog's: a reading is filed against the
 * episode open **at the instant it was taken**. It was always written that way; until now the one
 * dialog that could reach it never passed anything but the current time.
 */
@RunWith(RobolectricTestRunner::class)
class BackfilledReadingTest {

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

    private suspend fun person() = repo.profiles.addProfile("Ada", "Daughter", "2019-03-14", 0xFF00796BL)

    @Test
    fun `a weight taken during a past illness is filed against that illness`() = runTest {
        val profileId = person()
        val flu = repo.episodes.startEpisode(profileId, "Flu", startedAt = now - 10 * day)
        repo.episodes.endEpisode(flu, endedAt = now - 7 * day)

        val id = repo.readings.logReading(
            profileId = profileId,
            type = ReadingType.WEIGHT,
            value = 17.4,
            takenAt = now - 8 * day
        )

        assertEquals(flu, db.healthDao().getReading(id)!!.episodeId)
    }

    @Test
    fun `and not against the illness that happens to be open now`() = runTest {
        val profileId = person()
        val flu = repo.episodes.startEpisode(profileId, "Flu", startedAt = now - 10 * day)
        repo.episodes.endEpisode(flu, endedAt = now - 7 * day)
        val cold = repo.episodes.startEpisode(profileId, "Cold", startedAt = now - day)

        // Typed up today, taken during the flu: the story it belongs to is the flu's.
        val backfilled = repo.readings.logReading(
            profileId = profileId,
            type = ReadingType.OXYGEN,
            value = 97.0,
            takenAt = now - 8 * day
        )
        assertEquals(flu, db.healthDao().getReading(backfilled)!!.episodeId)

        val today = repo.readings.logReading(profileId, ReadingType.OXYGEN, 96.0, takenAt = now)
        assertEquals(cold, db.healthDao().getReading(today)!!.episodeId)
    }

    @Test
    fun `a reading from a week nobody called an illness belongs to none`() = runTest {
        // Not everything worth recording happened during a declared episode, and a reading with no
        // illness around it is a real reading rather than an orphan to be adopted by the nearest one.
        val profileId = person()
        val flu = repo.episodes.startEpisode(profileId, "Flu", startedAt = now - 10 * day)
        repo.episodes.endEpisode(flu, endedAt = now - 7 * day)

        val id = repo.readings.logReading(profileId, ReadingType.HEART_RATE, 88.0, takenAt = now - 3 * day)

        assertNull(db.healthDao().getReading(id)!!.episodeId)
    }
}
