package com.health.app.logic

import com.operations.suitekit.SuiteElapsed
import com.operations.suitekit.SuiteVerdict
import java.time.Instant
import java.time.ZoneId

/**
 * What Health thinks of the moment you say something happened.
 *
 * The suite's when-picker offers every instant and asks the app; this is Health's answer, and it is
 * deliberately not the same answer LifeOps would give about the same tap.
 *
 * **Ahead is refused.** A temperature that has not been taken yet is a typo, and it is not an
 * inert one: `DoseSchedule` reasons over these instants, and a dose dated into tomorrow moves the
 * window that says whether the next one is safe. That is a bad enough outcome to be worth blocking
 * at the point of entry rather than validating afterwards.
 *
 * **Behind is allowed, and said out loud.** Reconstructing last month's flu a week later is the case
 * this whole feature exists for, so a back-dated record is not an error — but it is worth naming,
 * because "3 days ago" recorded now and "3 days ago" recorded then are different claims about how
 * well somebody remembers, and the person filing it should see which one they are making.
 */
object HealthWhen {

    /**
     * A minute of slack for the future.
     *
     * "Now" is a fixed instant from the moment a dialog opened, and a person who spends ninety
     * seconds typing a note would otherwise watch their own default turn into a refusal underneath
     * them. A minute is far too small to matter to a dose window and exactly big enough to cover
     * the drift.
     */
    private const val FUTURE_GRACE_MILLIS = 60_000L

    fun check(millis: Long, nowMillis: Long = System.currentTimeMillis()): SuiteVerdict {
        if (millis > nowMillis + FUTURE_GRACE_MILLIS) {
            return SuiteVerdict.Refused(
                "That's still to come. Health records what has happened, and a reading dated ahead " +
                    "would be taken seriously by the dose windows."
            )
        }
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val then = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
        if (then.isBefore(today)) {
            return SuiteVerdict.Note(
                "Filed late — this happened ${SuiteElapsed.formatAgo(nowMillis - millis)}."
            )
        }
        return SuiteVerdict.Fine
    }
}
