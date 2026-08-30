package com.lifeops.app.connection

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bus carries LifeOps' ticks out to whoever else in the process owns the task — Maintenance
 * publishes upkeep onto a future week and has to know when one is done.
 *
 * What is worth holding down is the promise that makes it safe to have at all: **a listener cannot
 * break a tick**. Everything else here is a handful of lines; that one guarantee is the reason a
 * hosted app mid-restore can't turn "mark done" into a crash in the week planner.
 */
class TaskCompletionBusTest {

    private val registered = mutableListOf<TaskCompletionBus.Listener>()

    private fun listening(listener: TaskCompletionBus.Listener): TaskCompletionBus.Listener {
        TaskCompletionBus.register(listener)
        registered += listener
        return listener
    }

    @After
    fun tearDown() {
        registered.forEach { TaskCompletionBus.unregister(it) }
        registered.clear()
    }

    private fun completion(id: String = "task-1") =
        TaskCompletionBus.Completion(id, "Truck: Oil change", 1_772_000_000_000L)

    @Test
    fun `every listener hears every completion`() {
        val heard = mutableListOf<String>()
        listening { heard += "a:${it.taskId}" }
        listening { heard += "b:${it.taskId}" }

        TaskCompletionBus.announce(completion())

        assertEquals(listOf("a:task-1", "b:task-1"), heard)
    }

    @Test
    fun `a listener that throws cannot break the tick, or the listener after it`() {
        val heard = mutableListOf<String>()
        listening { error("this app's database is mid-restore") }
        listening { heard += it.taskId }

        // The announcement itself must not throw — the caller is inside LifeOps' completion path.
        TaskCompletionBus.announce(completion())

        assertEquals(listOf("task-1"), heard)
    }

    @Test
    fun `an unregistered listener stops hearing`() {
        val heard = mutableListOf<String>()
        val listener = listening { heard += it.taskId }

        TaskCompletionBus.announce(completion("first"))
        TaskCompletionBus.unregister(listener)
        TaskCompletionBus.announce(completion("second"))

        assertEquals(listOf("first"), heard)
    }

    @Test
    fun `registering the same listener twice does not double-announce`() {
        val heard = mutableListOf<String>()
        val listener = TaskCompletionBus.Listener { heard += it.taskId }
        listening(listener)
        TaskCompletionBus.register(listener)

        TaskCompletionBus.announce(completion())

        assertEquals(1, heard.size)
    }

    @Test
    fun `announcing to nobody is not an error`() {
        TaskCompletionBus.announce(completion())
        assertTrue(registered.isEmpty())
    }
}
