package com.lifeops.app.util

import org.junit.Assert.*
import org.junit.Test

class LineageTest {

    // Simulate the walk-to-root logic used in ReportsViewModel
    private fun walkToRoot(taskId: String, taskById: Map<String, Pair<String, String?>>): String? {
        // taskById: id -> (id, carriedFromTaskId)
        var cur = taskById[taskId]
        val seen = mutableSetOf<String>()
        while (cur?.second != null && cur.first !in seen) {
            seen.add(cur.first)
            cur = taskById[cur.second!!]
        }
        return cur?.first
    }

    @Test
    fun `root task returns itself`() {
        val tasks = mapOf("A" to Pair("A", null))
        assertEquals("A", walkToRoot("A", tasks))
    }

    @Test
    fun `child returns root`() {
        val tasks = mapOf(
            "A" to Pair("A", null),
            "B" to Pair("B", "A")
        )
        assertEquals("A", walkToRoot("B", tasks))
    }

    @Test
    fun `deep chain returns root`() {
        val tasks = mapOf(
            "A" to Pair("A", null),
            "B" to Pair("B", "A"),
            "C" to Pair("C", "B")
        )
        assertEquals("A", walkToRoot("C", tasks))
    }

    @Test
    fun `cycle guard prevents infinite loop`() {
        // A → B → A (cycle)
        val tasks = mapOf(
            "A" to Pair("A", "B"),
            "B" to Pair("B", "A")
        )
        // Should terminate without hanging
        val result = walkToRoot("A", tasks)
        assertNotNull(result)
    }
}
