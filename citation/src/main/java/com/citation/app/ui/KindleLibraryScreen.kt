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
 * A compact JS probe of the browse page's *actual* state, for the temporary diagnostics panel. Returns
 * `readyState | href | title | bodyTextLen | imageCount | linkCount | firstBodyText`, so a blank grid
 * reveals its cause on-screen: a sign-in bounce shows in the href/text, an "unsupported browser" gate
 * shows in the text, a still-loading page shows a non-`complete` readyState, and a genuinely empty grid
 * shows as `complete` with few images and short text.
 */
private const val DIAG_PROBE =
    "(function(){try{var b=document.body;var t=b?(b.innerText||''):'';" +
        "var imgs=document.images?document.images.length:0;" +
        "var links=document.querySelectorAll('a').length;" +
        "return [document.readyState,location.href,(document.title||''),t.length,imgs,links," +
        "t.slice(0,160).replace(/\\s+/g,' ')].join(' | ');}catch(e){return 'probe error: '+e;}})()"

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
 * **Diagnostics (temporary):** while we chase why the library grid can come up blank, a panel below the
 * hint actively probes the page ([DIAG_PROBE]) and shows its live state plus any console errors and
 * failed loads — so the actual failure is visible on-screen (screenshot-able) instead of hidden. Meant
 * to be removed once the cause is understood.
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
    // Temporary on-screen diagnostics.
    var pageState by remember { mutableStateOf("probing…") }
    val events = remember { mutableStateListOf<String>() } // console errors + failed loads, capped
    fun note(line: String) {
        events.add(line)
        if (events.size > 30) events.removeAt(0)
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

    // Temporary diagnostic poll: report the page's live DOM state so a blank grid shows its cause.
    LaunchedEffect(webView) {
        val view = webView ?: return@LaunchedEffect
        while (true) {
            view.evaluateJavascript(DIAG_PROBE) { raw -> pageState = unquoteJsString(raw) }
            delay(1000)
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
            // Temporary diagnostics panel — always visible so it can be screenshotted.
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            ) {
                Column(
                    Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState()).padding(8.dp)
                ) {
                    Text(
                        "WebView diagnostics (temporary):",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        pageState,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    events.forEach { line ->
                        Text(
                            line,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    // Let a debuggable build attach Chrome DevTools (chrome://inspect) to this WebView.
                    if ((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
                        WebView.setWebContentsDebuggingEnabled(true)
                    }
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
                        // Surface JS console errors/warnings from the library SPA on-screen.
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
                        // Keep every navigation — the Amazon sign-in redirects included — inside this
                        // WebView; the default would hand http(s) URLs to an external browser and break
                        // the session. Also record failed main-frame loads on-screen.
                        webViewClient = object : WebViewClient() {
                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?
                            ) {
                                if (request?.isForMainFrame == true) {
                                    note("load error: ${error?.description} @ ${request.url}")
                                }
                            }

                            override fun onReceivedHttpError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                errorResponse: WebResourceResponse?
                            ) {
                                if (request?.isForMainFrame == true) {
                                    note("http ${errorResponse?.statusCode}: ${request.url}")
                                }
                            }
                        }
                        loadUrl(library.startUrl)
                    }
                }
            )
        }
    }
}
