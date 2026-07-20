@file:OptIn(ExperimentalMaterial3Api::class)

package com.lifeops.app.ui.screens.game

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lifeops.app.ui.components.AppHeader
import com.lifeops.app.ui.components.HubCard

/**
 * The Game hub: landing page for the arcade run and its economy. Sits between Planning and History
 * in the bottom nav — the present-tense payoff for the past-tense work the rest of the app records.
 */
@Composable
fun GameScreen(
    onPlayRun: () -> Unit,
    onOpenResources: () -> Unit,
    onOpenArtifacts: () -> Unit,
    onOpenScoreboard: () -> Unit,
) {
    Scaffold(topBar = { AppHeader() }) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                HubCard(
                    title = "Play a Run",
                    subtitle = "Spend Energy earned this week on a wave-based holdout",
                    icon = Icons.Default.SportsEsports,
                    onClick = onPlayRun
                )
            }
            item {
                HubCard(
                    title = "Scoreboard",
                    subtitle = "Every finished run: week, investment, score, set reached",
                    icon = Icons.Default.EmojiEvents,
                    onClick = onOpenScoreboard
                )
            }
            item {
                HubCard(
                    title = "Resources",
                    subtitle = "Aspect earnings and the run economy",
                    icon = Icons.Default.Diamond,
                    onClick = onOpenResources
                )
            }
            item {
                HubCard(
                    title = "Artifacts",
                    subtitle = "The baseline draft pool and what each rank does",
                    icon = Icons.Default.AutoAwesome,
                    onClick = onOpenArtifacts
                )
            }
        }
    }
}
