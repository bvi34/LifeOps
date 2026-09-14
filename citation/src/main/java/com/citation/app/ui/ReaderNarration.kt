package com.citation.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.citation.core.speech.CustomVoice
import com.citation.core.speech.NarrationStatus
import com.citation.core.speech.SkipGranularity
import com.citation.core.speech.SleepMode
import com.citation.core.speech.SpeechSettings
import com.citation.core.speech.VoiceCatalog
import com.citation.core.speech.VoiceDraft
import com.citation.core.speech.VoiceModel
import kotlinx.coroutines.launch

/**
 * Reading aloud: the voices installed, the ones defined here, and the speech settings they run at.
 */

internal fun ReaderViewModel.refreshInstalledVoices() {
    _installedVoices.value = narrator?.installedVoices().orEmpty()
    _userVoices.value = narrator?.userVoices().orEmpty()
}

/** The voices offered for the open book, its own language first. */
internal fun ReaderViewModel.voiceCatalog(): List<VoiceModel> =
    VoiceCatalog.suggestedFor(_openBook.value?.metadata?.language)

/** Bytes the downloaded voices occupy, for the storage screen. */
internal fun ReaderViewModel.voiceStorageBytes(): Long = narrator?.voiceStorageBytes() ?: 0L

/** The chapter the voice is in, by its own title where the source gave one. */
internal fun ReaderViewModel.nowPlayingChapter(ordinal: Int): String? = narrator?.chapterTitle(ordinal)

/**
 * Download a voice, reporting progress and reporting the outcome on the status line.
 *
 * Runs in the ViewModel's own scope rather than a composition's, so leaving the picker part-way
 * through a sixty-megabyte download does not cancel it.
 */
internal fun ReaderViewModel.installVoice(model: VoiceModel) {
    val narrator = narrator ?: return
    install(model) { onProgress -> narrator.installVoice(model, onProgress) }
}

/**
 * Add a voice of the reader's own, fetched from a link.
 *
 * Returns whether the draft was accepted, which is what the entry form needs to know: a rejected
 * one leaves the reader looking at what they typed with the reason on the status line, and an
 * accepted one closes and gets on with the download. The voice is recorded before it is fetched,
 * so a failure sixty megabytes in leaves something to press retry on.
 */
internal fun ReaderViewModel.addVoiceFromLink(draft: VoiceDraft): Boolean {
    val narrator = narrator ?: return false
    val model = define(draft) ?: return false
    install(model) { onProgress -> narrator.installVoice(model, onProgress) }
    return true
}

/**
 * Add a voice of the reader's own from two files already on this device.
 *
 * The streams are opened by the screen rather than here: reading a document the reader picked
 * needs a `ContentResolver` and the grant that came with the pick, and this ViewModel
 * deliberately has no `Context`. Sizes are what the picker reported, and are used for two things
 * — the progress bar, and catching the one mistake everybody makes, which is picking the two
 * files the wrong way round.
 */
internal fun ReaderViewModel.addVoiceFromFiles(
    draft: VoiceDraft,
    openTokens: () -> java.io.InputStream?,
    openWeights: () -> java.io.InputStream?,
    modelBytes: Long? = null,
    tokensBytes: Long? = null
): Boolean {
    val narrator = narrator ?: return false
    CustomVoice.checkFiles(modelBytes, tokensBytes)?.let {
        _status.value = it
        return false
    }
    val model = define(draft) ?: return false
    // Only for the progress bar: what it actually weighs is recorded from what was copied.
    val sized = model.copy(sizeBytes = modelBytes ?: 0L)
    install(model) { onProgress -> narrator.importVoice(sized, openTokens, openWeights, onProgress) }
    return true
}

/** Delete a voice — its files, and, when the reader added it, the entry naming it. */
internal fun ReaderViewModel.deleteVoice(model: VoiceModel) {
    if (narrator?.deleteVoice(model) == true) _status.value = "${model.name} removed."
    refreshInstalledVoices()
}

internal fun ReaderViewModel.define(draft: VoiceDraft): VoiceModel? {
    val narrator = narrator ?: return null
    if (_voiceProgress.value != null) {
        _status.value = "One voice is already downloading; let it finish first."
        return null
    }
    return when (val outcome = narrator.defineVoice(draft)) {
        is CustomVoice.Outcome.Defined -> outcome.model.also { refreshInstalledVoices() }
        is CustomVoice.Outcome.Rejected -> {
            _status.value = outcome.reason
            null
        }
    }
}

/**
 * The bookkeeping both ways of installing a voice share: one at a time, progress while it runs,
 * and the outcome on the status line either way.
 */
internal fun ReaderViewModel.install(
    model: VoiceModel,
    fetch: suspend ((Float) -> Unit) -> com.citation.app.audio.VoiceStore.InstallResult
) {
    if (_voiceProgress.value != null) return
    viewModelScope.launch {
        _voiceProgress.value = model.id to 0f
        val result = fetch { _voiceProgress.value = model.id to it }
        _voiceProgress.value = null
        refreshInstalledVoices()
        _status.value = when (result) {
            is com.citation.app.audio.VoiceStore.InstallResult.Installed -> "${model.name} is ready to read aloud."
            is com.citation.app.audio.VoiceStore.InstallResult.Failed -> result.reason
        }
    }
}

/**
 * Start reading the open book aloud from where the reader is.
 *
 * The voice picks up the *live* position rather than the last saved one, so pressing play after
 * scrolling starts at the sentence on screen rather than wherever the last save landed.
 */
internal fun ReaderViewModel.readAloud() {
    val narrator = narrator ?: return
    val book = _openBook.value ?: return
    val (chapter, offset) = _position.value
    narrator.play(book, chapter, offset)
}

/** Pause the voice, keeping the book and the position. */
internal fun ReaderViewModel.pauseAloud() = narrator?.pause() ?: Unit

/** Stop reading aloud and give the engine back. */
internal fun ReaderViewModel.stopAloud() = narrator?.stop() ?: Unit

/**
 * Play or pause, for the reader's single button.
 *
 * Pausing and resuming only means anything for the book the voice actually has open. Pressing
 * play in a *different* book starts that one from the page on screen — otherwise the button
 * under one book would silently resume another, which is the sort of thing nobody debugs.
 */
internal fun ReaderViewModel.toggleAloud() {
    val narrator = narrator ?: return
    val onScreen = _openBook.value?.key?.toString()
    val sameBook = onScreen != null && narrator.openBookKey == onScreen
    if (narration.value.status == NarrationStatus.IDLE || !sameBook) readAloud() else narrator.toggle()
}

/** Move the voice by a sentence or a paragraph. */
internal fun ReaderViewModel.skipAloud(granularity: SkipGranularity, forward: Boolean) {
    narrator?.skip(granularity, forward)
}

/** Change the speaking speed; takes effect at the next sentence. */
internal fun ReaderViewModel.setSpeechRate(rate: Float) = configureSpeech { it.withRate(rate) }

/** Choose a downloaded voice, or `null` to let the narrator pick the best one installed. */
internal fun ReaderViewModel.setVoice(voiceId: String?) = configureSpeech { it.copy(voiceId = voiceId) }

/** Change any speech setting — the engine, the voice, what gets spoken, how it behaves. */
internal fun ReaderViewModel.updateSpeech(transform: (SpeechSettings) -> SpeechSettings) = configureSpeech(transform)

/** Arm, change or cancel the sleep timer. */
internal fun ReaderViewModel.setSleepTimer(mode: SleepMode) = narrator?.setSleep(mode) ?: Unit

/** Push an armed sleep timer out — the "still awake" gesture. */
internal fun ReaderViewModel.extendSleepTimer(millis: Long) = narrator?.extendSleep(millis) ?: Unit

internal fun ReaderViewModel.configureSpeech(transform: (SpeechSettings) -> SpeechSettings) {
    val narrator = narrator ?: return
    narrator.configure(transform(narrator.speechSettings.value))
}

/**
 * The voice moved: follow it with the page.
 *
 * Only *following*. Recording where the voice got to is the narrator's own job, on a scope that
 * outlives this ViewModel — the case that matters is an activity destroyed while a backgrounded
 * book keeps reading, where a write on `viewModelScope` would be cancelled with it and the
 * position would stop being recorded silently.
 *
 * And only while the two are the same book: the voice carries on through a book the reader may
 * have closed and walked away from, and the page must not follow it there.
 *
 * Position, yes; pace, no. [com.citation.core.reader.ReadingPace] answers "how long will this
 * take *you* to read", learnt from how fast this reader reads — and a voice at 1.5x would teach
 * it the speaking rate of an engine instead, dragging every "12 min left" in the app toward a
 * number about nobody. A reader who disagrees can turn
 * [SpeechSettings.bankListeningTowardPace] on and have listening measured like reading.
 */
internal fun ReaderViewModel.onNarratedPosition(bookKey: String?, chapterOrdinal: Int, charOffset: Int) {
    if (bookKey == null || bookKey != _openBook.value?.key?.toString()) return
    if (speechSettings.value.bankListeningTowardPace) {
        onPositionChanged(chapterOrdinal, charOffset)
        onReadingProgress()
    } else {
        _position.value = chapterOrdinal to charOffset
    }
    _chapterOrdinal.value = chapterOrdinal
}
