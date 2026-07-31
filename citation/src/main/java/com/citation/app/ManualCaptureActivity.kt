package com.citation.app

import android.app.AlertDialog
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.citation.core.capture.RawCapture
import kotlinx.coroutines.launch

/**
 * The **floating quick-capture** input — the catch-all for apps that expose nothing to select or
 * share. Launched from the [QuickCaptureBubbleService] bubble (or a launcher shortcut), it pops a
 * single text field *over* whatever you're looking at so you can jot a thought without leaving it.
 *
 * It captures **what you type, not what's on screen** — no overlay reads the app underneath. The
 * typed note is filed manually, so its provenance is thin by nature (usually the launcher/bubble's
 * own package or, failing that, a timestamp) and it lands in the triage queue for later tagging.
 */
class ManualCaptureActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val input = EditText(this).apply {
            hint = "Quick note…"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setLines(3)
        }

        AlertDialog.Builder(this)
            .setTitle("Capture to Citation")
            .setView(input)
            .setPositiveButton("Save") { _, _ -> save(input.text.toString()) }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun save(text: String) {
        if (text.isBlank()) {
            finish()
            return
        }
        val app = CitationApplication.get(this)
        lifecycleScope.launch {
            val message = runCatching {
                val repo = app.repository.await()
                val pkg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) referrer?.host else callingPackage
                repo.captureManual(
                    RawCapture(
                        text = text.trim(),
                        appPackage = pkg,
                        appLabel = pkg?.let { p ->
                            runCatching {
                                packageManager.getApplicationLabel(packageManager.getApplicationInfo(p, 0)).toString()
                            }.getOrNull()
                        },
                        capturedAt = System.currentTimeMillis()
                    )
                )
                "Saved to Citation."
            }.getOrElse { "Couldn't save to Citation." }
            Toast.makeText(this@ManualCaptureActivity, message, Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
