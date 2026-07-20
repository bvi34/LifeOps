package com.lifeops.app.ui.screens.game

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lifeops.app.data.model.GameResource
import com.lifeops.app.data.repository.GameResourceRepository
import com.lifeops.app.game.content.ChallengeMode
import com.lifeops.app.game.content.StartingWeapon
import com.lifeops.app.game.core.RunSeed
import com.lifeops.app.game.run.Loadout
import com.lifeops.app.game.run.RunConfig
import com.lifeops.app.game.run.RunEngine
import com.lifeops.app.util.DateUtil
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
    val message: String? = null,
)

/**
 * Owns the run lifecycle: the pre-run loadout (weapon + committed resources), the one-shot resource
 * debit on entry (energy gate + committed loadout, all expended per DESIGN.md §7), and the live
 * [RunEngine]. The engine is pure; the frame loop lives in the screen. The engine flow being null
 * means "in the loadout screen"; non-null means "a run is in progress".
 */
class RunViewModel(
    private val gameResourceRepository: GameResourceRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RunUiState())
    val uiState: StateFlow<RunUiState> = _uiState.asStateFlow()

    private val _engine = MutableStateFlow<RunEngine?>(null)
    val engine: StateFlow<RunEngine?> = _engine.asStateFlow()

    init {
        viewModelScope.launch {
            gameResourceRepository.observeResources().collectLatest { resources ->
                _uiState.update { it.copy(resources = resources, commitment = it.commitment.clampedTo(resources)) }
            }
        }
    }

    fun selectWeapon(weapon: StartingWeapon) = _uiState.update { it.copy(weapon = weapon) }

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

            val config = RunConfig(
                weapon = state.weapon,
                levelCap = Loadout.levelCapFor(commitment.levelCap),
                maxHits = Loadout.heartsFor(commitment.maxHealth),
                startingGold = commitment.gold,
                seed = RunSeed.fromWeek(DateUtil.currentWeekStart().toString()),
                challengeMode = state.challengeMode,
            )
            _engine.value = RunEngine(config)
            _uiState.update { it.copy(message = null) }
        }
    }

    private suspend fun spendCommitment(role: Loadout.Role, amount: Int, resources: List<GameResource>) {
        if (amount <= 0) return
        Loadout.resolve(role, resources)?.let {
            gameResourceRepository.spendResource(it.id, amount, "Run loadout: ${role.label}")
        }
    }

    /** Leave the run and return to the loadout screen (resources will have been debited already). */
    fun exitRun() {
        _engine.value = null
        _uiState.update { it.copy(commitment = Commitment()) }
    }

    fun dismissMessage() = _uiState.update { it.copy(message = null) }
}

class RunViewModelFactory(
    private val gameResourceRepository: GameResourceRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        RunViewModel(gameResourceRepository) as T
}
