package com.project.app.ui.docs

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.project.app.data.model.DocContent
import com.project.app.data.prefs.ProjectPrefs
import com.project.app.data.repository.ProjectRepository
import com.project.app.logic.BlockType
import com.project.app.logic.DocBlock
import com.project.app.logic.DocBlocks
import com.project.app.logic.RevisionReason
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The editor's state: the document open, the blocks in it, and the saves in flight.
 */

class DocEditorViewModel(
    private val repo: ProjectRepository,
    private val prefs: ProjectPrefs,
    private val docId: String
) : ViewModel() {

    val content: StateFlow<DocContent?> =
        repo.docs.observeDocContent(docId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Reading or editing. Remembered across documents and launches, because it is a mode you are
     * *in* — somebody re-reading a draft wants every document they open to be readable, not to
     * reach for the toggle forty times.
     */
    private val _readingMode = MutableStateFlow(prefs.docReadingMode)
    val readingMode: StateFlow<Boolean> = _readingMode.asStateFlow()

    fun toggleReadingMode() {
        val next = !_readingMode.value
        _readingMode.value = next
        prefs.docReadingMode = next
    }

    /**
     * Whether tables read as cards rather than as a grid.
     *
     * Remembered like reading mode, and for the same reason: on a small screen a wide table is only
     * readable one way, and which way that is depends on the phone and the person, not on the
     * document. Asking again on every table would be asking the same question forty times.
     */
    private val _tableCards = MutableStateFlow(prefs.docTableCards)
    val tableCards: StateFlow<Boolean> = _tableCards.asStateFlow()

    fun toggleTableCards() {
        val next = !_tableCards.value
        _tableCards.value = next
        prefs.docTableCards = next
    }

    /**
     * How many versions this document has, so the menu can offer the history only when there is
     * one — an empty history screen is a worse answer than no way to reach it.
     */
    val revisionCount: StateFlow<Int> =
        repo.versions.observeRevisionCount(docId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /**
     * Keep the document as it stands, by hand.
     *
     * The automatic versions cover the edits the app knows are destructive. This covers the ones it
     * cannot know about — the rewrite you are about to do yourself, a paragraph at a time.
     */
    fun saveRevision(onDone: (Boolean) -> Unit) = viewModelScope.launch {
        onDone(repo.versions.saveRevision(docId, RevisionReason.MANUAL) != null)
    }

    fun updateBlock(block: DocBlock) = viewModelScope.launch { repo.docs.updateBlock(docId, block) }

    /** Change a block's type, converting its text where the two shapes disagree. */
    fun retypeBlock(block: DocBlock, type: BlockType) =
        viewModelScope.launch { repo.docs.updateBlock(docId, DocBlocks.retype(block, type)) }

    fun addBlock(type: BlockType, after: String?) =
        viewModelScope.launch { repo.docs.addBlock(docId, type, after) }

    fun deleteBlock(blockId: String) = viewModelScope.launch { repo.docs.deleteBlock(docId, blockId) }

    fun moveBlock(blockId: String, delta: Int) =
        viewModelScope.launch { repo.docs.moveBlock(docId, blockId, delta) }

    fun rename(title: String) = viewModelScope.launch {
        val doc = content.value?.doc ?: return@launch
        repo.docs.updateDoc(doc.copy(title = title))
    }

    /** Rebuild the tables in this document that arrived as flattened paragraphs. */
    fun repairTables(onDone: (Int) -> Unit) =
        viewModelScope.launch { onDone(repo.docs.repairTables(docId)) }

    fun replaceFromMarkdown(markdown: String) =
        viewModelScope.launch { repo.docs.replaceDocFromMarkdown(docId, markdown) }

    /** The document as Markdown, handed to whoever asked — the clipboard or the share sheet. */
    fun exportMarkdown(onReady: (String) -> Unit) =
        viewModelScope.launch { onReady(repo.docs.docAsMarkdown(docId)) }

    class Factory(
        private val repo: ProjectRepository,
        private val prefs: ProjectPrefs,
        private val docId: String
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            DocEditorViewModel(repo, prefs, docId) as T
    }
}
