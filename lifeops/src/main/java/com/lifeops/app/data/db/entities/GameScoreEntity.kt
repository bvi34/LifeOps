package com.lifeops.app.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One finished run's scoreboard record (DESIGN.md §6 — "week-seeded runs make the scoreboard a
 * record of which weeks were legendary"). Written when a run ends. Purely a historical log; nothing
 * reads it back into a run, so it is safe to prune.
 */
@Entity(tableName = "game_scores", indices = [Index("score")])
data class GameScoreEntity(
    @PrimaryKey val id: String,
    /** The closed week that seeded the run (the loadout's week key). */
    val weekKey: String,
    /** Total banked points committed to the run's loadout (energy entry + level cap + health + gold). */
    val pointInvestment: Int,
    val score: Long,
    /** Endless set (loop) reached — 1-based, the same "Set" the HUD shows. */
    val setReached: Int,
    /** Wave reached within that set — 1-based. */
    val waveReached: Int,
    val totalWaves: Int,
    val levelReached: Int,
    val weapon: String,
    val challengeMode: String,
    val createdAt: String,
)
