package com.lifeops.app.game.run

import com.lifeops.app.game.content.ChallengeMode
import com.lifeops.app.game.content.StartingWeapon

/**
 * The committed loadout for one run, resolved from banked resources before the run starts
 * (DESIGN.md §7). Everything here is expended on entry — the run screen debits the resource
 * ledger once, then hands the engine this immutable config. No conversions between resources
 * ever happen (invariant #1): each field is funded only by its own resource.
 */
data class RunConfig(
    val weapon: StartingWeapon,
    /** Ceiling on in-run leveling, funded from banked Level Cap (§2). */
    val levelCap: Int,
    /** Run survivability, funded from banked Max Health. */
    val maxHealth: Float,
    /** In-run starting purse, funded from banked Starting Gold; gold dies with the run. */
    val startingGold: Int,
    /** Deterministic run seed (week-seeded in production, fixed in tests). */
    val seed: Long,
    /** Waves in the run — one per day of the closed week (§7). */
    val waves: Int = 7,
    /** Optional challenge mode the run's Director hosts (§8). Defaults to the standard run. */
    val challengeMode: ChallengeMode = ChallengeMode.NONE,
) {
    companion object {
        const val MIN_LEVEL_CAP = 6
        const val MAX_LEVEL_CAP = 40
        const val MIN_MAX_HEALTH = 60f
        const val MAX_MAX_HEALTH = 400f

        /** A sane default loadout for a first run with an empty bank (still playable, per §2). */
        fun default(weapon: StartingWeapon = StartingWeapon.GATLING, seed: Long = 1L) = RunConfig(
            weapon = weapon,
            levelCap = 20,
            maxHealth = 100f,
            startingGold = 0,
            seed = seed,
        )
    }
}
