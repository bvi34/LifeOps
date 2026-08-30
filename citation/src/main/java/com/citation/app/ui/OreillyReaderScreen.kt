package com.citation.app.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebSettings
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
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.citation.app.data.CitationRepository
import com.citation.app.data.OreillyWebCache
import com.citation.app.ui.reader.ReaderWebStyler
import com.citation.core.oreilly.EzproxyLogin
import com.citation.core.oreilly.OreillyLink

/**
 * **O'Reilly read-in-place**, through your library. Their reader is the real reader — the content is
 * licensed, so Citation caches nothing. This hosts O'Reilly's own page in a WebView, routed through
 * your library's EZproxy (so you read on a library card, not a personal account). Session cookies
 * persist across opens; when the proxy session lapses and bounces to the OCLC sign-in page, the
 * reader **auto-reauths** by filling your stored library card + PIN — the credential is yours and the
 * form is the library's, so nothing here touches O'Reilly's own tokens.
 *
 * Only *your layer* is captured: as you navigate, the reader's location token is saved; a note stores
 * your quote + that token as an `External` anchor. The book's content stays theirs; the annotation is
 * yours and sovereign.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun OreillyReaderScreen(session: CitationRepository.OreillySession, vm: ReaderViewModel) {
    var currentLocation by remember { mutableStateOf<String?>(null) }
    var showNote by remember { mutableStateOf(false) }
    var quote by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var webView by remember { mutableStateOf<WebView?>(null) }

    // The Display sheet's settings reach this reader too: Citation hosts the page, it does not own
    // it, but a reader who needs a night page or their own face needs it in every book they open.
    val readerSettings by vm.settings.collectAsStateWithLifecycle()
    ReaderWebStyler(webView, readerSettings)

    // Fill (and submit) the library sign-in form with the stored card/PIN. Also the manual fallback
    // if a site tweak defeats auto-submit — the button just re-runs the same fill.
    fun signIn(view: WebView, autoSubmit: Boolean) {
        val login = session.login ?: return
        view.evaluateJavascript(EzproxyLogin.fillScript(login.card, login.pin, autoSubmit), null)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("O'Reilly (read-in-place)", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = { vm.closeOreilly() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to library")
                    }
                },
                actions = {
                    // Manual sign-in fallback, only offered when a card/PIN is on file.
                    if (session.login != null) {
                        IconButton(onClick = { webView?.let { signIn(it, autoSubmit = true) } }) {
                            Icon(Icons.Default.Lock, contentDescription = "Sign in to library with saved card")
                        }
                    }
                    IconButton(onClick = { showNote = !showNote }) {
                        Icon(Icons.Default.Add, contentDescription = "Add note")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (showNote) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    OutlinedTextField(
                        value = quote, onValueChange = { quote = it },
                        label = { Text("Passage (paste the quote — content stays theirs)") },
                        modifier = Modifier.fillMaxWidth()
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
                                vm.captureOreillyNote(currentLocation.orEmpty(), quote, body)
                                quote = ""; body = ""; showNote = false
                            },
                            enabled = quote.isNotBlank() && body.isNotBlank(),
                            modifier = Modifier.padding(start = 8.dp)
                        ) { Text("Save note") }
                    }
                }
            }

            AndroidView(
                modifier = Modifier.weight(1f).fillMaxWidth()
                    .semantics { contentDescription = "O'Reilly reader, hosted through your library" },
                factory = { context ->
                    WebView(context).apply {
                        val web = this
                        webView = this
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        // Warm page cache: online, use the cache when valid (fast reopens) and hit the
                        // network otherwise; offline, serve a page you've already opened so a brief
                        // disconnect doesn't blank the reader. Licensed pages, kept only in the
                        // OS-evictable HTTP cache — never a permanent copy.
                        settings.cacheMode =
                            if (OreillyWebCache.isOnline(context)) WebSettings.LOAD_DEFAULT
                            else WebSettings.LOAD_CACHE_ELSE_NETWORK
                        // The warm cache has a TTL — if it's gone stale, drop it before this open.
                        if (session.purgeWarmCache) clearCache(true)
                        // The library proxy keeps you signed in via cookies — persist them across opens.
                        CookieManager.getInstance().apply {
                            setAcceptCookie(true)
                            setAcceptThirdPartyCookies(web, true)
                        }
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                view ?: return
                                url ?: return
                                // Session lapsed → bounced to the OCLC login. Auto-reauth if we can.
                                if (EzproxyLogin.isLoginPage(url) && session.login != null) {
                                    signIn(view, autoSubmit = true)
                                    return
                                }
                                // Otherwise track position from the URL so reopening lands at the spot.
                                OreillyLink.parse(url)?.location?.let { loc ->
                                    currentLocation = loc
                                    vm.saveOreillyPosition(loc)
                                }
                            }
                        }
                        loadUrl(session.deepLink)
                    }
                }
            )
        }
    }
}
