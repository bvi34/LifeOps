package com.operations.suitekit

import com.operations.backupkit.AppId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The home screen's arrangement, settled here rather than in a Compose file.
 *
 * Every case below is one a household can actually reach, and most of them are reached by *not*
 * doing anything: an order written months ago meets a build with a new app in it, or an app that has
 * been removed, or the same key twice because something wrote it twice. A grid is the wrong place to
 * find out what any of those do.
 */
class SuiteHomeLayoutTest {

    private val shipped = SuiteApps.all.map { it.key }

    @Test
    fun `no stored order is the order the suite ships in`() {
        assertEquals(shipped, SuiteHomeLayout.order(emptyList()).map { it.key })
        assertEquals(shipped, SuiteHomeLayout.visible(emptyList(), emptySet()).map { it.key })
    }

    @Test
    fun `a stored order is honoured, and what it does not mention follows it`() {
        val stored = listOf(AppId.LOGISTICS.key, AppId.HEALTH.key)
        val arranged = SuiteHomeLayout.order(stored).map { it.key }

        assertEquals(stored, arranged.take(2))
        // Everything else, still in the order the suite declares it.
        assertEquals(shipped.filterNot { it in stored }, arranged.drop(2))
        assertEquals(shipped.size, arranged.size)
    }

    @Test
    fun `an app added in an update appears rather than vanishing`() {
        // The case that matters most: the household arranged their screen when the suite had three
        // apps, and the order they saved says nothing about the eight that came later. An order
        // treated as authoritative would hide every one of them, and nothing would say so.
        val storedBeforeTheRest = listOf(AppId.LIFEOPS.key, AppId.CITATION.key, AppId.HEALTH.key)
        val arranged = SuiteHomeLayout.order(storedBeforeTheRest).map { it.key }

        assertEquals(shipped.toSet(), arranged.toSet())
        assertEquals(shipped.size, arranged.size)
    }

    @Test
    fun `an app this build has never heard of is dropped`() {
        val arranged = SuiteHomeLayout.order(listOf("telegraphy", AppId.HEALTH.key)).map { it.key }

        assertFalse("telegraphy" in arranged)
        assertEquals(AppId.HEALTH.key, arranged.first())
        assertEquals(shipped.size, arranged.size)
    }

    @Test
    fun `a key stored twice draws one tile`() {
        val arranged = SuiteHomeLayout.order(listOf(AppId.HEALTH.key, AppId.HEALTH.key)).map { it.key }

        assertEquals(shipped.size, arranged.size)
        assertEquals(1, arranged.count { it == AppId.HEALTH.key })
    }

    @Test
    fun `hiding an app is not deleting it - it comes back where it was`() {
        // Arrange the screen first, so "where it was" is somewhere the suite did not ship it.
        val arranged = SuiteAppearance()
            .withMoved(AppId.HEALTH, forward = false)
            .withMoved(AppId.HEALTH, forward = false)
        val placed = arranged.homeApps.indexOfFirst { it.appId == AppId.HEALTH }

        val hidden = arranged.withHidden(AppId.HEALTH, true)
        assertFalse(hidden.homeApps.any { it.appId == AppId.HEALTH })

        val shown = hidden.withHidden(AppId.HEALTH, false)
        assertEquals(placed, shown.homeApps.indexOfFirst { it.appId == AppId.HEALTH })
        assertEquals(arranged.homeApps.map { it.key }, shown.homeApps.map { it.key })
    }

    @Test
    fun `moving swaps with the next tile`() {
        val first = SuiteApps.all[0].appId
        val second = SuiteApps.all[1].appId

        val moved = SuiteHomeLayout.moved(emptyList(), emptySet(), first, forward = true)

        assertEquals(second.key, moved[0])
        assertEquals(first.key, moved[1])
        assertEquals(shipped.drop(2), moved.drop(2))
    }

    @Test
    fun `moving skips a hidden neighbour rather than swapping with it`() {
        // A hidden app still holds an index. Swapping with it would move the tile past nothing at
        // all, and the household would tap a button that visibly did nothing.
        val first = SuiteApps.all[0].appId
        val second = SuiteApps.all[1].appId
        val third = SuiteApps.all[2].appId

        val moved = SuiteHomeLayout.moved(emptyList(), setOf(second.key), first, forward = true)

        assertEquals(listOf(third.key, second.key, first.key), moved.take(3))
        assertEquals(
            "the hidden app should not have moved",
            1,
            moved.indexOf(second.key)
        )
    }

    @Test
    fun `there is nowhere to go at the ends`() {
        val first = SuiteApps.all.first().appId
        val last = SuiteApps.all.last().appId

        assertFalse(SuiteHomeLayout.canMove(emptyList(), emptySet(), first, forward = false))
        assertFalse(SuiteHomeLayout.canMove(emptyList(), emptySet(), last, forward = true))
        assertTrue(SuiteHomeLayout.canMove(emptyList(), emptySet(), first, forward = true))
        assertTrue(SuiteHomeLayout.canMove(emptyList(), emptySet(), last, forward = false))

        // And the order comes back unchanged rather than mangled.
        assertEquals(shipped, SuiteHomeLayout.moved(emptyList(), emptySet(), first, forward = false))
    }

    @Test
    fun `the last tile standing cannot be hidden`() {
        val survivor = SuiteApps.all.first().appId
        val everyoneElse = SuiteApps.all.drop(1).map { it.key }.toSet()

        assertFalse(SuiteHomeLayout.canHide(emptyList(), everyoneElse, survivor))
        assertEquals(1, SuiteHomeLayout.visible(emptyList(), everyoneElse).size)
        // An app that is already hidden is not a candidate either — there is nothing to hide.
        assertFalse(SuiteHomeLayout.canHide(emptyList(), everyoneElse, SuiteApps.all[1].appId))
    }

    @Test
    fun `an app can be hidden while others remain`() {
        assertTrue(SuiteHomeLayout.canHide(emptyList(), emptySet(), AppId.HEALTH))
    }
}
