package com.citation.core.speech

/**
 * A voice the reader brings themselves — and the handful of rules that keep that from being a bad
 * idea.
 *
 * [VoiceCatalog] is seven voices on purpose: a reader who wants one good English narrator should not
 * be made to shop a directory of hundreds. But seven is not everyone. Somebody reading in German,
 * somebody who already has the multi-speaker model they use on a laptop, somebody who trained a
 * voice of their own — all of them are asking for something the on-device track can already do, and
 * refusing them is refusing on the app's behalf rather than for any reason of theirs. The runtime
 * loads any Piper VITS model; Citation's whole involvement is knowing where two files are and what
 * to call the thing they make. So a reader supplies a name and either a link or the files, and from
 * that moment the voice is exactly as ordinary as a catalogue one: same store, same picker, same
 * delete button, same fallback when it will not load.
 *
 * What this file exists for is the one real difference between a voice the app names and a voice a
 * person types in — what an id and a URL are then allowed to be.
 *
 * - **An id becomes a filename.** `<id>.onnx` and `<id>.tokens.txt` are opened, written and deleted
 *   inside the voice store, so an id is *derived* here from the name rather than typed, and is held
 *   to a character set that cannot climb out of the directory it belongs to. [isSafeId] states that
 *   rule as something testable rather than as something the entry form is trusted to have prevented,
 *   because "the UI would never send that" stops being true the moment a second caller exists.
 * - **HTTPS, not HTTP.** An `http://` link is not a file the reader chose; it is whatever the
 *   network between them and the host decides to hand back, and what it would be handed back *as*
 *   is sixty megabytes of input to a native inference runtime. The catalogue's own answer to this is
 *   a published SHA-256, which a reader adding their own voice has no way to state, so the transport
 *   is the only integrity there is and it is required.
 * - **A name is not an id.** Two voices can both be called "Alba"; two files cannot share a path.
 *   Ids are slugged from the name and made unique against everything already known, so adding a
 *   second Alba adds a second voice instead of overwriting the first.
 *
 * None of this validates that the *file* is a voice — only the runtime can answer that, by loading
 * it. [checkFiles] catches the mistake that actually happens (the two files picked the wrong way
 * round) by their sizes, and a model that still will not load is reported the same way a corrupted
 * download is: the narrator falls back to the platform voice and says so.
 */
object CustomVoice {

    /** Long enough for "Northern English Male", short enough to stay a label. */
    const val MAX_NAME_LENGTH = 60

    /** Ids are filenames, and filenames have limits long before this one bites. */
    const val MAX_ID_LENGTH = 64

    /** What a voice synthesizes at when its owner has not said; every Piper voice but a few is this. */
    const val DEFAULT_SAMPLE_RATE = 22_050

    /** A Piper token table is about a kilobyte. Nothing remotely this large is one. */
    const val MAX_TOKENS_BYTES = 1L shl 20

    /** The smallest Piper voice is tens of megabytes. Nothing this small is a model. */
    const val MIN_MODEL_BYTES = 1L shl 20

    /** Rates a phone will play and a speech model is trained at. */
    val SAMPLE_RATES = 8_000..48_000

    /** The id given to a name with nothing sluggable in it, so such a name is still allowed. */
    private const val FALLBACK_ID = "voice"

    private val LANGUAGE_TAG = Regex("[A-Za-z]{2,3}(-[A-Za-z0-9]{1,8})*")
    private val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")

    /**
     * Turn what the reader filled in into a voice, or say why it is not one yet.
     *
     * Rejections are sentences rather than codes because every one of them is shown to the person
     * who just typed the thing being rejected, and "invalid input" tells them nothing about which
     * field to look at.
     *
     * @param taken every id already spoken for — the catalogue's and the reader's own — so the new
     *   voice gets a filename of its own instead of landing on someone else's.
     */
    fun define(draft: VoiceDraft, taken: Set<String>): Outcome {
        val name = draft.name.trim()
        if (name.isEmpty()) return Outcome.Rejected("Give the voice a name — it is what you will pick it by.")
        if (name.length > MAX_NAME_LENGTH) {
            return Outcome.Rejected("That name is longer than $MAX_NAME_LENGTH characters; shorten it.")
        }

        val language = draft.language.trim()
        if (!LANGUAGE_TAG.matches(language)) {
            return Outcome.Rejected("Say which language it speaks as a tag like en-US or de-DE.")
        }
        if (draft.speaker < 0) return Outcome.Rejected("A speaker number cannot be negative.")
        if (draft.sampleRate !in SAMPLE_RATES) {
            return Outcome.Rejected(
                "A sample rate of ${draft.sampleRate} Hz is outside what a speech model uses " +
                    "(${SAMPLE_RATES.first}–${SAMPLE_RATES.last} Hz)."
            )
        }

        val links = when (val source = draft.source) {
            is VoiceSource.Device -> "" to ""
            is VoiceSource.Link -> when (val resolved = links(source)) {
                is Links.Bad -> return Outcome.Rejected(resolved.reason)
                is Links.Ok -> resolved.model to resolved.tokens
            }
        }

        val id = idFor(name, taken)
        // Cannot happen through [idFor], which slugs to a safe alphabet and falls back when there is
        // nothing left. Checked anyway: this is the invariant the filesystem depends on, and it is
        // cheaper to answer here than to explain a file written outside the voice store.
        if (!isSafeId(id)) return Outcome.Rejected("That name does not make a usable file name.")

        return Outcome.Defined(
            VoiceModel(
                id = id,
                name = name,
                language = language,
                quality = draft.quality,
                sampleRate = draft.sampleRate,
                // Unknown until the file lands: nobody types the byte count of a download. The store
                // fills it in from what it actually wrote, so the picker stops guessing.
                sizeBytes = 0L,
                modelUrl = links.first,
                tokensUrl = links.second,
                speakers = if (draft.speaker > 0) draft.speaker + 1 else 1,
                speaker = draft.speaker,
                notes = draft.notes?.trim()?.takeIf { it.isNotEmpty() },
                origin = VoiceOrigin.USER
            )
        )
    }

    /**
     * Whether [id] is safe to use as the stem of a file in the voice store.
     *
     * ASCII letters, digits, dot, dash and underscore, starting with a letter or digit, and no `..`
     * anywhere: enough for every id the catalogue uses and for anything a slug produces, and short
     * of anything that names a directory, a parent, or a hidden file.
     */
    fun isSafeId(id: String): Boolean =
        id.isNotEmpty() &&
            id.length <= MAX_ID_LENGTH &&
            SAFE_ID.matches(id) &&
            !id.contains("..")

    /**
     * A filename-safe id for [name], unique against [taken].
     *
     * Derived rather than asked for. An id is a detail of where two files live, the reader is
     * thinking about a narrator, and a field that must be unique, lowercase and free of slashes is a
     * field that will be filled in wrongly. Collisions are settled by counting, so a second "Alba"
     * is `alba-2` and neither voice disturbs the other. Comparison is case-insensitive because some
     * filesystems are.
     */
    fun idFor(name: String, taken: Set<String>): String {
        val lowered = taken.map { it.lowercase() }.toSet()
        val base = slug(name).take(MAX_ID_LENGTH - SUFFIX_ROOM).trim('-').ifEmpty { FALLBACK_ID }
        if (base !in lowered) return base
        var ordinal = 2
        while ("$base-$ordinal" in lowered) ordinal++
        return "$base-$ordinal"
    }

    /**
     * The `tokens.txt` beside [modelUrl], or `null` when the link has no file to sit beside.
     *
     * Every Piper voice packaged for this runtime keeps its token table next to its weights, so the
     * second field of the form is one the reader almost never has to fill in — and a link they paste
     * from a browser carries a `?download=true` often enough that the query is dropped rather than
     * copied into a path.
     */
    fun siblingTokensUrl(modelUrl: String): String? {
        val path = modelUrl.substringBefore('?').substringBefore('#')
        val slash = path.lastIndexOf('/')
        if (slash < 0 || slash == path.lastIndex) return null
        val directory = path.substring(0, slash + 1)
        return if (directory.length <= HTTPS.length) null else directory + "tokens.txt"
    }

    /**
     * What is wrong with a pair of files the reader picked, or `null` when nothing obviously is.
     *
     * Only one mistake is worth catching before the runtime gets a say, and it is the one everybody
     * makes: picking the two files the wrong way round. Their sizes differ by four orders of
     * magnitude, so it is unmissable here and merely baffling later. A `null` size is a provider that
     * did not report one, which is not evidence of anything and never blocks the import.
     */
    fun checkFiles(modelBytes: Long?, tokensBytes: Long?): String? = when {
        modelBytes != null && modelBytes < MIN_MODEL_BYTES ->
            "That file is too small to be voice weights (${VoiceModel.megabytes(modelBytes)}). " +
                "The model is the large .onnx file."
        tokensBytes != null && tokensBytes > MAX_TOKENS_BYTES ->
            "That file is too large to be a token table (${VoiceModel.megabytes(tokensBytes)}). " +
                "The tokens are the small text file beside the model."
        else -> null
    }

    /** How defining a voice ended. */
    sealed class Outcome {

        /** The voice, ready to install. Nothing has been fetched or written yet. */
        data class Defined(val model: VoiceModel) : Outcome()

        /** Why it is not a voice yet, in words for the reader who typed it. */
        data class Rejected(val reason: String) : Outcome()
    }

    private const val HTTPS = "https://"

    /** Room for `-99`, so a de-duplicating suffix cannot push an id past its limit. */
    private const val SUFFIX_ROOM = 3

    /** The two links a [VoiceSource.Link] resolves to, or the sentence saying why it does not. */
    private sealed class Links {
        data class Ok(val model: String, val tokens: String) : Links()
        data class Bad(val reason: String) : Links()
    }

    private fun links(source: VoiceSource.Link): Links {
        val model = source.modelUrl.trim()
        if (model.isEmpty()) return Links.Bad("Paste the link to the voice's .onnx file.")
        if (!isHttps(model)) return Links.Bad(HTTP_ONLY)
        val tokens = source.tokensUrl.trim().ifEmpty { siblingTokensUrl(model).orEmpty() }
        if (tokens.isEmpty()) {
            return Links.Bad(
                "Paste the link to the voice's tokens.txt as well; there is nothing beside the " +
                    "model to look for it in."
            )
        }
        if (!isHttps(tokens)) return Links.Bad(HTTP_ONLY)
        return Links.Ok(model, tokens)
    }

    private const val HTTP_ONLY =
        "Voices are fetched over https:// only. Over plain http the file that arrives is whichever " +
            "one the network hands back, and it is fed straight to a speech runtime."

    private fun isHttps(url: String): Boolean =
        url.length > HTTPS.length && url.regionMatches(0, HTTPS, 0, HTTPS.length, ignoreCase = true)

    /** Lowercase, letters and digits, everything else a single dash, no dash at either end. */
    private fun slug(name: String): String = buildString {
        var pending = false
        name.lowercase().forEach { character ->
            when {
                character in 'a'..'z' || character in '0'..'9' -> {
                    if (pending && isNotEmpty()) append('-')
                    pending = false
                    append(character)
                }
                else -> pending = true
            }
        }
    }
}

/**
 * What the reader filled in, before any of it is known to be a voice.
 *
 * A draft rather than a half-built [VoiceModel], because the two are different things: a model is
 * something the store can install and the picker can show, and a form with an empty name is neither.
 * Keeping them apart is what lets [CustomVoice.define] be a total function with one place to look
 * for every rule, instead of a model that is valid in some fields and not others.
 *
 * @property sampleRate a fallback, not a fact: the runtime reports the rate of the model it actually
 *   loaded and the player uses that, so a reader who does not know theirs can leave the default and
 *   still hear the voice at the right pitch.
 */
data class VoiceDraft(
    val name: String,
    val language: String = "en-US",
    val quality: VoiceQuality = VoiceQuality.MEDIUM,
    val source: VoiceSource,
    val speaker: Int = 0,
    val sampleRate: Int = CustomVoice.DEFAULT_SAMPLE_RATE,
    val notes: String? = null
)

/** Where a reader's own voice comes from. Both end as the same two files in the same store. */
sealed class VoiceSource {

    /**
     * Fetched, the way a catalogue voice is.
     *
     * @property tokensUrl may be left empty, in which case the `tokens.txt` beside the model is
     *   used — which is where every voice packaged for this runtime keeps it.
     */
    data class Link(val modelUrl: String, val tokensUrl: String = "") : VoiceSource()

    /** Copied in from files already on the device, which is the case that works on a plane. */
    object Device : VoiceSource()
}
