package com.operations.vaultkit

import com.operations.backupkit.AppId
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The registry a rebuilt vault asks to fill itself.
 *
 * The interesting cases are the unhappy ones: an app that has nothing, an app that throws, and a
 * phone where no app is registered at all. Each of those happens on the day somebody has just lost
 * their passphrase, which is the worst possible day to discover that one broken source took the
 * other eight down with it.
 */
class SecretSourcesTest {

    private class FakeSource(
        override val owner: AppId,
        private val count: Int,
        private val explode: Boolean = false
    ) : SecretSource {
        var asked = 0
        override suspend fun refile(): Int {
            asked++
            if (explode) error("this app is having a bad day")
            return count
        }
    }

    @After
    fun tearDown() = SecretSources.reset()

    @Test
    fun `every registered app is asked, and the counts are kept apart`() = runTest {
        SecretSources.register(FakeSource(AppId.FINANCE, 3))
        SecretSources.register(FakeSource(AppId.CITATION, 2))

        val refill = SecretSources.refileAll()

        assertEquals(5, refill.filed)
        assertEquals(mapOf(AppId.FINANCE to 3, AppId.CITATION to 2), refill.byApp)
        assertEquals("Finance 3, Citation 2", refill.summary())
        assertFalse(refill.empty)
    }

    @Test
    fun `a source that throws counts as nothing and does not stop the others`() = runTest {
        SecretSources.register(FakeSource(AppId.FINANCE, 0, explode = true))
        val citation = FakeSource(AppId.CITATION, 2)
        SecretSources.register(citation)

        val refill = SecretSources.refileAll()

        assertEquals(1, citation.asked)
        assertEquals(2, refill.filed)
        assertEquals(0, refill.byApp[AppId.FINANCE])
    }

    @Test
    fun `an app with nothing stored is reported as nothing rather than left out`() = runTest {
        SecretSources.register(FakeSource(AppId.FINANCE, 0))

        val refill = SecretSources.refileAll()

        assertTrue(refill.empty)
        assertEquals(setOf(AppId.FINANCE), refill.byApp.keys)
        assertEquals(emptyList<AppId>(), refill.contributed)
        assertEquals("", refill.summary())
    }

    @Test
    fun `no sources at all is an empty refill, not a failure`() = runTest {
        val refill = SecretSources.refileAll()

        assertTrue(refill.empty)
        assertTrue(refill.byApp.isEmpty())
        assertEquals(emptyList<AppId>(), SecretSources.owners)
    }

    @Test
    fun `registering twice replaces rather than doubling`() = runTest {
        SecretSources.register(FakeSource(AppId.FINANCE, 3))
        SecretSources.register(FakeSource(AppId.FINANCE, 1))

        val refill = SecretSources.refileAll()

        assertEquals(listOf(AppId.FINANCE), SecretSources.owners)
        assertEquals(1, refill.filed)
    }

    @Test
    fun `only the apps that gave something are named in the summary`() = runTest {
        SecretSources.register(FakeSource(AppId.FINANCE, 0))
        SecretSources.register(FakeSource(AppId.CITATION, 4))

        val refill = SecretSources.refileAll()

        assertEquals("Citation 4", refill.summary())
        assertEquals(listOf(AppId.CITATION), refill.contributed)
    }
}
