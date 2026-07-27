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
    /** Player hearts (discrete): survives this many contacts. Funded from banked Max Health. */
    val maxHits: Int,
    /** In-run starting purse, funded from banked Starting Gold; gold dies with the run. */
    val startingGold: Int,
    /** Deterministic run seed (week-seeded in production, fixed in tests). */
    val seed: Long,
    /** Waves in the run — one per day of the closed week (§7). */
    val waves: Int = 7,
    /** Optional challenge mode the run's Director hosts (§8). Defaults to the standard run. */
    val challengeMode: ChallengeMode = ChallengeMode.NONE,
    /**
     * Store items unlocked in previous runs (DESIGN.md §9). Unlocked passives/equipment join this
     * run's level-up draft pool from the start; unlocked guns/mutators are surfaced by the loadout.
     * The between-set store adds to this set as the run goes.
     */
    val unlockedIds: Set<String> = emptySet(),
    /** Unlocked mutators the player toggled on for this run in the loadout; applied at run start. */
    val activeMutatorIds: Set<String> = emptySet(),
) {
    companion object {
        const val MIN_LEVEL_CAP = 6
        const val MAX_LEVEL_CAP = 40
        const val BASE_HITS = 3
        const val MAX_HITS = 12

        /** A sane default loadout for a first run with an empty bank (still playable, per §2). */
        fun default(weapon: StartingWeapon = StartingWeapon.GATLING, seed: Long = 1L) = RunConfig(
            weapon = weapon,
            levelCap = 20,
            maxHits = BASE_HITS,
            startingGold = 0,
            seed = seed,
        )
    }
}
