package com.advisor.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.advisor.app.data.repository.AdvisorRepository
import com.advisor.app.logic.AdvisorPermissions
import com.advisor.app.logic.ModelSpec
import com.advisor.app.logic.SourceApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A rendered conversation row for the chat UI. */
data class ChatMessage(
    val id: String,
    val fromUser: Boolean,
    val text: String,
    val citationIds: List<String>
)

class AdvisorViewModel(private val repo: AdvisorRepository) : ViewModel() {

    val model: ModelSpec get() = repo.model

    val permissions: StateFlow<AdvisorPermissions> =
        repo.observePermissions().stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5_000), AdvisorPermissions.NONE
        )

    val messages: StateFlow<List<ChatMessage>> =
        repo.observeMessages().map { rows ->
            rows.map { row ->
                ChatMessage(
                    id = row.id,
                    fromUser = row.role == AdvisorRepository.ROLE_USER,
                    text = row.text,
                    citationIds = row.citationIds.split('\n').filter { it.isNotBlank() }
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _thinking = MutableStateFlow(false)
    val thinking: StateFlow<Boolean> = _thinking.asStateFlow()

    fun setPermission(app: SourceApp, granted: Boolean) = viewModelScope.launch {
        repo.setPermission(app, granted)
    }

    fun ask(question: String) {
        val q = question.trim()
        if (q.isEmpty() || _thinking.value) return
        viewModelScope.launch {
            _thinking.value = true
            try {
                repo.ask(q)
            } finally {
                _thinking.value = false
            }
        }
    }

    fun clearConversation() = viewModelScope.launch { repo.clearConversation() }

    class Factory(private val repo: AdvisorRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AdvisorViewModel(repo) as T
    }
}
