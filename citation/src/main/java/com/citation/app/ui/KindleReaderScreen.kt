package com.citation.app.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.citation.app.data.CitationRepository
import com.citation.app.data.KindleSession
import com.citation.app.ui.reader.ReaderWebStyler
import com.citation.core.kindle.KindleLink
import kotlinx.coroutines.delay

/**
 * **Kindle read-in-place**, on `read.amazon.com`. Amazon's Cloud Reader is the real reader — the
 * content is licensed and DRM'd, so Citation caches nothing. This hosts Amazon's own page in a WebView;
 * you sign in there once and cookies persist the session across opens (there's no library proxy or
 * stored credential, unlike O'Reilly).
 *
 * The reader **suppresses text selection**, so a passage can't be copied. What it does show, in the
 * clear, is the footer's position label ("Location 156 of 3866 · 4%"); we poll that as you read. A note
 * therefore cites the **location**, not the words: the position label stands in as the quote (kept as a
 * real quote instead if you ever manage to paste one), your annotation is your own, and the book is the
 * source.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun KindleReaderScreen(session: KindleSession, vm: ReaderViewModel) {
    // The reader's current position label ("Location 156 of 3866"), polled from the footer.
    var currentLocation by remember { mutableStateOf<String?>(null) }
    var showNote by remember { mutableStateOf(false) }
    var quote by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var webView by remember { mutableStateOf<WebView?>(null) }

    // The Display sheet's settings reach this reader too: Citation hosts the page, it does not own
    // it, but a reader who needs a night page or their own face needs it in every book they open.
    val readerSettings by vm.settings.collectAsStateWithLifecycle()
    ReaderWebStyler(webView, readerSettings)

    // Poll the footer while the reader is open: read the position label, and when it changes, reflect it
    // in the note composer's prefill and persist it as "where you were". The page is a single-page app,
    // so the position updates without a navigation — a timer is the honest way to follow it.
    LaunchedEffect(webView, session.bookKey) {
        val view = webView ?: return@LaunchedEffect
        while (true) {
            view.evaluateJavascript(KindleLink.footerProbeScript()) { raw ->
                KindleLink.parseFooter(unquoteJsString(raw))?.label?.let { label ->
                    if (label != currentLocation) {
                        currentLocation = label
                        vm.saveKindlePosition(label)
                    }
                }
            }
            delay(1200)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Kindle (read-in-place)", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = { vm.closeKindle() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to library")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        // Prefill the citation with the location — the passage can't be copied here.
                        if (!showNote) { quote = currentLocation.orEmpty(); body = "" }
                        showNote = !showNote
                    }) {
                        Icon(Icons.Default.Add, contentDescription = "Add note")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (showNote) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        currentLocation?.let { "You're at $it" }
                            ?: "Reading position not read yet — it'll fill in as you turn a page.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    OutlinedTextField(
                        value = quote, onValueChange = { quote = it },
                        label = { Text("Citation (the location — copying is blocked here)") },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                    OutlinedTextField(
                        value = body, onValueChange = { body = it },
                        label = { Text("Your note") },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { showNote = false }) { Text("Cancel") }
                        Button(
                            onClick = {
                                // Location is both the anchor and the fallback quote; the VM keeps a
                                // typed quote if there is one, else uses the location.
                                vm.captureKindleNote(currentLocation.orEmpty(), quote, body)
                                quote = ""; body = ""; showNote = false
                            },
                            enabled = body.isNotBlank() && (quote.isNotBlank() || currentLocation != null),
                            modifier = Modifier.padding(start = 8.dp)
                        ) { Text("Save note") }
                    }
                }
            }

            AndroidView(
                modifier = Modifier.weight(1f).fillMaxWidth()
                    .semantics { contentDescription = "Kindle Cloud Reader, read-in-place on read.amazon.com" },
                factory = { context ->
                    WebView(context).apply {
                        val web = this
                        webView = this
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        // Amazon keeps you signed in via cookies — persist them across opens.
                        CookieManager.getInstance().apply {
                            setAcceptCookie(true)
                            setAcceptThirdPartyCookies(web, true)
                        }
                        // Keep every navigation — the Amazon sign-in redirects included — inside this
                        // WebView; the default (no client) would hand http(s) URLs to an external browser
                        // and break the session.
                        webViewClient = WebViewClient()
                        loadUrl(session.readerUrl)
                    }
                }
            )
        }
    }
}

/**
 * `WebView.evaluateJavascript` hands back a JSON-encoded value: a string comes wrapped in quotes with
 * `\"`/`\\` escapes (and `null` for a null result). Unwrap it to the bare footer text before parsing —
 * enough for [KindleLink.parseFooter], which ignores anything that isn't a Location/Page/percent.
 */
internal fun unquoteJsString(raw: String?): String {
    if (raw == null || raw == "null") return ""
    val trimmed = raw.trim()
    val inner = if (trimmed.length >= 2 && trimmed.first() == '"' && trimmed.last() == '"') {
        trimmed.substring(1, trimmed.length - 1)
    } else trimmed
    return inner.replace("\\\"", "\"").replace("\\\\", "\\")
}
