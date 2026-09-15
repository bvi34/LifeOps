package com.citation.app.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.citation.app.data.CitationRepository
import com.citation.app.data.OreillyCatalog
import com.citation.core.oreilly.EzproxyLogin
import com.citation.core.oreilly.OreillyLink

/**
 * The O'Reilly **browse/catalog** surface — the read-in-place counterpart to Browse Royal Road. This
 * is a full-screen WebView on O'Reilly's own catalog, but routed through your **library's EZproxy** so
 * the whole skim runs on a library card, not a personal account: cookies persist the proxy session,
 * and when it lapses and bounces to the OCLC sign-in the WebView **auto-reauths** with your stored card
 * + PIN — the same credential-into-the-library's-own-form move the reader uses.
 *
 * You browse here to *find* a book; the moment you tap into one (`/library/view/{slug}/{id}/`), we
 * intercept the navigation, keep the WebView from opening it, and hand the book id (plus a title
 * guessed from the URL slug) back so it opens **read-in-place** through Citation's O'Reilly reader —
 * registered in your library, with note capture and resume, exactly like a book you added by hand.
 *
 * @param onOpenBook called with the tapped book's id and a best-effort title; the caller opens it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun OreillyCatalogScreen(
    catalog: OreillyCatalog,
    onOpenBook: (bookId: String, title: String) -> Unit,
    onBack: () -> Unit
) {
    var webView by remember { mutableStateOf<WebView?>(null) }

    // Fill (and submit) the library sign-in form with the stored card/PIN — same as the reader, so a
    // lapsed proxy session mid-browse re-signs-in without kicking you out of the catalog.
    fun signIn(view: WebView, autoSubmit: Boolean) {
        val login = catalog.login ?: return
        view.evaluateJavascript(EzproxyLogin.fillScript(login.card, login.pin, autoSubmit), null)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Browse O'Reilly") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Manual sign-in fallback, only offered when a card/PIN is on file.
                    if (catalog.login != null) {
                        IconButton(onClick = { webView?.let { signIn(it, autoSubmit = true) } }) {
                            Icon(Icons.Default.Lock, contentDescription = "Sign in to library with saved card")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Text(
                "Tap a book to open it read-in-place, on your library card.",
                Modifier.padding(12.dp),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.secondary
            )
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        val web = this
                        webView = this
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        // The library proxy keeps you signed in via cookies — persist them across opens
                        // and share them with the reader's WebView.
                        CookieManager.getInstance().apply {
                            setAcceptCookie(true)
                            setAcceptThirdPartyCookies(web, true)
                        }
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                val url = request?.url?.toString() ?: return false
                                // A login bounce isn't a book — let it load so onPageFinished can reauth.
                                if (EzproxyLogin.isLoginPage(url)) return false
                                // Only a real book-view page (`…/library/view/…`) opens the reader; the
                                // catalog's own navigation (search, topics, covers) doesn't parse as one
                                // and keeps loading in place.
                                val dest = OreillyLink.parse(url) ?: return false
                                val title = OreillyLink.titleFromSlug(dest.slug) ?: dest.bookId
                                onOpenBook(dest.bookId, title)
                                return true
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                view ?: return
                                url ?: return
                                // Proxy session lapsed → bounced to OCLC login. Auto-reauth if we can.
                                if (EzproxyLogin.isLoginPage(url) && catalog.login != null) {
                                    signIn(view, autoSubmit = true)
                                }
                            }
                        }
                        loadUrl(catalog.startUrl)
                    }
                }
            )
        }
    }
}
