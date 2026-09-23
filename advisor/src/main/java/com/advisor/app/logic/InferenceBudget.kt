package com.advisor.app.logic

import java.util.Locale

/**
 * The platform's thermal status, in its own order (`PowerManager.THERMAL_STATUS_*`). Kept here, not
 * read off the framework, because the policy that reacts to it is JVM-tested like the rest of `logic/`.
 */
enum class ThermalLevel(val label: String) {
    NONE("cool"),
    LIGHT("warm"),
    MODERATE("hot"),
    SEVERE("very hot"),
    CRITICAL("critically hot"),
    EMERGENCY("overheating"),
    SHUTDOWN("about to shut down");

    /**
     * Whether a generation already running should be stopped. At SEVERE the platform is throttling
     * hard and the next steps are the device's own emergency measures; four big cores pegged for
     * another minute is exactly the load that walks it there.
     */
    val stopsGeneration: Boolean get() = this >= SEVERE
}

/**
 * One reading of what the phone can spare, taken just before the model is asked to do something.
 * Every field is what the platform reported, not an estimate — the Android side
 * ([com.advisor.app.device.DeviceMonitor]) samples it, this layer only decides.
 */
data class DeviceState(
    /** RAM the kernel can hand out (`MemoryInfo.totalMem`) — a little under what is on the box. */
    val totalMemBytes: Long,
    /** What the box says (`MemoryInfo.advertisedMem`), for the card; 0 if unknown. */
    val advertisedMemBytes: Long = 0L,
    /** Free plus cheaply reclaimable RAM right now (`MemoryInfo.availMem`). */
    val availMemBytes: Long,
    /** The system already considers memory low and is killing background work (`MemoryInfo.lowMemory`). */
    val lowMemory: Boolean = false,
    val thermal: ThermalLevel = ThermalLevel.NONE,
    /**
     * `PowerManager.getThermalHeadroom`: 1.0 is where SEVERE throttling begins, forecast a few seconds
     * ahead. Null when the device doesn't report it — most don't below a flagship.
     */
    val thermalHeadroom: Float? = null,
    val powerSave: Boolean = false,
    /** Battery charge, 0–100; null if unreadable. */
    val batteryPercent: Int? = null,
    val charging: Boolean = true,
    /**
     * Resident memory of this process when it last died of low memory (`ApplicationExitInfo` with
     * `REASON_LOW_MEMORY`), if the last exit was that. The suite shares one process, so it is only
     * the model's doing when this is at least the size of the weights.
     */
    val lastLowMemoryKillRssBytes: Long? = null
)

/**
 * How the model may run for this turn. Three modes, and the one that applies is the most restrictive
 * any single reading calls for:
 *
 * - [Mode.FULL] — nothing is short; the model runs with the caller's own limits.
 * - [Mode.REDUCED] — the phone is hot, saving power or low on battery. The model still answers, but
 *   shorter and with no second (refinement) pass, because a second pass is a second full generation.
 * - [Mode.PAUSED] — running the model now would do harm: it would push the phone into its thermal
 *   emergency measures, or a cold load of gigabytes would get the whole suite (which shares this
 *   process) killed for memory. The grounded placeholder answers instead, and says why.
 *
 * [reasons] are short phrases for the model card and the answer's footnote — what was short, not
 * what the code did about it.
 */
data class InferenceBudget(
    val mode: Mode,
    val maxTokens: Int,
    val allowRefinement: Boolean,
    val allowWarmUp: Boolean,
    val reasons: List<String> = emptyList()
) {
    enum class Mode { FULL, REDUCED, PAUSED }

    val runsModel: Boolean get() = mode != Mode.PAUSED

    /** e.g. "Reduced — phone is hot, battery saver is on". */
    fun summary(): String = when (mode) {
        Mode.FULL -> "Full speed"
        Mode.REDUCED -> "Reduced — ${reasons.joinToString(", ")}"
        Mode.PAUSED -> "Paused — ${reasons.joinToString(", ")}"
    }

    companion object {
        /** No reading taken, or nothing to limit: what every backend got before there was a policy. */
        val UNCONSTRAINED = InferenceBudget(
            mode = Mode.FULL,
            maxTokens = GenerationParams().maxTokens,
            allowRefinement = true,
            allowWarmUp = true
        )
    }
}

/**
 * Decides an [InferenceBudget] from a [DeviceState]. Pure, and deliberately plain: every rule is a
 * threshold the model card can explain in a phrase.
 */
object AdvisorConstraints {

    /**
     * Memory a loaded model needs beyond its weights: the Q8_0 KV cache at 3072 tokens (~235 MiB),
     * ggml's compute buffers, and the tokenizer. Generous on purpose — under-counting here costs the
     * whole suite, since they share a process.
     */
    const val LOAD_RESERVE_BYTES = 512L shl 20

    /**
     * The most of the phone's RAM a model may claim. The weights are read into anonymous memory, not
     * mmap'd, so a model that is too big for the device does not crawl — it gets the process killed.
     * 60% leaves the system, the launcher and the rest of the suite something to live in: a 4B Q4_K_M
     * (~2.5 GB) wants a 6 GB phone, a 1.7B (~1.1 GB) runs on a 4 GB one.
     */
    const val MAX_SHARE_OF_RAM = 0.6

    /** Thermal headroom at which the model starts answering shorter. 1.0 is SEVERE. */
    const val REDUCE_AT_HEADROOM = 0.85f

    /** At or under this charge, off the charger, the model answers shorter. */
    const val LOW_BATTERY_PERCENT = 15

    /** The shortest reply a reduced budget still allows — enough for a real answer, not an essay. */
    const val MIN_REDUCED_TOKENS = 192

    /**
     * @param modelBytes size of the installed weights; 0 when none is installed (nothing to fit).
     * @param loaded whether the weights are already in memory — their cost is then already paid, and
     *   refusing to use them would free nothing.
     * @param maxTokens the caller's own reply limit, which a reduced budget halves.
     */
    fun budget(
        state: DeviceState,
        modelBytes: Long,
        loaded: Boolean,
        maxTokens: Int = GenerationParams().maxTokens
    ): InferenceBudget {
        val paused = ArrayList<String>()
        val reduced = ArrayList<String>()
        val noWarmUp = ArrayList<String>()

        // Heat. Status is what is happening; headroom is what is about to.
        when {
            state.thermal >= ThermalLevel.SEVERE -> paused += "phone is ${state.thermal.label}"
            (state.thermalHeadroom ?: 0f) >= 1f -> paused += "phone is about to throttle"
            state.thermal >= ThermalLevel.MODERATE -> reduced += "phone is ${state.thermal.label}"
            (state.thermalHeadroom ?: 0f) >= REDUCE_AT_HEADROOM -> reduced += "phone is heating up"
        }

        // Memory. Only a *cold* load can be refused — resident weights are already paid for.
        if (!loaded && modelBytes > 0) {
            val needed = modelBytes + LOAD_RESERVE_BYTES
            when {
                state.totalMemBytes > 0 && needed > state.totalMemBytes * MAX_SHARE_OF_RAM ->
                    paused += "the model needs ${gb(needed)}, too much for this phone's " +
                        "${gb(state.advertisedMemBytes.takeIf { it > 0 } ?: state.totalMemBytes)}"
                state.lowMemory ->
                    paused += "memory is low right now (${gb(state.availMemBytes)} free)"
            }
        }

        // Power.
        if (state.powerSave) {
            reduced += "battery saver is on"
            noWarmUp += "battery saver is on"
        }
        val battery = state.batteryPercent
        if (!state.charging && battery != null && battery <= LOW_BATTERY_PERCENT) {
            reduced += "battery at $battery%"
        }

        // Loading ahead of the first question is a guess that one is coming. When the last time
        // this process held the model ended with the system killing it for memory, that guess has
        // already cost the whole suite once; wait until someone actually asks.
        val killRss = state.lastLowMemoryKillRssBytes
        if (modelBytes > 0 && killRss != null && killRss >= modelBytes) {
            noWarmUp += "the suite was closed for memory last time the model was loaded"
        }
        if (state.thermal >= ThermalLevel.MODERATE) noWarmUp += "phone is ${state.thermal.label}"

        return when {
            paused.isNotEmpty() -> InferenceBudget(
                mode = InferenceBudget.Mode.PAUSED,
                maxTokens = 0,
                allowRefinement = false,
                allowWarmUp = false,
                reasons = paused + reduced
            )
            reduced.isNotEmpty() -> InferenceBudget(
                mode = InferenceBudget.Mode.REDUCED,
                maxTokens = (maxTokens / 2).coerceAtLeast(MIN_REDUCED_TOKENS).coerceAtMost(maxTokens),
                allowRefinement = false,
                allowWarmUp = noWarmUp.isEmpty(),
                reasons = reduced
            )
            else -> InferenceBudget(
                mode = InferenceBudget.Mode.FULL,
                maxTokens = maxTokens,
                allowRefinement = true,
                allowWarmUp = noWarmUp.isEmpty(),
                reasons = noWarmUp
            )
        }
    }

    /** One line for the model card: what the phone has, then what that allows. */
    fun describe(state: DeviceState, budget: InferenceBudget): String = buildString {
        val total = state.advertisedMemBytes.takeIf { it > 0 } ?: state.totalMemBytes
        if (total > 0) append(gb(total)).append(" RAM · ").append(gb(state.availMemBytes)).append(" free · ")
        append(state.thermal.label)
        state.thermalHeadroom?.let { append(String.format(Locale.US, " (headroom %.2f)", it)) }
        state.batteryPercent?.let { append(" · battery ").append(it).append('%') }
        if (state.charging) append(" charging")
        if (state.powerSave) append(" · saver on")
        append("\n").append(budget.summary())
        if (budget.mode == InferenceBudget.Mode.FULL && !budget.allowWarmUp && budget.reasons.isNotEmpty()) {
            append(" — loads on the first question (").append(budget.reasons.joinToString(", ")).append(')')
        }
    }

    internal fun gb(bytes: Long): String =
        String.format(Locale.US, "%.1f GB", bytes.coerceAtLeast(0L) / (1024.0 * 1024.0 * 1024.0))
}

/**
 * Where the engine reads the device from. A seam rather than a direct framework call so the engine
 * stays JVM-testable; the app wires in [com.advisor.app.device.DeviceMonitor].
 */
fun interface DeviceProbe {
    fun sample(): DeviceState
}
