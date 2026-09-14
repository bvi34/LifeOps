@file:OptIn(ExperimentalMaterial3Api::class)

package com.lifeops.app.ui.screens.game

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lifeops.app.game.content.ChallengeMode
import com.lifeops.app.game.run.RunSnapshot
import com.lifeops.app.game.run.reload

/**
 * The heads-up display: hits, gold, wave, and the meters that tick during a fight.
 */

@Composable
internal fun RunHud(snapshot: RunSnapshot, modifier: Modifier = Modifier) {
    Column(modifier.padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HudChip("Set", "${snapshot.tier + 1}")
            HudChip("Wave", "${snapshot.wave}/${snapshot.totalWaves}")
            HudChip("Lvl", "${snapshot.level}/${snapshot.levelCap}")
            HudChip("Gold", "${snapshot.gold}")
            HudChip("Score", "${snapshot.score}")
        }
        if (snapshot.tempBuffs.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                snapshot.tempBuffs.forEach { b ->
                    Box(
                        Modifier
                            .background(MUZZLE_COLOR.copy(alpha = 0.25f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            "${b.label}  ${b.secondsLeft.toInt() + 1}s",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White
                        )
                    }
                }
            }
        }
        if (snapshot.challengeModeName != ChallengeMode.NONE.name) {
            Spacer(Modifier.height(6.dp))
            HudChip("Mode", snapshot.challengeModeName)
        }
        snapshot.boss?.let { boss ->
            Spacer(Modifier.height(8.dp))
            Meter(fraction = boss.healthFrac, color = BOSS_COLOR, label = "${boss.name}  ${(boss.healthFrac * 100).toInt()}%")
        }
        Spacer(Modifier.height(8.dp))
        // Discrete hearts instead of an HP bar.
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(snapshot.playerMaxHits) { i ->
                Text(
                    if (i < snapshot.playerHits) "♥" else "♡",
                    color = if (i < snapshot.playerHits) HEALTH_COLOR else Color.White.copy(alpha = 0.3f),
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Meter(
            fraction = if (snapshot.xpToNext > 0f) (snapshot.xp / snapshot.xpToNext) else 0f,
            color = XP_COLOR,
            label = "XP"
        )
        Spacer(Modifier.height(4.dp))
        // Ammo: while reloading, the bar fills with reload progress; otherwise it shows rounds left.
        Meter(
            fraction = if (snapshot.reloading) snapshot.reloadFrac
            else if (snapshot.magazine > 0) snapshot.ammo.toFloat() / snapshot.magazine else 0f,
            color = if (snapshot.reloading) MUZZLE_COLOR else AMMO_COLOR,
            label = if (snapshot.reloading) "Reloading…" else "Ammo ${snapshot.ammo}/${snapshot.magazine}"
        )
        // Spin-up bar (Gatling): shows the fire-rate wind-up while engaged.
        if (snapshot.spinUp && snapshot.spinFrac > 0.01f) {
            Spacer(Modifier.height(4.dp))
            Meter(fraction = snapshot.spinFrac, color = SPIN_COLOR, label = "Spin ${(snapshot.spinFrac * 100).toInt()}%")
        }
        if (snapshot.held.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                snapshot.held.forEach { h ->
                    Box(
                        Modifier
                            .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text("${h.name} ${h.rank}", style = MaterialTheme.typography.labelSmall, color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
private fun HudChip(label: String, value: String) {
    Box(
        Modifier
            .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.6f))
            Text(value, style = MaterialTheme.typography.labelMedium, color = Color.White, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun Meter(fraction: Float, color: Color, label: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(16.dp)
            .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .background(color.copy(alpha = 0.8f), RoundedCornerShape(8.dp))
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            modifier = Modifier.align(Alignment.Center)
        )
    }
}
