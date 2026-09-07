package com.citation.app.audio

import android.content.Context
import com.citation.core.speech.InstalledVoice
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsCallback
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File

/**
 * The neural voice, running on this device: sherpa-onnx driving a Piper VITS model.
 *
 * This is the implementation behind [NeuralSynthesizer], and the only class in Citation that knows
 * a runtime exists. Everything above it — the planner, the player, the sleep timer, the controls —
 * was written against the interface and does not change if this is swapped for another engine, or
 * removed entirely: with nothing registered, the reader falls back to the platform voice and the
 * app is exactly what it was before.
 *
 * Three files make a voice speak, and they come from three different places for good reasons. The
 * **model** (60–120 MB) is downloaded, because it is far too large to ship to readers who never
 * listen. The **tokens** beside it map the model's phoneme inventory to the ids it was trained on,
 * and are downloaded with it because they are per-voice. The **pronunciation data** is in the APK
 * (see [EspeakData]), because it is shared by every voice and small enough to always have — which
 * is what makes a freshly downloaded voice work on a plane.
 *
 * The model is loaded by path, which is the reason a voice the reader added needs nothing from this
 * class: a file in the voice store is a file in the voice store, whether the catalogue named it or a
 * person did. The one thing it takes from the voice's definition is which speaker to use, for the
 * multi-speaker models the catalogue does not carry but readers do.
 *
 * Synthesis is chunked through sherpa's own callback, so the player can start speaking a sentence
 * before the whole of it has been generated, and can stop in the middle of one. That is the
 * difference between a reader that responds to the pause button and one that finishes the sentence
 * first.
 */
class SherpaNeuralSynthesizer(private val context: Context) : NeuralSynthesizer {

    private var tts: OfflineTts? = null
    private var loadedVoiceId: String? = null

    /**
     * Which voice inside the model to speak with.
     *
     * Every catalogue voice is single-speaker, so this is 0 for all of them. It exists for the
     * models a reader brings themselves: a good part of what is published for this runtime is
     * multi-speaker, one file holding hundreds of narrators, and picking between them is a number
     * passed to the generator rather than a different download. It is read at load rather than per
     * sentence because it is a property of the voice, not of the utterance.
     */
    private var loadedSpeaker = 0

    override val sampleRate: Int get() = tts?.sampleRate ?: 0

    override val isLoaded: Boolean get() = tts != null

    override fun load(voice: InstalledVoice): Boolean {
        if (loadedVoiceId == voice.model.id && tts != null) return true
        release()
        val data = EspeakData.directory(context) ?: return false
        if (!File(voice.modelPath).isFile || !File(voice.tokensPath).isFile) return false

        return runCatching {
            val vits = OfflineTtsVitsModelConfig.builder()
                .setModel(voice.modelPath)
                .setTokens(voice.tokensPath)
                .setDataDir(data.absolutePath)
                .build()
            val model = OfflineTtsModelConfig.builder()
                .setVits(vits)
                // Two threads is the useful part of the curve on a phone: it roughly halves the time
                // to the first chunk, and more threads mostly spend battery contending.
                .setNumThreads(2)
                .setDebug(false)
                .build()
            tts = OfflineTts(OfflineTtsConfig.builder().setModel(model).build())
            loadedVoiceId = voice.model.id
            loadedSpeaker = voice.model.speaker.coerceAtLeast(0)
            true
        }.getOrElse {
            // A model that will not load — a truncated download, an ABI with no native library, a
            // device out of memory — is answered by falling back, never by failing the book.
            release()
            false
        }
    }

    override fun synthesize(text: String, speed: Float, sink: NeuralSynthesizer.AudioSink): Boolean {
        val engine = tts ?: return false
        var cancelled = false
        return runCatching {
            // sherpa's own contract: 1 keeps generating, 0 stops. That maps straight onto a sink
            // that has been cancelled, so a pause reaches the model rather than only the speaker.
            val callback = OfflineTtsCallback { samples ->
                if (sink.onSamples(samples, samples.size)) {
                    1
                } else {
                    cancelled = true
                    0
                }
            }
            engine.generateWithCallback(text, loadedSpeaker, speed.coerceIn(MIN_SPEED, MAX_SPEED), callback)
            !cancelled
        }.getOrDefault(false)
    }

    override fun release() {
        runCatching { tts?.release() }
        tts = null
        loadedVoiceId = null
        loadedSpeaker = 0
    }

    companion object {

        /**
         * Whether this build actually carries the native runtime.
         *
         * The Java API is always on the classpath — it is a committed jar — but the libraries it
         * calls into are fetched at build time and may legitimately be absent (no network, or the
         * fetch deliberately skipped). Asking the linker is the only honest answer, and it is asked
         * once: a `false` here is what makes the Listen tab say the device's own voice is being used
         * and stop offering downloads that nothing could load.
         *
         * Loading is not cheap — tens of megabytes are mapped — so callers should ask off the main
         * thread, which is why registration happens on the application's background scope.
         */
        fun isRuntimePresent(): Boolean = runtimePresent

        private val runtimePresent: Boolean by lazy {
            runCatching { System.loadLibrary("sherpa-onnx-jni") }.isSuccess
        }

        // The model's own length scale, not resampling — so a faster reading stays in pitch. Outside
        // this range a VITS model stops being intelligible rather than merely sounding hurried.
        private const val MIN_SPEED = 0.5f
        private const val MAX_SPEED = 3f
    }
}
