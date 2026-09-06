package com.citation.core.speech

/**
 * A downloadable neural voice: two files, a checksum, and enough metadata to choose between them.
 *
 * Citation ships no voice, for the same reason it ships no font — the good ones are somebody else's
 * to distribute, and bundling sixty megabytes of one voice into an app that mostly reads text would
 * be a poor trade for the reader who never listens. What it ships instead is the ability to fetch
 * one, verify it, and speak with it offline forever after. That is the whole shape of the on-device
 * neural track: the download is the only thing that ever touches the network.
 *
 * A voice is a **pair**: the ONNX weights, and the token table beside them mapping the model's
 * phoneme inventory to the ids it was trained on. Both are required — weights without their tokens
 * cannot be loaded and do not count as installed, however many megabytes of them are on disk. The
 * pronunciation data that turns text into those phonemes is shared by every voice and ships with the
 * app rather than being downloaded per voice.
 *
 * @property id the voice's canonical name, e.g. `en_US-lessac-medium`. Also its filename stem and
 *   the value stored in [SpeechSettings.voiceId].
 * @property name what to call it in a list — the speaker, not the file.
 * @property language BCP-47, e.g. `en-US`.
 * @property quality what it costs to run and to store; see [VoiceQuality].
 * @property sampleRate the rate the model synthesizes at, in Hz.
 * @property sizeBytes the weights' size, for a download prompt that tells the truth before it starts.
 * @property modelUrl where the `.onnx` comes from.
 * @property tokensUrl where its token table comes from.
 * @property sha256 the weights' SHA-256 as the publisher states it, or `null` when unknown.
 *   Integrity, not security: it catches the half-finished download, which is the failure that
 *   actually happens, and the runtime handed half a model does not fail politely.
 * @property tokensSha256 the same for the token table.
 * @property speakers how many voices the model contains; more than one means [speaker] selects.
 * @property notes anything the reader should know before spending the megabytes.
 */
data class VoiceModel(
    val id: String,
    val name: String,
    val language: String,
    val quality: VoiceQuality,
    val sampleRate: Int,
    val sizeBytes: Long,
    val modelUrl: String,
    val tokensUrl: String,
    val sha256: String? = null,
    val tokensSha256: String? = null,
    val speakers: Int = 1,
    val speaker: Int = 0,
    val notes: String? = null
) {

    /** The weights' filename on disk. */
    val modelFileName: String get() = "$id.onnx"

    /** The token table's filename on disk, beside the weights. */
    val tokensFileName: String get() = "$id.tokens.txt"

    /** "English (US) · Medium · 60 MB" — one line for a voice picker. */
    val summary: String get() = "$language · ${quality.label} · ${megabytes(sizeBytes)}"

    companion object {
        /** Whole megabytes, rounded up, so a size never reads as smaller than the download is. */
        fun megabytes(bytes: Long): String = "${(bytes + 1_048_575) / 1_048_576} MB"
    }
}

/**
 * What a voice costs to run, which on a phone is the choice that matters.
 *
 * Quality here is the publisher's own tier, and it tracks both file size and synthesis time: a high
 * voice on an older phone can take longer to speak a sentence than the sentence takes to say, which
 * is the one failure mode that makes read-along unusable. Offering the tier honestly lets a reader
 * trade down rather than conclude the feature is broken.
 */
enum class VoiceQuality(val label: String) {
    /** Smallest and fastest; noticeably synthetic. Good on old hardware. */
    LOW("Low"),

    /** The default trade: natural enough for hours of listening, comfortable on any recent phone. */
    MEDIUM("Medium"),

    /** Best sounding, largest, slowest. Worth it on a current phone. */
    HIGH("High")
}

/**
 * A voice as it exists on this device: the model it came from, and where its two files are.
 *
 * Paths are opaque strings rather than files, because `:core` holds no notion of a filesystem — the
 * Android side supplies them and this side reasons about whether the pair is complete.
 */
data class InstalledVoice(
    val model: VoiceModel,
    val modelPath: String,
    val tokensPath: String
)

/**
 * The rules for what counts as installed, and what to do when a voice is asked for.
 *
 * Small enough to look trivial, and worth having anyway: "which voice do we speak with" is asked
 * from three different places (starting playback, showing the picker, deciding whether the neural
 * engine is available at all), and answering it three times is how they end up disagreeing.
 */
object VoiceSelection {

    /**
     * The voice to speak with, given what is installed and what the reader asked for.
     *
     * Falls back rather than failing: a [SpeechSettings.voiceId] naming a voice that has since been
     * deleted resolves to the best remaining one instead of leaving the reader with silence and no
     * explanation. Returns `null` only when nothing is installed, which the caller answers by
     * falling back to the platform engine or by offering the download.
     */
    fun resolve(installed: List<InstalledVoice>, settings: SpeechSettings): InstalledVoice? {
        if (settings.engine == EnginePreference.SYSTEM) return null
        if (installed.isEmpty()) return null
        settings.voiceId?.let { wanted ->
            installed.firstOrNull { it.model.id == wanted }?.let { return it }
        }
        return best(installed)
    }

    /**
     * The best of what is installed: highest quality first, then the largest model, then by name so
     * the answer is stable rather than dependent on the order the disk listed files in.
     */
    fun best(installed: List<InstalledVoice>): InstalledVoice? =
        installed.maxWithOrNull(
            compareBy<InstalledVoice> { it.model.quality.ordinal }
                .thenBy { it.model.sizeBytes }
                .thenByDescending { it.model.id }
        )

    /** Voices for a book in [language], most appropriate first; never hides the rest. */
    fun forLanguage(installed: List<InstalledVoice>, language: String?): List<InstalledVoice> {
        if (language.isNullOrBlank()) return installed
        val tag = language.lowercase().replace('_', '-')
        val primary = tag.substringBefore('-')
        return installed.sortedWith(
            compareByDescending<InstalledVoice> { it.model.language.lowercase().replace('_', '-') == tag }
                .thenByDescending { it.model.language.lowercase().substringBefore('-') == primary }
                .thenByDescending { it.model.quality.ordinal }
        )
    }
}
