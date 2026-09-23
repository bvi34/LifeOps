package com.health.app.connect

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.health.app.ui.theme.HealthTheme

/**
 * What Health does with what it reads from Health Connect — the page Health Connect opens from its
 * permission screen, and requires an app to have before it will show that screen at all.
 *
 * Short, and true of the code: the sentences here are the promise in Health's manifest, said to the
 * person deciding whether to grant it.
 */
class HealthConnectPrivacyActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HealthTheme {
                Scaffold(topBar = { TopAppBar(title = { Text("Health and Health Connect") }) }) { padding ->
                    Column(
                        Modifier
                            .padding(padding)
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        PARAGRAPHS.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        Button(onClick = { finish() }) { Text("Close") }
                    }
                }
            }
        }
    }

    private companion object {
        val PARAGRAPHS = listOf(
            "Health keeps a household's health records on this phone. With your permission it also " +
                "reads what Health Connect holds — activity, sleep, heart rate, body measurements, " +
                "nutrition, cycle tracking and medical records — so they sit beside what you record " +
                "by hand.",
            "Everything it reads is filed under one person: the primary user you choose in Health. " +
                "Health Connect's data is the data of whoever this phone belongs to, and Health " +
                "won't read anything until you have said who that is.",
            "It only reads. Health never writes to Health Connect or changes anything in it.",
            "What it reads stays on this phone, in Health's own database. It is not uploaded, " +
                "shared or sent anywhere. It is included in the suite's backup, the same as every " +
                "other health record, and that backup goes only where you put it.",
            "You choose which kinds of data to allow in Health Connect's own screen, and you can " +
                "withdraw any of them there at any time. Health's Health Connect screen can also " +
                "stop the import and delete everything it has imported."
        )
    }
}
