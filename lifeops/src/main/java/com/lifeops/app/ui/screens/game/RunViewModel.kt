package com.lifeops.app.ui.screens.game

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lifeops.app.data.model.GameResource
import com.lifeops.app.data.model.GameScore
import com.lifeops.app.data.repository.GameResourceRepository
import com.lifeops.app.data.repository.GameScoreRepository
import com.lifeops.app.data.repository.GameUnlockRepository
import com.lifeops.app.data.repository.PreferencesRepository
import com.lifeops.app.game.content.ChallengeMode
import com.lifeops.app.game.content.StartingWeapon
import com.lifeops.app.game.content.StoreCatalog
import com.lifeops.app.game.run.StoreOffer
import com.lifeops.app.game.core.RunSeed
import com.lifeops.app.game.run.Loadout
import com.lifeops.app.game.run.RunConfig
import com.lifeops.app.game.run.RunEngine
import com.lifeops.app.game.run.RunSnapshot
import com.lifeops.app.util.DateUtil
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** How much of a loadout-role resource the player has committed to this run. */
data class Commitment(val levelCap: Int = 0, val maxHealth: Int = 0, val gold: Int = 0)

data class RunUiState(
    val resources: List<GameResource> = emptyList(),
    val weapon: StartingWeapon = StartingWeapon.GATLING,
    val challengeMode: ChallengeMode = ChallengeMode.NONE,
    val commitment: Commitment = Commitment(),
    /** Store items unlocked in previous runs (DESIGN.md §9) — gates loadout guns/mutators + the pool. */
    val unlockedIds: Set<String> = emptySet(),
    /** Unlocked mutators the player has toggled on for the next run in the loadout. */
    val activeMutatorIds: Set<String> = emptySet(),
    /** Whether the weekly dev/sandbox run is available (not yet used this week). */
    val devRunAvailable: Boolean = false,
    val message: String? = null,
) {
    /** Weapons offered in the loadout: the always-on baseline plus any unlocked store guns. */
    val availableWeapons: List<StartingWeapon>
        get() = StartingWeapon.values().filter { !it.unlockable } + StoreCatalog.unlockedGuns(unlockedIds)
}

/**
 * Owns the run lifecycle: the pre-run loadout (weapon + committed resources), the one-shot resource
 * debit on entry (energy gate + committed loadout, all expended per DESIGN.md §7), and the live
 * [RunEngine]. The engine is pure; the frame loop lives in the screen. The engine flow being null
 * means "in the loadout screen"; non-null means "a run is in progress".
 */
class RunViewModel(
    private val gameResourceRepository: GameResourceRepository,
    private val gameScoreRepository: GameScoreRepository,
    private val gameUnlockRepository: GameUnlockRepository,
    private val preferencesRepository: PreferencesRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RunUiState(devRunAvailable = computeDevRunAvailable()))
    val uiState: StateFlow<RunUiState> = _uiState.asStateFlow()

    private val _engine = MutableStateFlow<RunEngine?>(null)
    val engine: StateFlow<RunEngine?> = _engine.asStateFlow()

    /** True while the in-progress run is a dev/sandbox run: no banked spend, nothing permanent. */
    private var devRunActive = false

    /** The dev run is once a week; a new week (or never having run one) opens the gate. */
    private fun computeDevRunAvailable(): Boolean =
        preferencesRepository.lastDevRunWeek != DateUtil.currentWeekStart().toString()

    /** Loadout facts captured at run start so the scoreboard record needs only the final numbers. */
    private data class RunMeta(
        val weekKey: String,
        val pointInvestment: Int,
        val totalWaves: Int,
        val weapon: String,
        val challengeMode: String,
    )

    /** Non-null between run start and its one scoreboard write; nulled after recording so a run is
     *  logged exactly once no matter how many terminal frames the screen observes. */
    private var runMeta: RunMeta? = null

    init {
        viewModelScope.launch {
            gameResourceRepository.observeResources().collectLatest { resources ->
                _uiState.update { it.copy(resources = resources, commitment = it.commitment.clampedTo(resources)) }
            }
        }
        viewModelScope.launch {
            gameUnlockRepository.observeUnlockedIds().collectLatest { ids ->
                _uiState.update { state ->
                    // Keep the loadout coherent if the currently-selected gun is somehow no longer
                    // owned, and drop any toggled mutators that were cleared.
                    val weaponOk = !state.weapon.unlockable || StoreCatalog.unlockedGuns(ids).contains(state.weapon)
                    state.copy(
                        unlockedIds = ids,
                        activeMutatorIds = state.activeMutatorIds.intersect(ids),
                        weapon = if (weaponOk) state.weapon else StartingWeapon.GATLING,
                    )
                }
            }
        }
    }

    fun selectWeapon(weapon: StartingWeapon) = _uiState.update { it.copy(weapon = weapon) }

    /** Toggle an owned mutator on/off for the next run (loadout opt-in — DESIGN.md §9). */
    fun toggleMutator(id: String) = _uiState.update { state ->
        if (id !in state.unlockedIds) state
        else state.copy(
            activeMutatorIds = if (id in state.activeMutatorIds) state.activeMutatorIds - id
            else state.activeMutatorIds + id
        )
    }

    fun selectChallengeMode(mode: ChallengeMode) = _uiState.update { it.copy(challengeMode = mode) }

    fun setCommitment(commitment: Commitment) =
        _uiState.update { it.copy(commitment = commitment.clampedTo(it.resources)) }

    private fun Commitment.clampedTo(resources: List<GameResource>): Commitment {
        // Bound each commitment by the banked balance and by the amount the stat can actually use
        // (past a stat's ceiling, extra commitment is spent for no gain). Gold is 1:1 and uncapped.
        fun cap(role: Loadout.Role, v: Int, maxUseful: Int = Int.MAX_VALUE): Int {
            val balance = Loadout.resolve(role, resources)?.currentValue ?: 0
            return v.coerceIn(0, minOf(balance, maxUseful))
        }
        return Commitment(
            levelCap = cap(Loadout.Role.LEVEL_CAP, levelCap, Loadout.MAX_USEFUL_LEVEL_CAP),
            maxHealth = cap(Loadout.Role.MAX_HEALTH, maxHealth, Loadout.MAX_USEFUL_HEALTH),
            gold = cap(Loadout.Role.STARTING_GOLD, gold),
        )
    }

    fun canAfford(): Boolean = Loadout.canAfford(_uiState.value.resources)

    /** Whether the player can buy back into a lost run. A dev run revives for free; a normal run
     *  needs the banked Energy for the 2× entry price. */
    fun canRevive(): Boolean = devRunActive || Loadout.canRevive(_uiState.value.resources)

    /** The Energy price of a revive (2× run entry) for the defeat screen; free (0) in a dev run. */
    val reviveCost: Int get() = if (devRunActive) 0 else Loadout.REVIVE_COST

    /**
     * Buy back into a lost run for [reviveCost] banked Energy (DESIGN.md §7). Debits the ledger and
     * tells the engine to revive; a no-op (with a message) if the player can't afford it. The
     * scoreboard write is deferred by the screen while a revive is affordable, so a revived run is
     * still logged exactly once — with its final, higher score.
     */
    fun revive() {
        val engine = _engine.value ?: return
        // A dev run revives for free — no banked spend, like every other cost in the sandbox.
        if (devRunActive) {
            engine.revive()
            return
        }
        val state = _uiState.value
        if (!Loadout.canRevive(state.resources)) {
            _uiState.update { it.copy(message = "Not enough Energy to revive.") }
            return
        }
        viewModelScope.launch {
            Loadout.energyResource(state.resources)?.let {
                gameResourceRepository.spendResource(it.id, Loadout.REVIVE_COST, "Run revive")
            }
            engine.revive()
        }
    }

    /**
     * Debit the ledger and start a run. Locked out at zero energy (invariant #6): if the player
     * can't pay the entry cost, no run begins. Committed loadout resources are spent here too, so
     * they are genuinely expended by the run.
     */
    fun startRun() {
        val state = _uiState.value
        if (!Loadout.canAfford(state.resources)) {
            _uiState.update { it.copy(message = "Not enough Energy to run. Close a week to earn more.") }
            return
        }
        val commitment = state.commitment.clampedTo(state.resources)
        viewModelScope.launch {
            // Energy gate.
            Loadout.energyResource(state.resources)?.let {
                gameResourceRepository.spendResource(it.id, Loadout.ENERGY_COST, "Run entry")
            }
            // Committed loadout (each funds only its own stat — no cross-conversion).
            spendCommitment(Loadout.Role.LEVEL_CAP, commitment.levelCap, state.resources)
            spendCommitment(Loadout.Role.MAX_HEALTH, commitment.maxHealth, state.resources)
            spendCommitment(Loadout.Role.STARTING_GOLD, commitment.gold, state.resources)

            val weekKey = DateUtil.currentWeekStart().toString()
            val config = RunConfig(
                weapon = state.weapon,
                levelCap = Loadout.levelCapFor(commitment.levelCap),
                maxHits = Loadout.heartsFor(commitment.maxHealth),
                startingGold = commitment.gold,
                seed = RunSeed.fromWeek(weekKey),
                challengeMode = state.challengeMode,
                unlockedIds = state.unlockedIds,
                activeMutatorIds = state.activeMutatorIds,
            )
            // Point investment = every banked point the run spent: the energy gate plus each
            // committed loadout resource (no cross-conversion — this is just their sum for display).
            runMeta = RunMeta(
                weekKey = weekKey,
                pointInvestment = Loadout.ENERGY_COST + commitment.levelCap + commitment.maxHealth + commitment.gold,
                totalWaves = config.waves,
                weapon = state.weapon.displayName,
                challengeMode = state.challengeMode.name,
            )
            devRunActive = false
            _engine.value = RunEngine(config)
            _uiState.update { it.copy(message = null) }
        }
    }

    /**
     * Start the weekly dev/sandbox run (DESIGN.md §7): unlimited resources, no banked spend, ending
     * automatically after [RunConfig.DEV_RUN_SETS] sets. Once a week — a used gate blocks re-entry
     * until the week rolls over. Nothing earned in it is permanent (the store is free and unrecorded)
     * and the run is never logged to the scoreboard, so it can't pollute the standings.
     */
    fun startDevRun() {
        if (!computeDevRunAvailable()) {
            _uiState.update {
                it.copy(devRunAvailable = false, message = "Dev run already used this week — resets Monday.")
            }
            return
        }
        val state = _uiState.value
        val weekKey = DateUtil.currentWeekStart().toString()
        preferencesRepository.lastDevRunWeek = weekKey
        devRunActive = true
        // A dev run is ephemeral: it is not recorded on the scoreboard, so there is no run meta.
        runMeta = null
        val config = RunConfig.devRun(
            weapon = state.weapon,
            seed = RunSeed.fromWeek(weekKey),
            unlockedIds = state.unlockedIds,
            activeMutatorIds = state.activeMutatorIds,
            challengeMode = state.challengeMode,
        )
        _engine.value = RunEngine(config)
        _uiState.update { it.copy(devRunAvailable = false, message = null) }
    }

    /**
     * Log a finished run to the scoreboard, exactly once. The screen calls this on the first frame it
     * sees a terminal status; [runMeta] is cleared here so repeated terminal frames don't re-record.
     */
    fun recordRunEnd(snapshot: RunSnapshot) {
        val meta = runMeta ?: return
        runMeta = null
        viewModelScope.launch {
            gameScoreRepository.record(
                GameScore(
                    id = UUID.randomUUID().toString(),
                    weekKey = meta.weekKey,
                    pointInvestment = meta.pointInvestment,
                    score = snapshot.score,
                    setReached = snapshot.tier + 1,
                    waveReached = snapshot.wave,
                    totalWaves = meta.totalWaves,
                    levelReached = snapshot.level,
                    weapon = meta.weapon,
                    challengeMode = meta.challengeMode,
                    createdAt = DateUtil.now(),
                )
            )
        }
    }

    /** The banked Modifier-Budget balance that funds the store (DESIGN.md §9), for the overlay. */
    fun modifierBudgetBalance(): Int =
        Loadout.modifierBudgetResource(_uiState.value.resources)?.currentValue ?: 0

    val modifierBudgetName: String
        get() = Loadout.modifierBudgetResource(_uiState.value.resources)?.name ?: "Modifier Budget"

    /**
     * Buy a store offer (DESIGN.md §9): debit banked Modifier Budget, record the permanent unlock,
     * and tell the engine to grant it to the run. A no-op with a message if unaffordable — the engine
     * never touches the bank (the same contract as [revive]).
     */
    fun purchaseStore(offer: StoreOffer) {
        val engine = _engine.value ?: return
        // In a dev run the store is free and impermanent: grant it to the run only — no banked spend,
        // no unlock record. It vanishes with the run, like everything else in the sandbox.
        if (devRunActive) {
            engine.applyStorePurchase(offer.itemId)
            _uiState.update { it.copy(message = null) }
            return
        }
        val state = _uiState.value
        val budget = Loadout.modifierBudgetResource(state.resources)
        if ((budget?.currentValue ?: 0) < offer.cost) {
            _uiState.update { it.copy(message = "Not enough ${budget?.name ?: "Modifier Budget"} to buy ${offer.name}.") }
            return
        }
        viewModelScope.launch {
            budget?.let { gameResourceRepository.spendResource(it.id, offer.cost, "Store: ${offer.name}") }
            gameUnlockRepository.unlock(offer.itemId)
            engine.applyStorePurchase(offer.itemId)
            _uiState.update { it.copy(message = null) }
        }
    }

    /** Decline the store this set and roll on. */
    fun skipStore() {
        _engine.value?.skipStore()
    }

    private suspend fun spendCommitment(role: Loadout.Role, amount: Int, resources: List<GameResource>) {
        if (amount <= 0) return
        Loadout.resolve(role, resources)?.let {
            gameResourceRepository.spendResource(it.id, amount, "Run loadout: ${role.label}")
        }
    }

    /** Leave the run and return to the loadout screen (resources will have been debited already). */
    fun exitRun() {
        // Drop any un-recorded meta: leaving mid-run is not a finished run and should not be logged.
        runMeta = null
        devRunActive = false
        _engine.value = null
        // Re-read the once-a-week gate so returning to the loadout reflects a just-used dev run.
        _uiState.update { it.copy(commitment = Commitment(), devRunAvailable = computeDevRunAvailable()) }
    }

    fun dismissMessage() = _uiState.update { it.copy(message = null) }
}

class RunViewModelFactory(
    private val gameResourceRepository: GameResourceRepository,
    private val gameScoreRepository: GameScoreRepository,
    private val gameUnlockRepository: GameUnlockRepository,
    private val preferencesRepository: PreferencesRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        RunViewModel(gameResourceRepository, gameScoreRepository, gameUnlockRepository, preferencesRepository) as T
}
