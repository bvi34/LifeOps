package com.advisor.app.logic

import com.advisor.app.logic.InferenceBudget.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvisorConstraintsTest {

    private val gib = 1024L * 1024 * 1024

    /** A cool, charged 8 GB phone with plenty free — nothing to limit. */
    private fun roomy() = DeviceState(
        totalMemBytes = 7 * gib + gib / 2,
        advertisedMemBytes = 8 * gib,
        availMemBytes = 4 * gib,
        batteryPercent = 80,
        charging = false
    )

    private val model4b = 2_500_000_000L

    @Test
    fun a_roomy_cool_phone_runs_the_model_at_full_length() {
        val budget = AdvisorConstraints.budget(roomy(), model4b, loaded = false, maxTokens = 512)
        assertEquals(Mode.FULL, budget.mode)
        assertEquals(512, budget.maxTokens)
        assertTrue(budget.allowRefinement)
        assertTrue(budget.allowWarmUp)
        assertTrue(budget.reasons.isEmpty())
    }

    @Test
    fun a_hot_phone_answers_shorter_and_once() {
        val budget = AdvisorConstraints.budget(
            roomy().copy(thermal = ThermalLevel.MODERATE), model4b, loaded = true, maxTokens = 512
        )
        assertEquals(Mode.REDUCED, budget.mode)
        assertEquals(256, budget.maxTokens)
        assertFalse("a second pass is a second generation", budget.allowRefinement)
        assertFalse("no speculative load on a hot phone", budget.allowWarmUp)
        assertEquals(listOf("phone is hot"), budget.reasons)
    }

    @Test
    fun rising_headroom_reduces_before_the_status_changes() {
        val budget = AdvisorConstraints.budget(
            roomy().copy(thermal = ThermalLevel.LIGHT, thermalHeadroom = 0.9f), model4b, loaded = true
        )
        assertEquals(Mode.REDUCED, budget.mode)
        assertEquals(listOf("phone is heating up"), budget.reasons)
    }

    @Test
    fun severe_heat_pauses_the_model() {
        val budget = AdvisorConstraints.budget(
            roomy().copy(thermal = ThermalLevel.SEVERE), model4b, loaded = true
        )
        assertEquals(Mode.PAUSED, budget.mode)
        assertFalse(budget.runsModel)
        assertEquals(listOf("phone is very hot"), budget.reasons)
    }

    @Test
    fun a_forecast_of_throttling_pauses_even_while_the_status_is_still_mild() {
        val budget = AdvisorConstraints.budget(
            roomy().copy(thermal = ThermalLevel.LIGHT, thermalHeadroom = 1.02f), model4b, loaded = true
        )
        assertEquals(Mode.PAUSED, budget.mode)
    }

    @Test
    fun a_model_too_big_for_the_phone_is_never_cold_loaded() {
        val fourGb = DeviceState(
            totalMemBytes = 3 * gib + gib / 2, advertisedMemBytes = 4 * gib, availMemBytes = 2 * gib
        )
        val budget = AdvisorConstraints.budget(fourGb, model4b, loaded = false)
        assertEquals(Mode.PAUSED, budget.mode)
        assertTrue(budget.reasons.single().contains("too much for this phone's 4.0 GB"))
    }

    @Test
    fun a_smaller_model_fits_the_same_phone() {
        val fourGb = DeviceState(
            totalMemBytes = 3 * gib + gib / 2, advertisedMemBytes = 4 * gib, availMemBytes = 2 * gib
        )
        val budget = AdvisorConstraints.budget(fourGb, 1_100_000_000L, loaded = false)
        assertEquals(Mode.FULL, budget.mode)
    }

    @Test
    fun low_memory_blocks_a_cold_load_but_not_a_resident_model() {
        val tight = roomy().copy(availMemBytes = gib / 2, lowMemory = true)
        assertEquals(Mode.PAUSED, AdvisorConstraints.budget(tight, model4b, loaded = false).mode)
        assertEquals(
            "freeing nothing by refusing weights already in memory",
            Mode.FULL, AdvisorConstraints.budget(tight, model4b, loaded = true).mode
        )
    }

    @Test
    fun no_model_installed_means_nothing_to_fit() {
        val tight = roomy().copy(availMemBytes = gib / 2, lowMemory = true)
        assertEquals(Mode.FULL, AdvisorConstraints.budget(tight, modelBytes = 0, loaded = false).mode)
    }

    @Test
    fun battery_saver_reduces_and_skips_the_speculative_load() {
        val budget = AdvisorConstraints.budget(roomy().copy(powerSave = true), model4b, loaded = false)
        assertEquals(Mode.REDUCED, budget.mode)
        assertFalse(budget.allowWarmUp)
        assertEquals(listOf("battery saver is on"), budget.reasons)
    }

    @Test
    fun a_low_battery_counts_only_off_the_charger() {
        val low = roomy().copy(batteryPercent = 10)
        assertEquals(Mode.REDUCED, AdvisorConstraints.budget(low, model4b, loaded = true).mode)
        assertEquals(
            Mode.FULL,
            AdvisorConstraints.budget(low.copy(charging = true), model4b, loaded = true).mode
        )
    }

    @Test
    fun a_reduced_budget_never_drops_below_a_usable_reply_nor_above_the_callers_limit() {
        val hot = roomy().copy(thermal = ThermalLevel.MODERATE)
        assertEquals(
            AdvisorConstraints.MIN_REDUCED_TOKENS,
            AdvisorConstraints.budget(hot, model4b, loaded = true, maxTokens = 256).maxTokens
        )
        assertEquals(100, AdvisorConstraints.budget(hot, model4b, loaded = true, maxTokens = 100).maxTokens)
    }

    @Test
    fun a_low_memory_kill_while_the_model_was_resident_stops_the_speculative_load() {
        val budget = AdvisorConstraints.budget(
            roomy().copy(lastLowMemoryKillRssBytes = 3 * gib), model4b, loaded = false
        )
        assertEquals("a question may still load it", Mode.FULL, budget.mode)
        assertFalse(budget.allowWarmUp)
    }

    @Test
    fun a_low_memory_kill_the_model_could_not_have_caused_is_not_held_against_it() {
        val budget = AdvisorConstraints.budget(
            roomy().copy(lastLowMemoryKillRssBytes = 400L * 1024 * 1024), model4b, loaded = false
        )
        assertTrue(budget.allowWarmUp)
    }

    @Test
    fun the_card_line_names_what_the_phone_has_and_what_it_allows() {
        val state = roomy().copy(thermal = ThermalLevel.MODERATE, thermalHeadroom = 0.91f)
        val line = AdvisorConstraints.describe(state, AdvisorConstraints.budget(state, model4b, loaded = true))
        assertEquals(
            "8.0 GB RAM · 4.0 GB free · hot (headroom 0.91) · battery 80%\nReduced — phone is hot",
            line
        )
    }

    @Test
    fun thermal_levels_follow_the_platforms_order() {
        // DeviceMonitor maps PowerManager.THERMAL_STATUS_* (0..6) onto these by name; the policy
        // compares them by order, so the order has to be the platform's.
        assertEquals(
            listOf("NONE", "LIGHT", "MODERATE", "SEVERE", "CRITICAL", "EMERGENCY", "SHUTDOWN"),
            ThermalLevel.entries.map { it.name }
        )
        assertFalse(ThermalLevel.MODERATE.stopsGeneration)
        assertTrue(ThermalLevel.SEVERE.stopsGeneration)
    }
}
