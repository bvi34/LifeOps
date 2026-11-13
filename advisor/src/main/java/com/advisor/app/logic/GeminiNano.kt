package com.advisor.app.logic

/**
 * Whether the phone's own system model — Gemini Nano, served by Android's AICore — can answer, as
 * AICore itself reports it. Mirrors ML Kit's `FeatureStatus`, plus [UNKNOWN] for "not asked yet",
 * so this layer stays free of the framework and JVM-testable like the rest of `logic/`.
 */
enum class NanoAvailability(val label: String) {
    /** Downloaded and ready: a question can go to it now. */
    AVAILABLE("ready on this phone"),

    /** The phone supports it but AICore hasn't fetched the model yet. */
    DOWNLOADABLE("supported, but not downloaded yet"),

    /** AICore is fetching it. */
    DOWNLOADING("downloading"),

    /** This phone has no AICore, or AICore doesn't offer Nano to apps here. */
    UNAVAILABLE("not available on this phone"),

    /** Nobody has asked AICore yet. */
    UNKNOWN("not checked yet");

    val usable: Boolean get() = this == AVAILABLE
}

/** One finished Nano generation: the raw text, and whether it stopped at the reply limit. */
data class NanoResult(val text: String, val hitTokenLimit: Boolean = false)

/**
 * The narrow seam Gemini Nano runs behind — the counterpart of [LlmBackend] for a model that lives in
 * a system service instead of in this process. The Android implementation
 * ([com.advisor.app.llm.MlKitNanoBackend]) talks to AICore through ML Kit's Prompt API; everything
 * that decides *when* to use it and how to shape its prompt is here, framework-free.
 *
 * All calls block; callers are already off the main thread (the repository runs generation on
 * `Dispatchers.Default`).
 */
interface NanoBackend {
    /** The last availability AICore reported, without asking again. */
    val availability: NanoAvailability

    /** Ask AICore again and return (and remember) the answer. Never throws. */
    fun refresh(): NanoAvailability

    /** The model AICore says it is serving, e.g. a Gemini Nano version; null until known. */
    val modelName: String? get() = null

    /** The input window AICore reported, in tokens; null until known. */
    val tokenLimit: Int? get() = null

    /** Why the last call failed, for the model card; null when nothing has. */
    val lastError: String? get() = null

    /**
     * Generate a reply to [prompt], at most [maxOutputTokens] long. [onText] receives each new piece
     * as it arrives (raw, in order). Throws on any failure; the engine turns that into a fallback.
     */
    fun generate(
        prompt: String,
        params: NanoParams,
        onText: ((String) -> Unit)? = null
    ): NanoResult

    /** Get AICore to load the model ahead of the first question. Best-effort. */
    fun warmUp() {}
}

/** The sampling knobs the Prompt API takes. */
data class NanoParams(
    val maxOutputTokens: Int = GeminiNanoFormat.MAX_OUTPUT_TOKENS,
    val temperature: Float = 0.3f,
    val topK: Int = 16
)

/**
 * How Advisor's prompt is put to Gemini Nano, and how its reply is cleaned back into an answer.
 *
 * The Prompt API takes one block of text — there is no chat template to mark where one turn ends —
 * so the earlier conversation is given as a clearly closed transcript with an instruction not to
 * continue it. That matters: [AdvisorPrompt.render]'s comment explains how a model handed
 * `Advisor: <previous answer>` as an open line restates it and every reply grows by the last one.
 * The grounded body follows unchanged, ending on its `ANSWER:` cue.
 */
object GeminiNanoFormat {

    /**
     * The reply limit asked of Nano. The Prompt API caps output well below the 512 tokens the local
     * Qwen3 is given; asking for more is an error rather than a longer answer.
     */
    const val MAX_OUTPUT_TOKENS = 256

    fun forPrompt(prompt: AdvisorPrompt): String = buildString {
        val system = prompt.system.trim()
        if (system.isNotEmpty()) append(system).append("\n\n")

        val turns = prompt.conversation.filter { it.text.isNotBlank() }
        if (turns.isNotEmpty()) {
            append("EARLIER IN THIS CONVERSATION (finished turns, oldest first — use them to resolve ")
            append("follow-ups, never repeat or continue them):\n")
            for (turn in turns) {
                append(if (turn.fromUser) "User: " else "Advisor: ").append(turn.text.trim()).append('\n')
            }
            append("(end of earlier conversation)\n\n")
        }

        append(prompt.render(includeConversation = false).removePrefix(prompt.system).trimStart())
    }

    /**
     * The reply as an answer: trimmed, and without a speaker label or `ANSWER:` cue the model echoed
     * at the start — the one habit a prompt that ends on a cue invites.
     */
    fun cleanOutput(raw: String): String {
        var text = raw.trim()
        while (true) {
            val label = LEADING_LABEL.find(text) ?: break
            text = text.substring(label.range.last + 1).trimStart()
        }
        return text.trim()
    }

    /**
     * [cleanOutput] for a reply still arriving: a start that could still turn into a label (`ANS`,
     * `Advis`) is held back until it either becomes one and is dropped, or turns out to be prose.
     * Without this the screen would flash "ANSWER" and then take it back.
     */
    fun cleanPartial(raw: String): String {
        val text = cleanOutput(raw)
        val couldBeLabel = LABELS.any { it.startsWith(text, ignoreCase = true) }
        return if (couldBeLabel) "" else text
    }

    private val LABELS = listOf("ANSWER:", "Advisor:", "Assistant:")

    private val LEADING_LABEL = Regex("^(?:ANSWER|Advisor|Assistant)\\s*:", RegexOption.IGNORE_CASE)
}

/**
 * Gemini Nano, used **in front of** the local engine when the user has turned it on and the phone
 * has it. On a Pixel with AICore that means answers from a model the phone already keeps resident on
 * its TPU — no multi-gigabyte GGUF to import, and none of this process's memory spent holding one.
 *
 * Whenever Nano can't answer — switched off, not on this phone, not downloaded, or a call that fails
 * — every method hands over to [local], which is the same Qwen3 → placeholder chain Advisor ran
 * before Nano existed. So turning this on can only add an answerer, never remove one.
 *
 * Which of the two answers is settled once per question, in [admit], the same contract the local
 * engine already keeps: [spec], [generate] and the caller's choices all agree for the whole turn.
 */
class GeminiNanoLlmEngine(
    private val backend: NanoBackend,
    private val local: LocalLlmEngine,
    /** The user's switch on the model card. Read per turn, so flipping it applies to the next question. */
    private val enabled: () -> Boolean,
    private val params: NanoParams = NanoParams(),
    private val log: (String) -> Unit = {}
) : LocalLlmEngine {

    /** Whether this turn goes to Nano, as [admit] decided; until then, what the cached state says. */
    @Volatile private var nanoThisTurn: Boolean? = null

    /** The last time Nano was meant to answer and couldn't — shown in [status] until one succeeds. */
    @Volatile private var lastFailure: String? = null

    private fun usingNano(): Boolean = nanoThisTurn ?: (enabled() && backend.availability.usable)

    override val spec: ModelSpec
        get() = if (usingNano()) nanoSpec() else local.spec

    private fun nanoSpec() = ModelSpec(
        name = backend.modelName?.takeIf { it.isNotBlank() } ?: "Gemini Nano",
        parameters = "system model",
        quantization = "AICore",
        isPlaceholder = false
    )

    override val status: String
        get() = when {
            !enabled() -> local.status
            usingNano() -> lastFailure?.let { "Gemini Nano is answering; the last attempt failed ($it)." }
                ?: "Gemini Nano is answering, through Android's AICore — on the phone, not in this app."
            else -> "Gemini Nano is ${backend.availability.label}" +
                (backend.lastError?.let { " ($it)" } ?: "") +
                ", so the local engine answers: ${local.status}"
        }

    override val contextTokens: Int
        get() = if (usingNano()) backend.tokenLimit?.takeIf { it > 0 } ?: LlmBackend.DEFAULT_CONTEXT_TOKENS
        else local.contextTokens

    override fun admit(): InferenceBudget {
        val nano = enabled() && backend.refresh().usable
        nanoThisTurn = nano
        if (!nano) return local.admit()
        // Nano runs in AICore, not here: this process's memory and the weights' size, which the local
        // budget is about, don't apply. Its own reply cap does.
        return InferenceBudget.UNCONSTRAINED.copy(maxTokens = params.maxOutputTokens)
    }

    override fun describeDevice(): String? = if (usingNano()) null else local.describeDevice()

    /** Warm whichever will answer. Nano is cheap to warm; the local model only if it's the one. */
    override fun warmUp() {
        if (enabled() && backend.refresh().usable) {
            runCatching { backend.warmUp() }
        } else {
            local.warmUp()
        }
    }

    override fun generate(prompt: AdvisorPrompt): String = run(prompt, null)

    override fun generate(prompt: AdvisorPrompt, onPartial: (String) -> Unit): String = run(prompt, onPartial)

    private fun run(prompt: AdvisorPrompt, onPartial: ((String) -> Unit)?): String {
        if (!usingNano()) return localAnswer(prompt, onPartial)

        val formatted = GeminiNanoFormat.forPrompt(prompt)
        val seen = StringBuilder()
        val streamed: ((String) -> Unit)? = onPartial?.let { emit ->
            { piece: String ->
                seen.append(piece)
                emit(GeminiNanoFormat.cleanPartial(seen.toString()))
            }
        }
        val startedAt = System.nanoTime()
        val result = runCatching { backend.generate(formatted, params, streamed) }
        val ms = (System.nanoTime() - startedAt) / 1_000_000

        val failure = result.exceptionOrNull()
        val answer = result.getOrNull()?.let { GeminiNanoFormat.cleanOutput(it.text) }.orEmpty()
        if (failure != null || answer.isBlank()) {
            val why = failure?.let { it.message?.takeIf(String::isNotBlank) ?: it.javaClass.simpleName }
                ?: "it returned nothing"
            lastFailure = why
            log("Gemini Nano failed after $ms ms ($why); answering with the local engine.")
            // This turn is now the local engine's: the model card and the answer should say so, and
            // the local engine gets to read the phone for it, since nothing admitted it this turn.
            nanoThisTurn = false
            local.admit()
            return localAnswer(prompt, onPartial)
        }

        lastFailure = null
        log("Gemini Nano answered in $ms ms, ${answer.length} chars")
        return if (result.getOrNull()?.hitTokenLimit == true) {
            "$answer\n\n(Cut short at Gemini Nano's reply limit.)"
        } else {
            answer
        }
    }

    private fun localAnswer(prompt: AdvisorPrompt, onPartial: ((String) -> Unit)?): String =
        if (onPartial == null) local.generate(prompt) else local.generate(prompt, onPartial)

    companion object {
        const val TAG = "GeminiNanoLlmEngine"
    }
}
