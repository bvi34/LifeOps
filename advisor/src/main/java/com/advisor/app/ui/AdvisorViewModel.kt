package com.advisor.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.advisor.app.data.memory.TagCount
import com.advisor.app.data.repository.AdvisorRepository
import com.advisor.app.logic.AdvisorPermissions
import com.advisor.app.logic.Identity
import com.advisor.app.logic.MemoryRecord
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

    // --- long-term memory ---

    val memories: StateFlow<List<MemoryRecord>> =
        repo.observeMemories().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val tagCounts: StateFlow<List<TagCount>> =
        repo.observeTagCounts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // --- identity (loaded once from JSON) ---

    private val _identity = MutableStateFlow(Identity.EMPTY)
    val identity: StateFlow<Identity> = _identity.asStateFlow()

    private val _thinking = MutableStateFlow(false)
    val thinking: StateFlow<Boolean> = _thinking.asStateFlow()

    init {
        viewModelScope.launch { _identity.value = repo.loadIdentity() }
    }

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

    fun remember(content: String, tagsCsv: String, salience: Int, pinned: Boolean) = viewModelScope.launch {
        val tags = tagsCsv.split(',').map { it.trim() }.filter { it.isNotBlank() }
        repo.remember(content, tags, salience, pinned)
    }

    fun forget(id: String) = viewModelScope.launch { repo.forget(id) }

    fun setMemoryPinned(id: String, pinned: Boolean) = viewModelScope.launch {
        repo.setMemoryPinned(id, pinned)
    }

    class Factory(private val repo: AdvisorRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AdvisorViewModel(repo) as T
    }
}
