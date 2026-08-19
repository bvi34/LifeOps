package com.advisor.app.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.advisor.app.data.memory.TagCount
import com.advisor.app.data.repository.AdvisorRepository
import com.advisor.app.llm.AdvisorModelInfo
import com.advisor.app.logic.AdvisorPermissions
import com.advisor.app.logic.Identity
import com.advisor.app.logic.MemoryRecord
import com.advisor.app.logic.ModelSpec
import com.advisor.app.logic.Profile
import com.advisor.app.logic.ProfileKind
import com.advisor.app.logic.SourceApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A rendered conversation row for the chat UI. */
/** The system-prompt editor's state: what the model is currently told, and whether that is the user's. */
data class SystemPromptUiState(
    val text: String = "",
    val isCustom: Boolean = false
)

data class ChatMessage(
    val id: String,
    val fromUser: Boolean,
    val text: String,
    val citationIds: List<String>,
    val isClarification: Boolean = false
)

/** The state of the on-device model file, for the Permissions model card. */
sealed interface ModelUiState {
    /** Not importing: shows whether a model file is installed. */
    data class Idle(val info: AdvisorModelInfo) : ModelUiState
    /** A copy is in progress; [total] is -1 when the source size is unknown. */
    data class Importing(val copied: Long, val total: Long) : ModelUiState
    data class Error(val message: String) : ModelUiState
}

class AdvisorViewModel(private val repo: AdvisorRepository) : ViewModel() {

    val model: ModelSpec get() = repo.model

    /** Ground-truth diagnostic for the model card — the loaded file, or why it's still placeholder. */
    val modelStatus: String get() = repo.modelStatus

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
                    citationIds = row.citationIds.split('\n').filter { it.isNotBlank() },
                    isClarification = row.kind == AdvisorRepository.KIND_CLARIFICATION
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

    // --- standing profiles (JSON files; loaded imperatively and refreshed after changes) ---

    private val _profiles = MutableStateFlow<List<Profile>>(emptyList())
    val profiles: StateFlow<List<Profile>> = _profiles.asStateFlow()

    private val _thinking = MutableStateFlow(false)
    val thinking: StateFlow<Boolean> = _thinking.asStateFlow()

    // --- on-device model file ---

    private val _modelState = MutableStateFlow<ModelUiState>(ModelUiState.Idle(repo.modelInfo()))
    val modelState: StateFlow<ModelUiState> = _modelState.asStateFlow()

    // --- on-device embedding model file (powers semantic retrieval) ---

    private val _embeddingModelState = MutableStateFlow<ModelUiState>(ModelUiState.Idle(repo.embeddingModelInfo()))
    val embeddingModelState: StateFlow<ModelUiState> = _embeddingModelState.asStateFlow()

    /** True when an embedding model is active, so retrieval is semantic rather than lexical. */
    val semanticRetrieval: Boolean get() = repo.semanticRetrieval

    // --- the standing system prompt (user-editable) ---

    private val _systemPrompt = MutableStateFlow(SystemPromptUiState())
    val systemPrompt: StateFlow<SystemPromptUiState> = _systemPrompt.asStateFlow()

    /** The shipped instruction, shown as the reset target and as the editor's starting point. */
    val defaultSystemPrompt: String get() = repo.defaultSystemPrompt

    fun refreshSystemPrompt() = viewModelScope.launch {
        _systemPrompt.value = SystemPromptUiState(
            text = repo.loadSystemPrompt(),
            isCustom = repo.isSystemPromptCustom()
        )
    }

    /** Save a new standing instruction. Blank resets to the shipped default rather than clearing it. */
    fun saveSystemPrompt(text: String) = viewModelScope.launch {
        repo.saveSystemPrompt(text)
        refreshSystemPrompt().join()
    }

    fun resetSystemPrompt() = viewModelScope.launch {
        repo.resetSystemPrompt()
        refreshSystemPrompt().join()
    }

    init {
        viewModelScope.launch { _identity.value = repo.loadIdentity() }
        refreshProfiles()
        refreshSystemPrompt()
    }

    fun refreshModel() { _modelState.value = ModelUiState.Idle(repo.modelInfo()) }

    /** Import the picked GGUF into app storage, streaming progress into [modelState]. */
    fun importModel(uri: Uri) = viewModelScope.launch {
        _modelState.value = ModelUiState.Importing(0L, -1L)
        var lastPosted = 0L
        runCatching {
            repo.importModel(uri) { copied, total ->
                // Throttle: ~16 MB steps (or completion) — a multi-GB copy would post thousands of updates.
                if (copied - lastPosted >= (16L shl 20) || (total in 1..copied)) {
                    lastPosted = copied
                    _modelState.value = ModelUiState.Importing(copied, total)
                }
            }
        }.onSuccess { refreshModel() }
            .onFailure { _modelState.value = ModelUiState.Error(it.message ?: "Import failed") }
    }

    fun deleteModel() = viewModelScope.launch {
        repo.deleteModel()
        refreshModel()
    }

    fun refreshEmbeddingModel() { _embeddingModelState.value = ModelUiState.Idle(repo.embeddingModelInfo()) }

    /** Import the picked embedding GGUF into app storage, streaming progress into [embeddingModelState]. */
    fun importEmbeddingModel(uri: Uri) = viewModelScope.launch {
        _embeddingModelState.value = ModelUiState.Importing(0L, -1L)
        var lastPosted = 0L
        runCatching {
            repo.importEmbeddingModel(uri) { copied, total ->
                if (copied - lastPosted >= (16L shl 20) || (total in 1..copied)) {
                    lastPosted = copied
                    _embeddingModelState.value = ModelUiState.Importing(copied, total)
                }
            }
        }.onSuccess { refreshEmbeddingModel() }
            .onFailure { _embeddingModelState.value = ModelUiState.Error(it.message ?: "Import failed") }
    }

    fun deleteEmbeddingModel() = viewModelScope.launch {
        repo.deleteEmbeddingModel()
        refreshEmbeddingModel()
    }

    fun refreshProfiles() = viewModelScope.launch { _profiles.value = repo.listProfiles() }

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
                // The model may have written to a profile via @remember — reflect that.
                refreshProfiles()
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

    // --- profile actions ---

    fun appendToProfile(key: String, text: String) = viewModelScope.launch {
        repo.appendToProfile(key, text)
        refreshProfiles()
    }

    fun createProfile(name: String, kind: ProfileKind, summary: String) = viewModelScope.launch {
        repo.createProfile(name, kind, summary)
        refreshProfiles()
    }

    fun deleteProfile(key: String) = viewModelScope.launch {
        repo.deleteProfile(key)
        refreshProfiles()
    }

    class Factory(private val repo: AdvisorRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AdvisorViewModel(repo) as T
    }
}
