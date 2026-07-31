package com.citation.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.citation.app.ui.ReaderScreen
import com.citation.app.ui.ReaderViewModel
import com.citation.app.ui.theme.CitationTheme

/**
 * The reader's single entry point. It awaits the async-built repository, then hosts the
 * library/reader Compose tree. One activity is enough — Citation is a focused reading surface.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = CitationApplication.get(this)

        setContent {
            CitationTheme {
                var vm by remember { mutableStateOf<ReaderViewModel?>(null) }
                // Build the ViewModel once the repository is ready.
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    val repo = app.repository.await()
                    vm = ViewModelProvider(
                        this@MainActivity,
                        object : ViewModelProvider.Factory {
                            @Suppress("UNCHECKED_CAST")
                            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                                ReaderViewModel(repo) as T
                        }
                    )[ReaderViewModel::class.java]
                }
                vm?.let { ReaderScreen(it) }
            }
        }
    }
}
