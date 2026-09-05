package com.citation.app.audio

import com.citation.core.speech.InstalledVoice

/**
 * The one thing an on-device neural voice actually has to do: turn a sentence into samples.
 *
 * Kept as an interface, and kept this small, so the native runtime stays *outside* `:citation`. The
 * ONNX inference libraries that run these voices ship tens of megabytes of native code per ABI and
 * bind to a particular runtime version; a reader module that reads EPUBs should not carry that in
 * its dependency graph, and the choice between runtimes should not be a rewrite. The host module
 * registers an implementation through [NeuralSynthesizers]; everything above this interface — the
 * planner, the player, the notification, the controls — is already written and does not change when
 * one is plugged in.
 *
 * The contract is intentionally blocking and pull-shaped, because that is what every one of these
 * runtimes offers: hand it text, get samples. Emitting them in chunks through the sink lets the
 * player start speaking before a long sentence has finished synthesizing, which is the difference
 * between a responsive reader and one that hesitates before every line.
 */
interface NeuralSynthesizer {

    /** Sample rate of what [synthesize] emits, valid once a voice is loaded. */
    val sampleRate: Int

    /** Whether a voice is loaded and ready. */
    val isLoaded: Boolean

    /**
     * Load [voice] — its `.onnx` weights and the `.onnx.json` beside them, both already verified on
     * disk by [VoiceStore]. Returns `false` for a model this runtime cannot run, which the narrator
     * answers by falling back rather than by failing.
     */
    fun load(voice: InstalledVoice): Boolean

    /**
     * Synthesize [text], handing samples to [sink] as they are produced, and return `true` when the
     * whole utterance was produced.
     *
     * @param speed `1f` is the voice's own pace; the value maps to the model's own length scale
     *   rather than to resampling, so a faster reading stays in pitch.
     * @param sink called on the calling thread; a sink that returns `false` has been cancelled and
     *   synthesis should stop.
     */
    fun synthesize(text: String, speed: Float, sink: AudioSink): Boolean

    /** Unload the model and free what it holds. */
    fun release()

    /** Where synthesized samples go: 16-bit mono PCM at [sampleRate]. */
    fun interface AudioSink {
        /** Returns `false` to cancel synthesis — the reader pressed pause or skipped. */
        fun onSamples(samples: ShortArray, count: Int): Boolean
    }
}

/**
 * The registry the host module plugs a runtime into.
 *
 * A single nullable factory rather than a service loader or a reflective lookup: there is exactly
 * one runtime in a build, it is chosen at compile time by which dependency is present, and a
 * registry that can fail at runtime for a reason nobody can see is worse than one that is obviously
 * empty. With nothing registered, [NeuralSpeechEngine.prepare] reports unavailable, the narrator
 * falls back to the platform voice, and the voice picker says so — the app reads books either way.
 */
object NeuralSynthesizers {

    @Volatile
    private var factory: (() -> NeuralSynthesizer)? = null

    /** Register the runtime this build ships. Called once, from the hosting application. */
    fun register(factory: () -> NeuralSynthesizer) {
        this.factory = factory
    }

    /** Whether this build can run neural voices at all. */
    val isAvailable: Boolean get() = factory != null

    /** A fresh synthesizer, or `null` when no runtime is registered. */
    fun create(): NeuralSynthesizer? = factory?.invoke()
}
