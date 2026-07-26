package com.citation.app.ui

import android.annotation.SuppressLint
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.citation.app.data.CitationRepository
import com.citation.core.oreilly.OreillyLink

/**
 * **O'Reilly read-in-place.** Their reader is the real reader — the content is licensed, so Citation
 * caches nothing. This hosts O'Reilly's own page in a WebView (session cookies persist, and re-auth
 * is expected and left to the WebView — we don't try to defeat the short-lived library tokens), lands
 * you at your saved position via the deep link, and tracks where you are so reopening is one tap from
 * your spot.
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
                modifier = Modifier.weight(1f).fillMaxWidth(),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                // Track position from the URL so reopening lands at the saved spot.
                                url?.let { OreillyLink.parse(it) }?.location?.let { loc ->
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
