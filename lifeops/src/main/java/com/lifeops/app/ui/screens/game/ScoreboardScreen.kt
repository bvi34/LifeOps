@file:OptIn(ExperimentalMaterial3Api::class)

package com.lifeops.app.ui.screens.game

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeops.app.data.model.GameScore
import com.lifeops.app.ui.components.AppHeader
import com.lifeops.app.ui.components.BackNavIcon

/**
 * The game scoreboard (DESIGN.md §6). A ranked table of finished runs — Week, the points invested in
 * the loadout, the score, and the Set·Wave reached — so the record shows which weeks were legendary.
 */
@Composable
fun ScoreboardScreen(viewModel: ScoreboardViewModel, onBack: () -> Unit) {
    val scores by viewModel.scores.collectAsStateWithLifecycle()

    Scaffold(topBar = { AppHeader(navigationIcon = { BackNavIcon(onBack) }) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text("Scoreboard", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "Every finished run, best score first. Each run is a fingerprint of the week that seeded it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }

            if (scores.isEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Text(
                            "No runs yet. Play a run — win or lose, it lands here.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            } else {
                item { HeaderRow() }
                itemsIndexed(scores) { index, score ->
                    ScoreRow(rank = index + 1, score = score, highlight = index == 0)
                }
            }
        }
    }
}

@Composable
private fun HeaderRow() {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HeaderCell("#", 0.10f)
        HeaderCell("Week", 0.30f)
        HeaderCell("Invest", 0.18f, TextAlign.End)
        HeaderCell("Score", 0.24f, TextAlign.End)
        HeaderCell("Set·Wave", 0.18f, TextAlign.End)
    }
}

@Composable
private fun ScoreRow(rank: Int, score: GameScore, highlight: Boolean) {
    Card(
        Modifier.fillMaxWidth(),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = if (highlight)
                MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Cell(rank.toString(), 0.10f, fontWeight = FontWeight.Bold)
                Cell(weekLabel(score.weekKey), 0.30f)
                Cell(score.pointInvestment.toString(), 0.18f, TextAlign.End)
                Cell(score.score.toString(), 0.24f, TextAlign.End, fontWeight = FontWeight.Bold)
                Cell("${score.setReached}·${score.waveReached}", 0.18f, TextAlign.End)
            }
            Spacer(Modifier.height(2.dp))
            Text(
                "${score.weapon}  ·  ${score.challengeMode}  ·  lvl ${score.levelReached}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.HeaderCell(
    text: String,
    weight: Float,
    align: TextAlign = TextAlign.Start,
) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        textAlign = align,
        modifier = Modifier.weight(weight)
    )
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Cell(
    text: String,
    weight: Float,
    align: TextAlign = TextAlign.Start,
    fontWeight: FontWeight? = null,
) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = fontWeight,
        fontSize = 14.sp,
        textAlign = align,
        modifier = Modifier.weight(weight),
        maxLines = 1
    )
}

/** Render the stored week key (an ISO date, the Monday of the seeding week) compactly. */
private fun weekLabel(weekKey: String): String {
    // weekKey is a LocalDate.toString() like "2026-07-20"; show it as "Jul 20" when parseable.
    val parts = weekKey.split("-")
    if (parts.size != 3) return weekKey
    val month = parts[1].toIntOrNull() ?: return weekKey
    val day = parts[2].toIntOrNull() ?: return weekKey
    val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    val name = months.getOrNull(month - 1) ?: return weekKey
    return "$name $day, ${parts[0]}"
}
