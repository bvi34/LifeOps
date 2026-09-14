package com.lifeops.app.data.model



/**
 * What completed work earns and what planned work costs — the resources, the ledger of
 * transactions, and the scoring rows the Growth Record is drawn from.
 */

data class GameResource(
    val id: String,
    val name: String,
    val currentValue: Int = 0,
    val lifetimeEarned: Int = 0,
    val slotIndex: Int
)

data class GameResourceMapping(
    val id: String,
    val gameResourceId: String,
    val aspectId: String,
    val weight: Float = 1.0f
)

/** One finished run's scoreboard record (DESIGN.md §6). See GameScoreEntity for field meanings. */
data class GameScore(
    val id: String,
    val weekKey: String,
    val pointInvestment: Int,
    val score: Long,
    val setReached: Int,
    val waveReached: Int,
    val totalWaves: Int,
    val levelReached: Int,
    val weapon: String,
    val challengeMode: String,
    val createdAt: String,
)

data class ResourceTransaction(
    val id: String,
    val resourceId: String,
    val amount: Int,
    val type: String,
    val note: String? = null,
    val createdAt: String
)

/**
 * Per-aspect record sealed into a closed week's snapshot at week-close. Carries the
 * minutes spent plus the aspect's name + colour AS THEY WERE that week, so the Growth
 * Record ring stays faithful even if the aspect is later deleted, renamed or recoloured.
 */
data class AspectHistoryEntry(
    val minutes: Int,
    val name: String,
    val colorHex: String
)

data class ScoringPoint(val weekLabel: String, val resourcesEarned: Int)

data class PriorityCompletionRow(
    val priority: Priority,
    val completedCount: Int,
    val totalCount: Int
) {
    val rate: Float get() = if (totalCount > 0) completedCount.toFloat() / totalCount else 0f
}

enum class ResourceResetCycle(val label: String) {
    WEEKLY("weekly"),
    MONTHLY("monthly"),
    NEVER("never");

    companion object {
        fun from(value: String) = entries.firstOrNull { it.label == value } ?: MONTHLY
    }
}

data class CostResource(
    val id: String,
    val name: String,
    val resetCycle: ResourceResetCycle = ResourceResetCycle.MONTHLY,
    val capacity: Int? = null,
    val isActive: Boolean = true,
    val sortIndex: Int = 0,
    val createdAt: String
)

data class TaskCostEntry(
    val id: String,
    val taskId: String,
    val resourceId: String,
    val amount: Int,
    val note: String? = null,
    val recordedAt: String
)

data class CostUsageRow(
    val resourceName: String,
    val resetCycle: ResourceResetCycle,
    val capacity: Int?,
    val totalAmount: Int
)
