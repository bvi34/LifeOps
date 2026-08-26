package com.operations.sandbox.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.operations.backupkit.AppId
import com.operations.suite.ui.LocalSuiteAppearance
import com.operations.suite.ui.SuiteIcons
import com.operations.suite.ui.accentArgb
import com.operations.suitekit.SuiteAppInfo
import com.operations.suitekit.SuiteApps
import com.operations.suitekit.SuiteColors
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The Operations Sandbox home: a phone home screen for a suite that lives inside one app.
 *
 * The metaphor is doing real work, not decoration. Six apps share a process and an installer, so a
 * launcher grid is the honest picture of them — each tile carries the app's own glyph and colour,
 * so "the green one" and "the teal one" mean something before you have read a word. The dock holds
 * what belongs to the container rather than to any app: the settings that paint the whole suite,
 * and the backup that archives all of it at once.
 *
 * Tap a tile to open the app; press and hold to jump to where its colour is chosen.
 */
@Composable
fun SandboxHomeScreen(
    onOpenApp: (AppId) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenBackups: () -> Unit,
    onCustomizeApp: (AppId) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val appearance = LocalSuiteAppearance.current

    // A wallpaper mixed from the suite's own colours: whatever preset is chosen, the home screen is
    // already wearing it before a single app is opened.
    val wallpaper = remember(colors.primary, colors.tertiary, colors.background) {
        Brush.verticalGradient(
            listOf(
                colors.primary.copy(alpha = 0.20f),
                colors.background,
                colors.tertiary.copy(alpha = 0.12f)
            )
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .background(wallpaper)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            StatusHeader()

            Spacer(Modifier.height(28.dp))

            // The grid takes the space between the clock and the dock and scrolls inside it, so a
            // seventh and eighth app can arrive without pushing the dock off a short screen.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                SuiteApps.all.chunked(COLUMNS).forEach { row ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        row.forEach { info ->
                            AppTile(
                                info = info,
                                accent = appearance.accentArgb(info.appId),
                                onOpen = { onOpenApp(info.appId) },
                                onCustomize = { onCustomizeApp(info.appId) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        // Keep a short last row aligned with the columns above it.
                        repeat(COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                    Spacer(Modifier.height(20.dp))
                }
            }

            Dock(onOpenSettings = onOpenSettings, onOpenBackups = onOpenBackups)
        }
    }
}

private const val COLUMNS = 3

/** The clock strip, so the shell reads as a home screen rather than a menu. */
@Composable
private fun StatusHeader() {
    val context = LocalContext.current
    // The platform's own time format, so a 24-hour phone shows 21:41 rather than a 9:41 that could
    // mean either.
    val timeFormat = remember(context) { android.text.format.DateFormat.getTimeFormat(context) }
    val dateFormat = remember { SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()) }
    // Re-reads the clock on the minute; nothing here is worth a ticking second hand.
    val now by produceState(initialValue = Date()) {
        while (true) {
            value = Date()
            delay(30_000L)
        }
    }

    Column {
        Text(
            timeFormat.format(now),
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.Light,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            dateFormat.format(now),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "Operations Sandbox",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
            )
        }
    }
}

/** One app: its glyph in its colour, its name under it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppTile(
    info: SuiteAppInfo,
    accent: Long,
    onOpen: () -> Unit,
    onCustomize: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .combinedClickable(onClick = onOpen, onLongClick = onCustomize)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AppGlyph(icon = SuiteIcons.forApp(info.appId), argb = accent)
        Spacer(Modifier.height(8.dp))
        Text(
            info.label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * The icon itself: a rounded tile lit from the top-left in the app's colour, with a glyph in
 * whichever of ink/snow stays readable on it — so a user-chosen accent can never produce an
 * invisible icon.
 */
@Composable
fun AppGlyph(
    icon: ImageVector,
    argb: Long,
    size: Dp = 62.dp,
    modifier: Modifier = Modifier
) {
    val face = remember(argb) {
        Brush.linearGradient(
            listOf(
                Color(SuiteColors.lighten(argb, 0.22f)),
                Color(SuiteColors.darken(argb, 0.14f))
            )
        )
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size / 3.2f))
            .background(face),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color(SuiteColors.contrastOn(argb)),
            modifier = Modifier.size(size / 2.1f)
        )
    }
}

/** The container's own two entries: everything here is about the suite, not about one app. */
@Composable
private fun Dock(onOpenSettings: () -> Unit, onOpenBackups: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            DockTile(
                icon = Icons.Filled.Settings,
                label = "Settings",
                accent = MaterialTheme.colorScheme.primary.toArgbLong(),
                onClick = onOpenSettings
            )
            DockTile(
                icon = Icons.Filled.Archive,
                label = "Backups",
                accent = MaterialTheme.colorScheme.tertiary.toArgbLong(),
                onClick = onOpenBackups
            )
        }
    }
}

@Composable
private fun DockTile(
    icon: ImageVector,
    label: String,
    accent: Long,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AppGlyph(icon = icon, argb = accent, size = 52.dp)
        Spacer(Modifier.height(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Compose paints in floats; the suite's colour maths speaks `0xAARRGGBB`. */
private fun Color.toArgbLong(): Long = toArgb().toLong() and 0xFFFFFFFFL
