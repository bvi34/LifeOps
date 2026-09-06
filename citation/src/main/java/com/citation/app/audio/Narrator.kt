package com.citation.app.audio

import android.content.Context
import com.citation.app.CitationApplication
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import com.citation.core.model.Book
import com.citation.core.reader.ReadingProgress
import com.citation.core.speech.EnginePreference
import com.citation.core.speech.Narration
import com.citation.core.speech.NarrationState
import com.citation.core.speech.NarrationStatus
import com.citation.core.speech.NarrationStep
import com.citation.core.speech.SkipGranularity
import com.citation.core.speech.SleepMode
import com.citation.core.speech.SleepTimer
import com.citation.core.speech.SleepTimerState
import com.citation.core.speech.SpeechPlan
import com.citation.core.speech.SpeechPlanner
import com.citation.core.speech.SpeechSettings
import com.citation.core.speech.Utterance
import com.citation.core.speech.UtteranceKind
import com.citation.core.speech.VoiceModel
import com.citation.core.speech.VoiceSelection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The narrator: one book, one voice, one position, held for as long as the reader is listening.
 *
 * It is a process-wide singleton for the same reason [com.citation.app.CitationApplication] is —
 * listening outlives the screen. The activity can be rotated, backgrounded or destroyed while the
 * book keeps reading in the reader's ear, and when the reader comes back the player they see is the
 * one that has been running. The foreground service ([NarrationService]) keeps the process alive and
 * owns the lock-screen controls; this class owns the actual decisions, and neither knows anything
 * about the other's UI.
 *
 * What is *not* here is deliberate. Nothing in this file decides what to say, where a sentence ends,
 * what happens at a chapter boundary, or when the sleep timer fires — those are in `:core`, tested
 * without a device. This is the part that can only exist on Android: audio focus, an engine, a
 * coroutine that survives the screen, and a position written back to the reader.
 */
class Narrator private constructor(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val voices = VoiceStore(context)
    private val settingsStore = SpeechSettingsStore(context)

    private val _state = MutableStateFlow(NarrationState())

    /** What the reader is hearing, for the player, the notification and the read-along highlight. */
    val state: StateFlow<NarrationState> = _state.asStateFlow()

    private val _settings = MutableStateFlow(settingsStore.load())

    /** How the book is being spoken — observed by the player's own controls. */
    val speechSettings: StateFlow<SpeechSettings> = _settings.asStateFlow()

    private var settings: SpeechSettings
        get() = _settings.value
        set(value) { _settings.value = value }
    private var sleep = SleepTimerState()
    private var book: Book? = null
    private var plan: SpeechPlan = SpeechPlan.empty(0)
    private var engine: SpeechEngine? = null
    private var job: Job? = null

    init {
        // The player opens showing the speed and voice the reader last chose, before anything plays.
        _state.value = _state.value.copy(rate = settings.rate, voiceId = settings.voiceId)
        // A download interrupted at forty megabytes is dead weight nothing will ever finish; the
        // store keeps those on `.part` files precisely so they can be swept without risk.
        voices.clearPartials()
    }

    /** Where to resume from after a pause, or after another app takes the audio and gives it back. */
    private var resumeIndex = 0
    private var pausedByFocusLoss = false

    /** The chapter whose title has already been read out, so resuming does not say it again. */
    private var announcedChapter = -1
    private var focusRequest: AudioFocusRequest? = null

    /**
     * Where the voice has got to, reported continuously so the reader's own position follows it.
     *
     * The narrator does not write to the database itself: position, pace and progress are the
     * ViewModel's to own, and one book must not end up with two things saving where it is. Note
     * that a listener should decline to bank this toward the reading pace — see
     * [SpeechSettings.bankListeningTowardPace] for why.
     */
    var onPosition: ((bookKey: String?, chapterOrdinal: Int, charOffset: Int) -> Unit)? = null

    /**
     * Saving where the voice got to is the narrator's own job, not the caller's.
     *
     * It used to ride out on [onPosition] and be written by the ViewModel, and that was wrong in
     * exactly the case the feature exists for: an activity backgrounded for an hour can be destroyed
     * while its foreground service keeps reading, and a `viewModelScope` write is cancelled with it.
     * The position would then stop being recorded silently, and stay stopped even after the reader
     * came back — the one thing a listener would notice, in the one situation they would notice it.
     * So it is written here, on a scope that lives as long as the process the voice does.
     */
    private var lastSavedAt = 0L
    private var lastSavedChapter = -1

    /**
     * How far through *its own* book a place is.
     *
     * Offered because the narrator keeps reading a book the reader may have since closed and moved
     * away from, so a caller saving progress cannot measure it against whatever is on screen.
     */
    fun progressFraction(chapterOrdinal: Int, charOffset: Int): Float? =
        book?.let { ReadingProgress.at(it, chapterOrdinal, charOffset).fraction }

    /** The book the voice has open, which need not be the one on screen. */
    val openBookKey: String? get() = book?.key?.toString()

    /** The open book's title, for the lock screen and the notification. */
    val bookTitle: String? get() = book?.metadata?.title

    /** Its author, likewise. */
    val bookAuthor: String? get() = book?.metadata?.author

    /** A chapter's own title, when the source gave it one worth showing. */
    fun chapterTitle(ordinal: Int): String? = book?.chapterAt(ordinal)?.title?.takeIf { it.isNotBlank() }

    /** Replace the speech settings. A change of voice or engine takes effect at the next utterance. */
    fun configure(settings: SpeechSettings) {
        val engineChanged = settings.engine != this.settings.engine || settings.voiceId != this.settings.voiceId
        val contentChanged = settings.content != this.settings.content
        this.settings = settings
        settingsStore.save(settings)
        if (engineChanged) {
            engine?.release()
            engine = null
        }
        if (contentChanged) replan(keepPosition = true)
        _state.value = _state.value.copy(rate = settings.rate)
    }

    /** Start reading [book] aloud from a reader's position. */
    fun play(book: Book, chapterOrdinal: Int, charOffset: Int) {
        this.book = book
        loadChapter(chapterOrdinal)
        sleep = SleepTimer.arm(settings.sleepMode)
        val step = Narration.start(plan, charOffset)
        if (step !is NarrationStep.Speak) {
            stop()
            return
        }
        // The service is what keeps the process alive with the screen off, and what puts the
        // controls on the lock screen. Started here rather than by the caller so every entry point
        // — the reader, a headset button, a resumed session — gets the same behaviour.
        NarrationService.start(context)
        start(step.index)
    }

    /** Resume where the voice left off. */
    fun resume() {
        if (book == null) return
        NarrationService.start(context)
        start(resumeIndex)
    }

    /** Stop speaking, keep the book and the position. */
    fun pause() {
        flush()
        job?.cancel()
        job = null
        engine?.stop()
        abandonFocus()
        _state.value = _state.value.copy(status = NarrationStatus.PAUSED, wordRange = null)
    }

    /** Stop, release the engine, and let the service go. */
    fun stop() {
        // Before the book is forgotten, which is what makes it the last chance to record the place.
        flush()
        job?.cancel()
        job = null
        engine?.release()
        engine = null
        abandonFocus()
        book = null
        plan = SpeechPlan.empty(0)
        resumeIndex = 0
        pausedByFocusLoss = false
        announcedChapter = -1
        lastSavedChapter = -1
        sleep = SleepTimer.cancel()
        _state.value = NarrationState()
    }

    /** Play or pause, for a headset button and a notification that have only one of them. */
    fun toggle() {
        if (_state.value.status == NarrationStatus.SPEAKING) pause() else resume()
    }

    /** Move by a sentence or a paragraph, in either direction, crossing chapters if need be. */
    fun skip(granularity: SkipGranularity, forward: Boolean) {
        val current = book ?: return
        val step = Narration.skip(plan, resumeIndex, granularity, forward, current.chapters.size)
        act(step)
    }

    /** Arm, change or cancel the sleep timer. */
    fun setSleep(mode: SleepMode) {
        sleep = SleepTimer.arm(mode)
        settings = settings.copy(sleepMode = mode)
        settingsStore.save(settings)
        publishSleep()
    }

    /** The voices installed on this device, for the picker. */
    fun installedVoices() = voices.installed()

    /**
     * Download and install a voice, reporting `0f`..`1f` as it arrives.
     *
     * Suspends for as long as sixty megabytes takes, so it belongs to a screen the reader is looking
     * at rather than to playback. Installing the voice already selected drops the loaded engine so
     * the next sentence is spoken by the new file rather than by whatever was in memory.
     */
    suspend fun installVoice(model: VoiceModel, onProgress: (Float) -> Unit = {}): VoiceStore.InstallResult {
        val result = VoiceDownloader(voices).install(model, onProgress)
        if (result is VoiceStore.InstallResult.Installed && model.id == settings.voiceId) {
            engine?.release()
            engine = null
        }
        return result
    }

    /** Delete a downloaded voice and its files. Drops the engine when it is the one loaded. */
    fun deleteVoice(model: VoiceModel): Boolean {
        if (settings.voiceId == model.id || _state.value.voiceId == model.id) {
            engine?.release()
            engine = null
        }
        return voices.delete(model)
    }

    /** Bytes the downloaded voices occupy, for the storage screen. */
    fun voiceStorageBytes(): Long = voices.totalBytes()

    /** Whether this build carries a neural runtime at all; false means the platform voice only. */
    val neuralAvailable: Boolean get() = NeuralSynthesizers.isAvailable

    /** Push an armed timer out — the "still awake" gesture. */
    fun extendSleep(millis: Long) {
        sleep = SleepTimer.extend(sleep, millis)
        publishSleep()
    }

    // --- The loop --------------------------------------------------------------------------------

    private fun start(index: Int) {
        val current = book ?: return
        if (index !in 0 until plan.size) {
            act(Narration.after(plan, plan.size - 1, current.chapters.size, settings, sleep))
            return
        }
        job?.cancel()
        pausedByFocusLoss = false
        lastFailure = null
        if (!requestFocus()) {
            _state.value = _state.value.copy(status = NarrationStatus.PAUSED)
            return
        }
        job = scope.launch {
            var at = index
            val speaker = engineFor() ?: run {
                fail("No voice is available. Install one, or enable your device's speech engine.")
                return@launch
            }
            while (isActive) {
                val utterance = plan[at] ?: break
                // Resuming restarts the sentence being spoken rather than resuming inside it: no
                // engine can be resumed mid-utterance, and a sentence heard twice from its start is
                // what a person listening actually wants after an interruption.
                resumeIndex = at

                // Say which chapter this is, once, when one actually begins. A listener has no page
                // to glance at, so without it chapters run together into an undifferentiated hour —
                // and equally, someone who pressed play half way down a page has not started a
                // chapter and should not be told they have.
                announcement(at)?.let { announcement ->
                    announcedChapter = plan.chapterOrdinal
                    publishSpeaking(at, null)
                    val said = speaker.speak(announcement, settings.rate, settings.pitch,
                        SleepTimer.volumeScale(sleep)) {}
                    if (said is SpeechResult.Stopped) return@launch
                }

                publishSpeaking(at, utterance.range.takeIf { !utterance.isMarker })
                onPosition?.invoke(openBookKey, plan.chapterOrdinal, utterance.start)
                persist(plan.chapterOrdinal, utterance.start)

                val result = speaker.speak(
                    utterance = utterance,
                    rate = settings.rate,
                    pitch = settings.pitch,
                    volume = SleepTimer.volumeScale(sleep),
                    onWord = { range -> publishWord(range) }
                )
                when (result) {
                    is SpeechResult.Stopped -> return@launch
                    is SpeechResult.Failed -> {
                        fail(result.reason)
                        return@launch
                    }
                    is SpeechResult.Spoken -> Unit
                }

                // The written pauses are a *reading* pace, so they shorten with the speaking rate:
                // a beat between paragraphs at 2x that still ran a third of a second would read as
                // the app hesitating rather than as punctuation.
                val rest = (utterance.pauseAfterMillis / settings.rate.coerceAtLeast(0.1f)).toLong()
                if (rest > 0) delay(rest)
                publishSleep()

                when (val step = Narration.after(plan, at, book?.chapters?.size ?: 0, settings, sleep)) {
                    is NarrationStep.Speak -> at = step.index
                    is NarrationStep.ChangeChapter -> {
                        loadChapter(step.ordinal)
                        if (plan.isEmpty) {
                            // A chapter of nothing but a plate or a skipped table: step over it
                            // rather than stopping the book on it.
                            at = 0
                            if (!advancePastEmptyChapter(step.ordinal)) return@launch
                        } else {
                            at = if (step.fromEnd) plan.size - 1 else 0
                        }
                    }
                    is NarrationStep.Stop -> {
                        stopAfterSpeaking()
                        return@launch
                    }
                }
            }
        }
    }

    /**
     * The chapter announcement to speak before unit [index], if any.
     *
     * Only at the top of a chapter, only once per chapter, and never when the chapter's own first
     * heading already says the same words — which most EPUBs' do, and hearing the title twice is
     * worse than not hearing it at all. Zero-width, so it highlights nothing and moves no position.
     */
    private fun announcement(index: Int): Utterance? {
        if (!settings.announceChapterTitle || index != 0) return null
        if (announcedChapter == plan.chapterOrdinal) return null
        val title = book?.chapterAt(plan.chapterOrdinal)?.title?.trim().orEmpty()
        if (title.isBlank()) return null
        val first = plan.utterances.firstOrNull()
        if (first != null && first.kind == UtteranceKind.HEADING &&
            first.spoken.trim().equals(title, ignoreCase = true)
        ) {
            // The chapter says its own name; let it.
            announcedChapter = plan.chapterOrdinal
            return null
        }
        val at = first?.start ?: 0
        return Utterance(
            start = at,
            end = at,
            spoken = title,
            kind = UtteranceKind.HEADING,
            pauseAfterMillis = settings.content.pauses.afterHeading,
            runs = emptyList()
        )
    }

    /** Walk forward over chapters that plan to nothing; false when the book runs out. */
    private fun advancePastEmptyChapter(from: Int): Boolean {
        val chapters = book?.chapters?.size ?: return false
        var ordinal = from
        while (plan.isEmpty) {
            ordinal += 1
            if (ordinal >= chapters) {
                stopAfterSpeaking()
                return false
            }
            loadChapter(ordinal)
        }
        return true
    }

    /**
     * Record where the voice is, in the book the voice has open.
     *
     * In memory every sentence — that is [onPosition]'s job — but on disk far less often: the voice
     * moves every few seconds and may do so for hours in a pocket, and a write per sentence would be
     * a write per sentence for the whole of it. Being a sentence stale costs a listener nothing (the
     * worst case on resume is hearing one line twice), while a crossed chapter is written at once,
     * because that is the jump somebody would notice losing.
     */
    private fun persist(chapterOrdinal: Int, charOffset: Int, force: Boolean = false) {
        val key = openBookKey ?: return
        val now = System.currentTimeMillis()
        val crossedChapter = chapterOrdinal != lastSavedChapter
        if (!force && !crossedChapter && now - lastSavedAt < SAVE_INTERVAL_MILLIS) return
        lastSavedAt = now
        lastSavedChapter = chapterOrdinal
        val fraction = progressFraction(chapterOrdinal, charOffset)
        scope.launch {
            // Degrades rather than throws when the runtime is not installed — a background entry
            // point must never be the thing that brings the app down.
            val repository = CitationApplication.getOrNull()?.repository?.await() ?: return@launch
            runCatching { repository.saveListeningPosition(key, chapterOrdinal, charOffset, fraction) }
        }
    }

    /** Write the current place regardless of the throttle. A no-op before anything has been said. */
    private fun flush() {
        val range = _state.value.utteranceRange ?: return
        persist(_state.value.chapterOrdinal, range.first, force = true)
    }

    private fun act(step: NarrationStep) {
        when (step) {
            is NarrationStep.Speak -> start(step.index)
            is NarrationStep.ChangeChapter -> {
                loadChapter(step.ordinal)
                start(if (step.fromEnd) (plan.size - 1).coerceAtLeast(0) else 0)
            }
            is NarrationStep.Stop -> stopAfterSpeaking()
        }
    }

    private fun loadChapter(ordinal: Int) {
        val chapter = book?.chapterAt(ordinal)
        plan = if (chapter == null) SpeechPlan.empty(ordinal) else SpeechPlanner.plan(chapter, settings.content)
        resumeIndex = 0
        _state.value = _state.value.copy(chapterOrdinal = ordinal)
    }

    /** Re-plan the open chapter after a content setting changed, keeping the voice roughly in place. */
    private fun replan(keepPosition: Boolean) {
        val offset = if (keepPosition) plan[resumeIndex]?.start else null
        val ordinal = plan.chapterOrdinal
        loadChapter(ordinal)
        if (offset != null) resumeIndex = plan.indexAt(offset)
    }

    /**
     * The engine to speak with: the reader's preference, then what is actually installed.
     *
     * A preference for a neural voice that is not installed falls through to the platform engine
     * unless the reader asked for neural only — someone who has chosen a good voice would rather be
     * told it is missing than be read to by the phone.
     */
    private suspend fun engineFor(): SpeechEngine? {
        engine?.let { return it }
        _state.value = _state.value.copy(status = NarrationStatus.PREPARING)

        val installed = voices.installed()
        val selected = VoiceSelection.resolve(installed, settings)
        if (selected != null) {
            val synthesizer = NeuralSynthesizers.create()
            if (synthesizer != null) {
                val neural = NeuralSpeechEngine(selected, synthesizer)
                if (neural.prepare()) {
                    engine = neural
                    _state.value = _state.value.copy(voiceId = selected.model.id)
                    return neural
                }
                neural.release()
            }
        }
        if (settings.engine == EnginePreference.NEURAL_ONLY) return null

        val system = SystemSpeechEngine(context, book?.metadata?.language)
        if (!system.prepare()) {
            system.release()
            return null
        }
        engine = system
        _state.value = _state.value.copy(voiceId = SystemSpeechEngine.ID)
        return system
    }

    // --- Audio focus -----------------------------------------------------------------------------

    /**
     * Ask for the audio, and behave when it is taken away.
     *
     * A book that keeps talking over a phone call, or that never comes back after one, is the fastest
     * way to lose a listener. A permanent loss stops; a transient one pauses and resumes itself; a
     * duckable one — a navigation prompt — pauses too, because half-heard prose is worse than a
     * two-second gap in a way that half-heard music is not.
     */
    private fun requestFocus(): Boolean {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener(focusListener)
            .setWillPauseWhenDucked(true)
            .build()
        focusRequest = request
        return audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> stop()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (_state.value.status == NarrationStatus.SPEAKING) {
                    pause()
                    pausedByFocusLoss = true
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> if (pausedByFocusLoss) {
                pausedByFocusLoss = false
                resume()
            }
        }
    }

    // --- State -----------------------------------------------------------------------------------

    private fun publishSpeaking(index: Int, range: IntRange?) {
        _state.value = _state.value.copy(
            status = NarrationStatus.SPEAKING,
            bookKey = book?.key?.toString(),
            chapterOrdinal = plan.chapterOrdinal,
            utteranceIndex = index,
            utteranceRange = range,
            wordRange = null,
            rate = settings.rate,
            sleepRemainingMillis = SleepTimer.remaining(sleep)
        )
    }

    private fun publishWord(range: IntRange) {
        val current = _state.value
        if (current.status == NarrationStatus.SPEAKING) _state.value = current.copy(wordRange = range)
    }

    private fun publishSleep() {
        _state.value = _state.value.copy(sleepRemainingMillis = SleepTimer.remaining(sleep))
    }

    /** The book ended, or the sleep timer did: keep the position, give everything else back. */
    private fun stopAfterSpeaking() {
        engine?.release()
        engine = null
        abandonFocus()
        _state.value = _state.value.copy(
            status = NarrationStatus.PAUSED,
            wordRange = null,
            sleepRemainingMillis = null
        )
        sleep = SleepTimer.cancel()
    }

    private fun fail(reason: String) {
        engine?.release()
        engine = null
        abandonFocus()
        _state.value = _state.value.copy(status = NarrationStatus.FAILED, wordRange = null)
        lastFailure = reason
    }

    /** Why the voice stopped, for the player to show. Cleared when playback starts again. */
    @Volatile
    var lastFailure: String? = null
        private set

    companion object {
        /** How often the listening position reaches the database while the voice runs. */
        private const val SAVE_INTERVAL_MILLIS = 10_000L

        @Volatile
        private var instance: Narrator? = null

        /** The process's narrator, built against the application context. */
        fun get(context: Context): Narrator =
            instance ?: synchronized(this) {
                instance ?: Narrator(context.applicationContext).also { instance = it }
            }

        /** The narrator, if one has ever been built — for entry points that must not create it. */
        fun peek(): Narrator? = instance
    }
}
