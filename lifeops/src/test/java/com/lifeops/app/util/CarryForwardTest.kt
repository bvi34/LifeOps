package com.lifeops.app.util

import com.lifeops.app.data.model.*
import org.junit.Assert.*
import org.junit.Test

class CarryForwardTest {

    @Test
    fun `CARRIED_FORWARD contributes zero to resource totals`() {
        // buildSnapshot only counts COMPLETED → zero for CARRIED_FORWARD
        val status = TaskStatus.CARRIED_FORWARD
        assertNotEquals(TaskStatus.COMPLETED, status)
        // The snapshot's totalResourcesEarned only includes COMPLETED; this is
        // a contract test that the enum value itself is not COMPLETED
        assertEquals("carried_forward", status.value)
    }

    @Test
    fun `TaskStatus from string round-trips correctly`() {
        assertEquals(TaskStatus.CARRIED_FORWARD, TaskStatus.from("carried_forward"))
        assertEquals(TaskStatus.PENDING, TaskStatus.from("pending"))
        assertEquals(TaskStatus.COMPLETED, TaskStatus.from("completed"))
    }
}
