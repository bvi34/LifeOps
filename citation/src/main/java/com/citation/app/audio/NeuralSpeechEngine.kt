package com.citation.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.citation.core.speech.InstalledVoice
import com.citation.core.speech.Utterance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A downloaded neural voice, synthesized on the device and played straight out of memory.
 *
 * This is the track the on-device option is for: a voice good enough to listen to for hours, that
 * costs nothing per sentence, sends not one word of the book anywhere, and works on a plane. What it
 * costs instead is the download, the memory the model holds while it is loaded, and the CPU to run
 * it — all of which are the reader's to spend knowingly, which is why the voice picker states the
 * size and the quality tier before anything is fetched.
 *
 * There is no word reporting, and that is inherent rather than unfinished: the model produces audio
 * from phonemes with no alignment back to the characters that produced them. So
 * [reportsWordBoundaries] is false and the read-along highlight lands on the sentence — which is
 * what the planner made sentences small enough for.
 *
 * Playback is a plain [AudioTrack] rather than a media player, because there is no file, no
 * container and no seeking involved: samples arrive from the model and go to the speaker. That also
 * keeps the module free of a playback dependency it would otherwise need for exactly one buffer.
 */
class NeuralSpeechEngine(
    private val voice: InstalledVoice,
    private val synthesizer: NeuralSynthesizer
) : SpeechEngine {

    override val id: String = ID

    override val voiceLabel: String get() = voice.model.name

    /** Never: the model synthesizes a whole sentence at once, with no per-word alignment. */
    override val reportsWordBoundaries: Boolean = false

    private var track: AudioTrack? = null
    private val cancelled = AtomicBoolean(false)

    override suspend fun prepare(): Boolean = withContext(Dispatchers.IO) {
        // Loading is seconds of work and tens of megabytes of allocation; the narrator shows
        // PREPARING across it rather than appearing to have ignored the play button.
        runCatching { synthesizer.load(voice) }.getOrDefault(false)
    }

    override suspend fun speak(
        utterance: Utterance,
        rate: Float,
        pitch: Float,
        volume: Float,
        onWord: (IntRange) -> Unit
    ): SpeechResult = withContext(Dispatchers.IO) {
        if (!synthesizer.isLoaded) return@withContext SpeechResult.Failed("The voice is not loaded")
        cancelled.set(false)

        val output = openTrack(volume) ?: return@withContext SpeechResult.Failed("No audio output")
        try {
            output.play()
            val completed = synthesizer.synthesize(utterance.spoken, rate) { samples, count ->
                if (cancelled.get()) return@synthesize false
                // Blocking write: back-pressure from the track is what paces synthesis, so a fast
                // model does not run minutes ahead of the speaker and make pause feel broken.
                var offset = 0
                while (offset < count && !cancelled.get()) {
                    val written = output.write(samples, offset, count - offset, AudioTrack.WRITE_BLOCKING)
                    if (written <= 0) return@synthesize false
                    offset += written
                }
                !cancelled.get()
            }
            when {
                cancelled.get() -> SpeechResult.Stopped
                completed -> {
                    // Let the buffered tail finish rather than cutting the last syllable off.
                    drain(output)
                    if (cancelled.get()) SpeechResult.Stopped else SpeechResult.Spoken
                }
                else -> SpeechResult.Failed("The voice stopped mid-sentence")
            }
        } catch (e: Exception) {
            SpeechResult.Failed(e.message ?: "The voice failed")
        } finally {
            closeTrack()
        }
    }

    override fun stop() {
        cancelled.set(true)
        runCatching { track?.pause() }
        runCatching { track?.flush() }
    }

    override fun release() {
        stop()
        closeTrack()
        runCatching { synthesizer.release() }
    }

    private fun openTrack(volume: Float): AudioTrack? = runCatching {
        val rate = synthesizer.sampleRate.takeIf { it > 0 } ?: voice.model.sampleRate
        val minimum = AudioTrack.getMinBufferSize(
            rate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        ).coerceAtLeast(MINIMUM_BUFFER_BYTES)
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            // Four times the minimum: enough to ride out a scheduling hiccup mid-sentence without
            // adding audible latency to a pause.
            .setBufferSizeInBytes(minimum * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also {
                // The sleep timer's fade rides here, never on the reader's own volume setting.
                it.setVolume(volume.coerceIn(0f, 1f))
                track = it
            }
    }.getOrNull()

    /** Wait for the samples already handed to the track to actually be heard. */
    private fun drain(output: AudioTrack) {
        runCatching {
            var last = -1
            var settled = 0
            // The head stops advancing when the buffer has actually been played out; a few stable
            // polls in a row distinguish that from a momentary stall.
            while (!cancelled.get() && settled < DRAIN_STABLE_POLLS) {
                val position = output.playbackHeadPosition
                if (position == last) settled++ else { settled = 0; last = position }
                Thread.sleep(DRAIN_POLL_MILLIS)
            }
        }
    }

    private fun closeTrack() {
        val output = track ?: return
        track = null
        runCatching { output.stop() }
        runCatching { output.release() }
    }

    companion object {
        const val ID = "neural"

        private const val MINIMUM_BUFFER_BYTES = 8 * 1024
        private const val DRAIN_POLL_MILLIS = 20L
        private const val DRAIN_STABLE_POLLS = 3
    }
}
