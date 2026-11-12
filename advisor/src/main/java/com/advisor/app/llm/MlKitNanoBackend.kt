package com.advisor.app.llm

import android.util.Log
import com.advisor.app.logic.NanoAvailability
import com.advisor.app.logic.NanoBackend
import com.advisor.app.logic.NanoParams
import com.advisor.app.logic.NanoResult
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.common.StreamingCallback
import com.google.mlkit.genai.prompt.Candidate
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Gemini Nano through ML Kit's GenAI **Prompt API**, which hands the request to Android's AICore
 * system service. The model is AICore's, kept on the phone and run on its own accelerator; this
 * process never holds the weights, and the prompt goes to AICore over local IPC, not to a server.
 *
 * Where AICore isn't there (most phones that aren't a recent Pixel or flagship), every call reports
 * [NanoAvailability.UNAVAILABLE] instead of throwing, and Advisor answers with its local engine.
 *
 * ML Kit's own usage logging — which *would* go over the network — is switched off for the whole
 * suite in the container's manifest; see the note there.
 */
class MlKitNanoBackend : NanoBackend {

    /** Null when the client can't even be built here — no AICore, or the library is missing. */
    private val model: GenerativeModel? by lazy {
        runCatching { Generation.getClient() }
            .onFailure { Log.i(TAG, "Gemini Nano client unavailable: $it") }
            .getOrNull()
    }

    @Volatile override var availability: NanoAvailability = NanoAvailability.UNKNOWN
        private set

    @Volatile override var modelName: String? = null
        private set

    @Volatile override var tokenLimit: Int? = null
        private set

    @Volatile override var lastError: String? = null
        private set

    override fun refresh(): NanoAvailability {
        val client = model ?: return NanoAvailability.UNAVAILABLE.also { availability = it }
        val status = try {
            runBlocking { withTimeout(STATUS_TIMEOUT_MS) { client.checkStatus() } }
        } catch (t: Throwable) {
            lastError = describe(t)
            Log.i(TAG, "Gemini Nano status check failed: $t")
            return NanoAvailability.UNAVAILABLE.also { availability = it }
        }
        val mapped = when (status) {
            FeatureStatus.AVAILABLE -> NanoAvailability.AVAILABLE
            FeatureStatus.DOWNLOADABLE -> NanoAvailability.DOWNLOADABLE
            FeatureStatus.DOWNLOADING -> NanoAvailability.DOWNLOADING
            else -> NanoAvailability.UNAVAILABLE
        }
        availability = mapped
        if (mapped.usable && (modelName == null || tokenLimit == null)) readModelFacts(client)
        return mapped
    }

    /** The name and window AICore reports. Both are best-effort: the card just shows less without them. */
    private fun readModelFacts(client: GenerativeModel) {
        runCatching { runBlocking { withTimeout(STATUS_TIMEOUT_MS) { client.getBaseModelName() } } }
            .onSuccess { name -> modelName = name.takeIf { it.isNotBlank() } }
        runCatching { runBlocking { withTimeout(STATUS_TIMEOUT_MS) { client.getTokenLimit() } } }
            .onSuccess { limit -> tokenLimit = limit.takeIf { it > 0 } }
    }

    override fun generate(prompt: String, params: NanoParams, onText: ((String) -> Unit)?): NanoResult {
        val client = model ?: error("Gemini Nano isn't available on this phone")
        val request = generateContentRequest(TextPart(prompt)) {
            temperature = params.temperature
            topK = params.topK
            maxOutputTokens = params.maxOutputTokens
            candidateCount = 1
        }
        val response = try {
            runBlocking {
                withTimeout(GENERATE_TIMEOUT_MS) {
                    if (onText == null) {
                        client.generateContent(request)
                    } else {
                        client.generateContent(request, deltas(onText))
                    }
                }
            }
        } catch (t: Throwable) {
            lastError = describe(t)
            throw IllegalStateException(lastError, t)
        }
        val candidate = response.candidates.firstOrNull()
            ?: throw IllegalStateException("Gemini Nano returned no candidates").also { lastError = it.message }
        lastError = null
        return NanoResult(
            text = candidate.text,
            hitTokenLimit = candidate.finishReason == Candidate.FinishReason.MAX_TOKENS
        )
    }

    /**
     * The streaming callback as plain deltas. Each callback is documented as the newly generated
     * text; should a release ever hand over the whole text so far instead, the part already passed
     * on is dropped here rather than shown twice.
     */
    private fun deltas(onText: (String) -> Unit): StreamingCallback {
        val seen = StringBuilder()
        return StreamingCallback { text ->
            val piece = if (seen.isNotEmpty() && text.length > seen.length && text.startsWith(seen)) {
                text.substring(seen.length)
            } else {
                text
            }
            seen.append(piece)
            onText(piece)
        }
    }

    override fun warmUp() {
        val client = model ?: return
        runCatching { runBlocking { withTimeout(STATUS_TIMEOUT_MS) { client.warmup() } } }
            .onFailure { Log.i(TAG, "Gemini Nano warm-up failed: $it") }
    }

    /**
     * Ask AICore to fetch the model, reporting bytes as they arrive. The download is AICore's — the
     * system service fetches Google's model, not this app — and returns true once it has finished.
     */
    suspend fun download(onBytes: (Long) -> Unit): Boolean {
        val client = model ?: return false
        var done = false
        try {
            client.download().collect { status ->
                when (status) {
                    is DownloadStatus.DownloadProgress -> onBytes(status.totalBytesDownloaded)
                    is DownloadStatus.DownloadCompleted -> done = true
                    is DownloadStatus.DownloadFailed -> lastError = describe(status.e)
                    else -> {}
                }
            }
        } catch (t: Throwable) {
            lastError = describe(t)
        }
        refresh()
        return done
    }

    private fun describe(t: Throwable): String = when (t) {
        is GenAiException -> "${t.message ?: "AICore error"} (code ${t.errorCode})"
        else -> t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
    }

    companion object {
        private const val TAG = "MlKitNanoBackend"
        private const val STATUS_TIMEOUT_MS = 10_000L

        /** Nano answers in seconds; a minute means AICore is stuck, and the local engine should answer. */
        private const val GENERATE_TIMEOUT_MS = 60_000L
    }
}
