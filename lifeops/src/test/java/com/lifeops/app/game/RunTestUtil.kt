package com.lifeops.app.game

import com.lifeops.app.game.run.RunEngine
import com.lifeops.app.game.run.RunStatus
import com.lifeops.app.game.run.chooseOverflow
import com.lifeops.app.game.run.chooseSetBonus
import com.lifeops.app.game.run.skipStore

/**
 * Auto-resolve whichever pause the engine is sitting on by taking the first offered choice: a
 * level-up pick, the per-set boon/bane draft, or the overflow micro-pick. Tests that drive a run to
 * completion call this each frame so pauses never stall the loop.
 */
fun resolvePauses(engine: RunEngine) {
    when (engine.status) {
        RunStatus.LEVEL_UP -> engine.snapshot().levelUpOptions.firstOrNull()?.let { engine.choose(it) }
        RunStatus.SET_BONUS -> engine.snapshot().setBonusOptions.firstOrNull()?.let { engine.chooseSetBonus(it) }
        // The between-set store follows the set draft; skip it (no banked spend in the pure engine).
        RunStatus.STORE -> engine.skipStore()
        RunStatus.OVERFLOW -> engine.snapshot().overflowOptions.firstOrNull()?.let { engine.chooseOverflow(it) }
        else -> {}
    }
}
