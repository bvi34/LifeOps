package com.lifeops.app.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One permanently-unlocked store item (DESIGN.md §9 — "purchased modifiers are real records… from
 * the first purchase; the run client consumes them with no migration"). The [id] is the stable
 * [com.lifeops.app.game.content.StoreCatalog.Item.id]. Presence = owned; there is no rank or count
 * here — an artifact's in-run rank lives in the run, not the bank. Safe to prune (re-buyable).
 */
@Entity(tableName = "game_unlocks")
data class GameUnlockEntity(
    @PrimaryKey val id: String,
    /** When it was first bought, for display/ordering. */
    val unlockedAt: String,
)
