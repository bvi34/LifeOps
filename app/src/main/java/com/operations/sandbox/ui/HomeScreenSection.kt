@file:OptIn(ExperimentalMaterial3Api::class)

package com.operations.sandbox.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.operations.sandbox.shortcuts.SuiteShortcuts
import com.operations.suite.ui.SuiteAppearanceStore
import com.operations.suite.ui.accentArgb
import com.operations.suitekit.SuiteAppInfo
import com.operations.suitekit.SuiteAppearance

/**
 * The sandbox's own home screen: which apps are on the grid, in what order, and which of
 * them also get an icon on the phone's home screen.
 */

/**
 * The home screen's own arrangement: which tiles, in what order.
 *
 * All of it lives here rather than behind a long-press on the tile, which is where it first went.
 * A long-press is one gesture and it already means *jump to this app's colour*; a menu taking it
 * over would trade a shortcut used whenever somebody dislikes a colour for one used the handful of
 * times a home screen is rearranged.
 *
 * It is also the only shape that works. **This is where a hidden app comes back from**, and there
 * is no tile left to long-press once an app is off the screen — so every app is listed, hidden ones
 * included, and the switch is the only control in the suite that can put one back.
 *
 * The order is the same order the grid draws, so the list reads as the screen rather than as a
 * catalogue of it — an app moved here moves there, and the arrows run out at the ends for the same
 * reason the menu's do.
 */
@Composable
internal fun HomeScreenSection(appearance: SuiteAppearance, store: SuiteAppearanceStore) {
    SectionCard(
        title = "Home screen",
        subtitle = "Which apps the home screen shows, and the order it shows them in. Hiding an " +
            "app only takes its tile away — its data, its reminders and its place in the backup " +
            "are untouched, and it still opens from anywhere else that names it."
    ) {
        appearance.arrangedApps.forEach { info ->
            key(info.appId) {
                HomeAppRow(info = info, appearance = appearance, store = store)
            }
        }

        Spacer(Modifier.height(4.dp))
        TextButton(onClick = { store.resetHomeLayout() }) {
            Text("Show every app, in the original order")
        }

        PinToPhoneRow(appearance)
    }
}

/**
 * Putting one app on the *phone's* home screen, outside the suite.
 *
 * The suite is twelve apps behind one launcher icon; this is the household taking one of them back
 * out. It sits under the arrangement because it is the same question asked of the other home
 * screen — which apps do you want to see, and where.
 *
 * Every app is offered, **including the ones hidden from the suite's own grid**. That is not an
 * oversight and it is the opposite of what the launcher's *recent* shortcuts do: those are a guess,
 * so an app somebody has hidden is left out of them, while this is somebody pointing at an app and
 * asking for it. "Not on that screen, yes on this one" is a coherent thing to want.
 *
 * The whole row is absent on a launcher that does not do pinning — a few do not, and a control that
 * silently does nothing is worse than one that was never offered.
 */
@Composable
private fun PinToPhoneRow(appearance: SuiteAppearance) {
    val context = LocalContext.current
    val canPin = remember(context) { SuiteShortcuts.isPinSupported(context) }
    if (!canPin) return

    Spacer(Modifier.height(8.dp))
    HorizontalDivider()
    Spacer(Modifier.height(12.dp))
    Text("On your phone's home screen", style = MaterialTheme.typography.titleSmall)
    Text(
        "Tap an app to give it an icon of its own, next to everything else you use. Your launcher " +
            "asks before it adds one.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(10.dp))
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        appearance.arrangedApps.forEach { info ->
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { SuiteShortcuts.pin(context, info.appId) }
                    .padding(horizontal = 6.dp, vertical = 8.dp)
                    .width(64.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                AppGlyph(appId = info.appId, argb = appearance.accentArgb(info.appId), size = 30.dp)
                Spacer(Modifier.height(6.dp))
                Text(
                    info.label,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/** One app in the home-screen list: where it sits, and whether it is on the screen at all. */
@Composable
private fun HomeAppRow(
    info: SuiteAppInfo,
    appearance: SuiteAppearance,
    store: SuiteAppearanceStore
) {
    val hidden = appearance.isHidden(info.appId)
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppGlyph(
            appId = info.appId,
            argb = appearance.accentArgb(info.appId),
            size = 26.dp,
            // A hidden app is drawn faintly rather than left out, so the list reads at a glance as
            // "these three are off" rather than as a list somebody has to compare against the grid.
            modifier = Modifier.alpha(if (hidden) 0.4f else 1f)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            info.label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (hidden) {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.weight(1f)
        )
        // Moving a hidden app is not offered: it has no position on the screen to move within, and
        // its place in the order is held for it until it comes back.
        IconButton(
            onClick = { store.moveApp(info.appId, forward = false) },
            enabled = !hidden && appearance.canMove(info.appId, forward = false)
        ) {
            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move ${info.label} earlier")
        }
        IconButton(
            onClick = { store.moveApp(info.appId, forward = true) },
            enabled = !hidden && appearance.canMove(info.appId, forward = true)
        ) {
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move ${info.label} later")
        }
        Switch(
            checked = !hidden,
            // The last one showing cannot be switched off, for the reason the tile menu gives.
            enabled = hidden || appearance.canHide(info.appId),
            onCheckedChange = { store.setHidden(info.appId, !it) }
        )
    }
}
