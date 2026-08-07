package com.citation.app.ui

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * The Archive of Our Own **skim/catalog** surface — and *only* that, the AO3 twin of
 * [RoyalRoadCatalogScreen]. This WebView is where you browse AO3 to find a work; it is never the
 * reader. The moment you tap into a work (`/works/{id}`), we intercept the navigation, keep the
 * WebView from actually loading the live work, and hand the work id back so it opens **through
 * Citation's reader** instead — so chapter 1 reads identically to chapter 2.
 *
 * The `/works/{id}` regex also matches a chapter URL (`/works/{id}/chapters/{cid}`), so a tap on
 * either lands the reader on the work; the coordinator resolves the full catalog regardless.
 *
 * @param onOpenWork called with the tapped work id; the caller routes it into the reader.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun Ao3CatalogScreen(onOpenWork: (Long) -> Unit, onBack: () -> Unit) {
    val workPath = Regex("/works/(\\d+)")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Browse Archive of Our Own") },
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
                "Tap a work to open it in Citation's reader.",
                Modifier.padding(12.dp)
            )
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                val url = request?.url?.toString() ?: return false
                                // Only a work page (not /works/search, /works/new, tag pages, etc.)
                                // carries a numeric id right after /works/.
                                val id = workPath.find(url)?.groupValues?.get(1)?.toLongOrNull()
                                return if (id != null) {
                                    // Don't load the live work page — route into our reader instead.
                                    onOpenWork(id)
                                    true
                                } else {
                                    false
                                }
                            }
                        }
                        loadUrl("https://archiveofourown.org/works/search")
                    }
                }
            )
        }
    }
}
