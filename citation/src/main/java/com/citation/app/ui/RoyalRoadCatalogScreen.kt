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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * The Royal Road **skim/catalog** surface — and *only* that. This WebView is where you browse Royal
 * Road to find a serial; it is never the reader. The moment you tap into a fiction (`/fiction/{id}`),
 * we intercept the navigation, keep the WebView from actually loading the live story, and hand the
 * fiction id back so the story opens **through Citation's reader** instead — so chapter 1 reads
 * identically to chapter 2.
 *
 * @param onOpenFiction called with the tapped fiction id; the caller routes it into the reader.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun RoyalRoadCatalogScreen(onOpenFiction: (Long) -> Unit, onBack: () -> Unit) {
    val fictionPath = Regex("/fiction/(\\d+)")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Browse Royal Road") },
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
                "Tap a story to open it in Citation's reader.",
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
                                val id = fictionPath.find(url)?.groupValues?.get(1)?.toLongOrNull()
                                return if (id != null) {
                                    // Don't load the live story page — route into our reader instead.
                                    onOpenFiction(id)
                                    true
                                } else {
                                    false
                                }
                            }
                        }
                        loadUrl("https://www.royalroad.com/fictions/best-rated")
                    }
                }
            )
        }
    }
}
