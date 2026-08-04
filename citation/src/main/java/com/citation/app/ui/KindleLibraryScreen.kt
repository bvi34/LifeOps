package com.citation.app.ui

import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.citation.app.data.CitationRepository
import com.citation.core.kindle.KindleLink
import kotlinx.coroutines.delay

/**
 * The Kindle **browse/library** surface — the read-in-place counterpart to Browse O'Reilly, on
 * `read.amazon.com`. This is a full-screen WebView on your own Kindle library (the Cloud Reader's book
 * grid); Amazon keeps you signed in via cookies, so there's no library proxy or stored credential like
 * O'Reilly has — you sign in here once and the session persists across opens.
 *
 * You browse to *find* a book; the moment you tap one, we learn its **ASIN** (from the reader URL's
 * `?asin=`) and its **title** (from the reader chrome / page title, cleaned of Amazon's own labels) and
 * hand them back so the book opens read-in-place — registered in your library, deduped by ASIN, exactly
 * like a book you added by typing the ASIN in.
 *
 * Unlike O'Reilly's catalog, the Cloud Reader is a single-page app: tapping a book swaps in the reader
 * *without* a fresh navigation, so `shouldOverrideUrlLoading` never fires. We therefore **poll** the
 * page (the same honest trick [KindleReaderScreen] uses to follow the footer position) and hand off once
 * a book's ASIN appears — giving the title a moment to settle so it isn't named after the reader itself.
 *
 * **Diagnostics:** while we chase why the library grid can come up blank, a debuggable build surfaces the
 * WebView's own console errors and failed page/resource loads in a panel below the hint — so the actual
 * failure is visible on-screen (screenshot-able) instead of hidden. It's gated to debuggable builds and
 * meant to be removed once the cause is understood.
 *
 * @param onOpenBook called with the tapped book's ASIN and best-effort title; the caller opens it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun KindleLibraryScreen(
    library: CitationRepository.KindleLibrary,
    onOpenBook: (asin: String, title: String) -> Unit,
    onBack: () -> Unit
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    // Hand a picked book off exactly once — the poll keeps firing until this screen is torn down.
    var handedOff by remember { mutableStateOf(false) }
    // Temporary on-screen diagnostics: WebView console errors + failed loads, newest last, capped.
    val diagnostics = remember { mutableStateListOf<String>() }
    fun note(line: String) {
        diagnostics.add(line)
        if (diagnostics.size > 40) diagnostics.removeAt(0)
    }

    // Poll the SPA while you browse: when a book's ASIN appears in the URL you've opened one. Give the
    // title a few ticks to render (the reader chrome / document title lags the URL swap) before falling
    // back to the ASIN, so a book isn't stored as "Kindle Cloud Reader".
    LaunchedEffect(webView) {
        val view = webView ?: return@LaunchedEffect
        var asinSettleTicks = 0
        while (!handedOff) {
            view.evaluateJavascript(KindleLink.libraryProbeScript()) { raw ->
                if (handedOff) return@evaluateJavascript
                val probe = unquoteJsString(raw)
                val href = probe.substringBefore('\n')
                val rawTitle = probe.substringAfter('\n', "")
                val asin = KindleLink.asinOf(href)
                if (asin == null) {
                    asinSettleTicks = 0
                } else {
                    val title = KindleLink.cleanTitle(rawTitle)
                    // Open as soon as we have a real title, or after a short settle (fall back to ASIN).
                    if (title != null || asinSettleTicks >= 3) {
                        handedOff = true
                        onOpenBook(asin, title ?: asin)
                    } else {
                        asinSettleTicks++
                    }
                }
            }
            delay(700)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Browse Kindle") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Text(
                "Sign in to Amazon if asked, then tap a book to open it read-in-place — Citation learns " +
                    "its title and ASIN for you.",
                Modifier.padding(12.dp),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.secondary
            )
            if (diagnostics.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                ) {
                    Column(
                        Modifier.heightIn(max = 140.dp).verticalScroll(rememberScrollState()).padding(8.dp)
                    ) {
                        Text(
                            "WebView diagnostics (temporary):",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        diagnostics.forEach { line ->
                            Text(
                                line,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    // Let a debuggable build attach Chrome DevTools (chrome://inspect) to this WebView so
                    // the network/console can be inspected directly, and mirror errors on-screen too.
                    val debuggable =
                        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
                    if (debuggable) WebView.setWebContentsDebuggingEnabled(true)
                    WebView(context).apply {
                        val web = this
                        webView = this
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        // Amazon keeps you signed in via cookies — persist them across opens and share
                        // them with the reader's WebView.
                        CookieManager.getInstance().apply {
                            setAcceptCookie(true)
                            setAcceptThirdPartyCookies(web, true)
                        }
                        // Surface JS console errors/warnings from the library SPA so a blank grid shows
                        // its cause on-screen (debuggable builds only).
                        if (debuggable) {
                            webChromeClient = object : WebChromeClient() {
                                override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                                    if (msg.messageLevel() == ConsoleMessage.MessageLevel.ERROR ||
                                        msg.messageLevel() == ConsoleMessage.MessageLevel.WARNING
                                    ) {
                                        note("console ${msg.messageLevel()}: ${msg.message()}")
                                    }
                                    return false // also let it reach Logcat
                                }
                            }
                        }
                        // Keep every navigation — the Amazon sign-in redirects included — inside this
                        // WebView; the default would hand http(s) URLs to an external browser and break
                        // the session. Also record failed loads (debuggable builds only).
                        webViewClient = object : WebViewClient() {
                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?
                            ) {
                                if (debuggable && request?.isForMainFrame == true) {
                                    note("load error: ${error?.description} @ ${request.url}")
                                }
                            }

                            override fun onReceivedHttpError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                errorResponse: WebResourceResponse?
                            ) {
                                if (debuggable && request?.isForMainFrame == true) {
                                    note("http ${errorResponse?.statusCode}: ${request.url}")
                                }
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                if (debuggable) note("loaded: $url")
                            }
                        }
                        loadUrl(library.startUrl)
                    }
                }
            )
        }
    }
}
