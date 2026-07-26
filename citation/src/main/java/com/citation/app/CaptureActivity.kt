package com.citation.app

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Patterns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.citation.app.data.CitationRepository
import com.citation.core.capture.RawCapture
import kotlinx.coroutines.launch

/**
 * The **sanctioned capture entry point** — a tiny, chromeless activity that receives text handed to
 * Citation from *another app* and files it as a note without ever switching you into the reader.
 *
 * It backs three of the capture surfaces the feature calls for:
 *  - **`PROCESS_TEXT`** — the "Save to Citation" item in the system text-selection toolbar. Highlight
 *    in any app with standard selectable text (a browser, most readers/PDF viewers), tap, done.
 *  - **Share target** — `ACTION_SEND` of `text/plain`, so anything with a share button (including
 *    Kindle's own highlight-share) can push a passage or a link in.
 *  - **Kindle notebook import** — when the shared text is actually a Kindle *notebook export* (HTML),
 *    it is parsed into per-highlight captures instead of one blob.
 *
 * There is deliberately **no overlay and no reading of the app underneath** — Citation only ever
 * receives content another app chose to hand it. The activity resolves provenance via the ladder (in
 * the repository), shows a one-line confirmation, and finishes.
 */
class CaptureActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = extractText()
        if (text.isNullOrBlank()) {
            toastAndFinish("Nothing to capture.")
            return
        }
        val app = application as CitationApplication
        lifecycleScope.launch {
            val message = runCatching {
                val repo = app.repository.await()
                fileCapture(repo, text)
            }.getOrElse { "Couldn't save to Citation." }
            toastAndFinish(message)
        }
    }

    /** File the incoming [text], routing a Kindle notebook export to the highlight importer. */
    private suspend fun fileCapture(repo: CitationRepository, text: String): String {
        if (looksLikeKindleNotebook(text)) {
            repo.importKindleNotebook(text)?.let { count ->
                return "Imported $count Kindle highlight${if (count == 1) "" else "s"}."
            }
        }
        val url = firstUrlIn(text)
        val raw = RawCapture(
            text = text,
            title = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.takeIf { it.isNotBlank() },
            url = url,
            appPackage = referrerPackage(),
            location = url,
            capturedAt = System.currentTimeMillis()
        )
        // A shared bare URL (a link with no prose) is a manual bookmark; selected prose is a quote.
        val note = if (url != null && url == text.trim()) {
            repo.captureManual(raw)
        } else {
            repo.captureQuoted(raw, quote = text, annotation = "")
        }
        return "Saved to Citation — “${note.source.title.take(40)}”."
    }

    /** The captured text from either a PROCESS_TEXT selection or a shared `text/plain`. */
    private fun extractText(): String? = when (intent?.action) {
        Intent.ACTION_PROCESS_TEXT ->
            intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        Intent.ACTION_SEND ->
            intent.getStringExtra(Intent.EXTRA_TEXT)
        else -> null
    }

    /** The package that shared into us, when the OS surfaces it — the ladder's app-name fallback. */
    private fun referrerPackage(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) referrer?.host else callingPackage

    private fun firstUrlIn(text: String): String? =
        Patterns.WEB_URL.matcher(text).let { if (it.find()) text.substring(it.start(), it.end()) else null }

    private fun looksLikeKindleNotebook(text: String): Boolean =
        text.contains("class=\"bookTitle", ignoreCase = true) && text.contains("noteText", ignoreCase = true)

    private fun toastAndFinish(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        finish()
    }
}
