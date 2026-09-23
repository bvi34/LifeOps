package com.advisor.app.logic

/**
 * The Advisor's real generation step, backed by a local **Qwen3-4B** model (Q4_K_M GGUF) running fully
 * on-device through an [LlmBackend]. It formats the assembled [AdvisorPrompt] with Qwen3's chat
 * template ([Qwen3ChatFormat]), runs it through the backend, and cleans the completion back into a
 * grounded answer — the same prompt, retrieval and citations the rest of the pipeline already built.
 *
 * If the backend isn't ready — the multi-gigabyte weights aren't on the device yet — or a generation
 * comes back empty or throws, it transparently falls back to the deterministic [PlaceholderLlmEngine]
 * so the pipeline still produces a grounded, cited answer instead of failing. [spec] reports which of
 * the two actually answered, so the UI's model card stays honest.
 */
class Qwen3LlmEngine(
    private val backend: LlmBackend,
    private val params: GenerationParams = GenerationParams(),
    private val fallback: LocalLlmEngine = PlaceholderLlmEngine(),
    /**
     * Where the prompt-boundary diagnostics go. A sink rather than a direct `android.util.Log` call
     * because everything under `logic/` is framework-free and JVM-tested; the Android log is wired in
     * by [com.advisor.app.AdvisorApp]. Defaults to discarding, so a test never needs a mocked
     * framework to exercise generation.
     */
    private val log: (String) -> Unit = {},
    /**
     * What the phone can spare right now. Null means unmeasured, and the model always runs as it did
     * before there was a policy — which is what tests and a backend with nothing to load want.
     */
    private val device: DeviceProbe? = null,
    /**
     * How often a running generation re-reads the device. Two seconds: well inside the time it takes
     * a phone to move a thermal level, and no faster than the platform will answer a headroom query.
     */
    private val watchEveryMillis: Long = 2_000L
) : LocalLlmEngine {

    /** The budget [admit] settled for the current turn. */
    @Volatile private var budget: InferenceBudget = InferenceBudget.UNCONSTRAINED

    /**
     * What is actually running. Read from the loaded file's name rather than being a constant: the
     * model card's job is to be honest about which model is answering, and any generation GGUF can be
     * installed — reporting all of them as the one this class was written against would make the card
     * confidently wrong about the thing it exists to report.
     */
    override val spec: ModelSpec
        get() = if (backend.isReady && budget.runsModel) GgufName.specOf(backend.detail) else fallback.spec

    override val status: String
        get() = if (backend.isReady && !budget.runsModel) "${backend.detail} — ${budget.summary()}"
        else backend.detail

    override fun admit(): InferenceBudget {
        val state = sample() ?: return InferenceBudget.UNCONSTRAINED.also { budget = it }
        val decided = AdvisorConstraints.budget(state, backend.modelBytes, backend.isLoaded, params.maxTokens)
        if (decided.mode != InferenceBudget.Mode.FULL) log("Inference budget: ${decided.summary()}")
        budget = decided
        return decided
    }

    override fun describeDevice(): String? {
        val state = sample() ?: return null
        return AdvisorConstraints.describe(
            state, AdvisorConstraints.budget(state, backend.modelBytes, backend.isLoaded, params.maxTokens)
        )
    }

    override fun generate(prompt: AdvisorPrompt): String = run(prompt, onPartial = null)

    override fun generate(prompt: AdvisorPrompt, onPartial: (String) -> Unit): String =
        run(prompt, onPartial)

    /**
     * Loading ahead of the first question is a bet that one is coming; it is only placed when the
     * phone can afford to lose it. Otherwise the model loads when a question actually asks for it.
     */
    override fun warmUp() {
        val decided = admit()
        if (decided.allowWarmUp) backend.warmUp()
        else log("Warm-up skipped: ${(decided.reasons).joinToString(", ")}; the model loads on the first question.")
    }

    override val contextTokens: Int get() = backend.contextTokens

    private fun run(prompt: AdvisorPrompt, onPartial: ((String) -> Unit)?): String {
        if (!backend.isReady) return fallback.generate(prompt)
        val turn = budget
        if (!turn.runsModel) {
            return fallback.generate(prompt) +
                "\n\n(The model is paused — ${turn.reasons.joinToString(", ")}. This answer is built " +
                "from your records alone.)"
        }
        val limits = if (turn.mode == InferenceBudget.Mode.REDUCED) params.copy(maxTokens = turn.maxTokens) else params
        val formatted = Qwen3ChatFormat.forPrompt(prompt)
        log("Qwen3 prompt boundary: chars=${formatted.length} hash=${sha256(formatted)}")
        log("Qwen3 prompt boundary head=${formatted.take(120).replace("\n", "\\n")}")
        val before = sample()
        val startedAt = System.nanoTime()
        val raw = runCatching {
            watched(turn, limits) { generateWith(formatted, limits, onPartial) }
        }.getOrNull()
        logCost(before, sample(), (System.nanoTime() - startedAt) / 1_000_000, raw?.length ?: 0, limits.maxTokens)
        val answer = raw?.let { Qwen3ChatFormat.cleanOutput(it) }.orEmpty()
        val cutoff = backend.takeCutoff()
        return when {
            answer.isBlank() -> fallback.generate(prompt)
            cutoff != null -> "$answer\n\n($cutoff.)"
            else -> answer
        }
    }

    private fun generateWith(formatted: String, limits: GenerationParams, onPartial: ((String) -> Unit)?): String =
        if (onPartial == null) {
            backend.generate(formatted, limits)
        } else {
            // The backend streams raw model output; the chat format is what makes it an answer.
            // Cleaning the whole accumulation each time — rather than the newest piece — is what
            // lets a retraction (a control token completing, a think block closing) simply
            // produce a shorter string instead of needing to be undone downstream.
            val seen = StringBuilder()
            backend.generate(formatted, limits) { piece ->
                seen.append(piece)
                onPartial(Qwen3ChatFormat.cleanPartial(seen.toString()))
            }
        }

    /**
     * Runs [generation] while a watcher re-reads the device every [watchEveryMillis] and applies the
     * same rules [admit] does, to the answer already in progress: a phone that has become too hot
     * (or is forecast to) stops it, one that has become warm or started saving power shortens it.
     * The turn's budget was read before the answer started, and an answer can run for a minute.
     *
     * The reading is taken with the model counted as resident — it is, and refusing it now would
     * free nothing — so memory never stops an answer; heat and power do. A shortening is applied
     * once and only to a turn that started in full; a reduced turn is already short. A stop or a
     * limit the backend could not deliver yet (the model is still loading, prefill not armed) is
     * simply asked for again on the next reading.
     */
    private fun <T> watched(turn: InferenceBudget, limits: GenerationParams, generation: () -> T): T {
        val probe = device ?: return generation()
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        val watcher = Thread({
            var shortened = turn.mode != InferenceBudget.Mode.FULL
            while (!done.get()) {
                try {
                    Thread.sleep(watchEveryMillis)
                } catch (interrupted: InterruptedException) {
                    break
                }
                if (done.get()) break
                val state = runCatching { probe.sample() }.getOrNull() ?: continue
                val live = AdvisorConstraints.budget(state, backend.modelBytes, loaded = true, maxTokens = limits.maxTokens)
                val why = live.reasons.joinToString(", ")
                when {
                    !live.runsModel -> if (backend.interrupt(why)) {
                        log("Mid-answer: stopping — $why")
                        break
                    }
                    live.mode == InferenceBudget.Mode.REDUCED && !shortened -> if (backend.limitTokens(live.maxTokens, why)) {
                        log("Mid-answer: shortening to ${live.maxTokens} tokens — $why")
                        shortened = true
                    }
                }
            }
        }, "advisor-generation-watch").apply { isDaemon = true }
        watcher.start()
        try {
            return generation()
        } finally {
            done.set(true)
            watcher.interrupt()
        }
    }

    private fun sample(): DeviceState? = device?.let { runCatching { it.sample() }.getOrNull() }

    /**
     * What one generation cost the phone, from the platform's side: the native log says how the
     * kernels spent the time, this says what that did to the device — whether an answer walked it
     * from cool to throttling, and how much memory it left everyone else.
     */
    private fun logCost(before: DeviceState?, after: DeviceState?, wallMs: Long, chars: Int, maxTokens: Int) {
        val line = StringBuilder("Generation cost: ")
            .append(wallMs).append(" ms, ").append(chars).append(" chars (limit ").append(maxTokens).append(" tokens)")
        if (before != null && after != null) {
            line.append("; thermal ").append(before.thermal.label).append(" → ").append(after.thermal.label)
            if (before.thermalHeadroom != null || after.thermalHeadroom != null) {
                line.append(", headroom ").append(headroom(before.thermalHeadroom))
                    .append(" → ").append(headroom(after.thermalHeadroom))
            }
            line.append(", free ").append(AdvisorConstraints.gb(before.availMemBytes))
                .append(" → ").append(AdvisorConstraints.gb(after.availMemBytes))
            if (after.lowMemory) line.append(" (system reports low memory)")
        }
        log(line.toString())
    }

    private fun headroom(value: Float?): String =
        value?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: "?"

    companion object {
        /** The Android log tag [com.advisor.app.AdvisorApp] stamps the diagnostics with. */
        const val TAG = "Qwen3LlmEngine"

        private fun sha256(text: String): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            return digest.digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
                .take(16)
        }
    }
}
