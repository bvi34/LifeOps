package com.lifeops.app.ui.screens.game

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lifeops.app.data.model.GameScore
import com.lifeops.app.data.repository.GameScoreRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/** Read-only view of the game scoreboard (DESIGN.md §6): finished runs, best score first. */
class ScoreboardViewModel(
    gameScoreRepository: GameScoreRepository,
) : ViewModel() {

    val scores: StateFlow<List<GameScore>> = gameScoreRepository.observeScores()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

class ScoreboardViewModelFactory(
    private val gameScoreRepository: GameScoreRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        ScoreboardViewModel(gameScoreRepository) as T
}
