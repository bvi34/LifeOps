package com.operations.sandbox.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AddToHomeScreen
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.LockClock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.operations.backupkit.AppId
import com.operations.sandbox.shortcuts.SuiteShortcuts
import com.operations.suite.ui.LocalSuiteAppearance
import com.operations.suite.ui.SuiteAppearanceStore
import com.operations.suite.ui.SuiteIcons
import com.operations.suite.ui.accentArgb
import com.operations.suite.ui.inkColor
import com.operations.suite.ui.rememberSuiteWallpaper
import com.operations.suite.ui.suiteWallpaper
import com.operations.suitekit.SuiteAppInfo
import com.operations.suitekit.SuiteAppearance
import com.operations.suitekit.SuiteApps
import com.operations.suitekit.SuiteColors
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The Operations Sandbox home: a phone home screen for a suite that lives inside one app.
 *
 * The metaphor is doing real work, not decoration. Eight apps share a process and an installer, so a
 * launcher grid is the honest picture of them — each tile carries the app's own glyph and colour,
 * so "the green one" and "the teal one" mean something before you have read a word. The dock holds
 * what belongs to the container rather than to any app: the settings that paint the whole suite,
 * and the backup that archives all of it at once.
 *
 * Tap a tile to open the app; press and hold for the things a launcher's long-press offers — move
 * it, take it off the screen, put it on the *phone's* home screen, or repaint it.
 *
 * The grid is the household's, not the suite's. Eleven apps is past the point where a shipped order
 * is anybody's order, so the tiles are drawn in the arrangement the appearance document holds and
 * an app they never open can be taken off the screen entirely (it keeps its data, its reminders and
 * its place in the order — Settings is where it comes back from). [SuiteHomeLayout] settles what
 * happens when an app arrives in an update, which is that it appears.
 *
 * Above the grid sits the one piece of live information the shell shows on its own: a weather tile
 * for wherever the phone is, on the theory that "is it raining?" is asked more often than any app
 * on this screen is opened. It reads LifeOps' weather cache, so it costs nothing to show.
 *
 * When a launch check has found a newer release, a single line appears under the clock saying so —
 * the whole of the update's presence on this screen. Tapping it opens the Updates tab, which is
 * where anything actually happens; nothing downloads or installs from here.
 *
 * A second line can appear on the same terms, and it is the only place in the suite that says this:
 * **credentials are waiting for the vault.** An app that saves a token while Secrets is shut has it
 * queued in memory rather than filed (see `SecretsAccess`), which is correct and was invisible — the
 * queue does not survive the process, so a household that never happened to open Secrets lost the
 * mirror silently, on exactly the credentials the vault exists to carry onto the next phone. The
 * line says how many and opens Secrets; the tile carries the same count, so it is still visible
 * after the banner has been read past.
 *
 * The backdrop is the user's: a shipped design, their own gradient, or the suite's own colours (the
 * default). Whichever it is, the text on top is written in the ink that wallpaper resolved to, so a
 * bright wallpaper cannot swallow the clock.
 */
@Composable
fun SandboxHomeScreen(
    weather: WeatherWidgetController?,
    onOpenApp: (AppId) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenBackups: () -> Unit,
    onCustomizeApp: (AppId) -> Unit,
    onOpenWeather: () -> Unit,
    /** The tag of a release newer than this build, or null when there is nothing to say. */
    availableUpdateTag: String? = null,
    onOpenUpdates: () -> Unit = {}
) {
    val appearance = LocalSuiteAppearance.current

    // The tile whose long-press menu is open, if any.
    var menuFor by remember { mutableStateOf<AppId?>(null) }

    // Two facts about the vault, or ABSENT/0 on a phone where nothing has ever registered a broker.
    val vault = rememberVaultStatus()

    // Whatever the user chose in the gear. The default still mixes itself from the suite's own
    // colours, so an install that never opens the wallpaper picker looks exactly as it always did.
    val wallpaper = rememberSuiteWallpaper(appearance)
    val ink = wallpaper.inkColor

    // The shell draws edge to edge, so the status and navigation bars sit *on* the wallpaper: their
    // icons have to follow its ink, not the theme's mode. Otherwise Paper under a dark theme puts
    // white icons on a near-white backdrop. Leaving the home screen hands them back to the theme.
    val view = LocalView.current
    val themeIsDark = appearance.darkMode
    DisposableEffect(view, wallpaper.isDark, themeIsDark) {
        val controller = view.context.findActivity()
            ?.window
            ?.let { WindowCompat.getInsetsController(it, view) }
        controller?.isAppearanceLightStatusBars = !wallpaper.isDark
        controller?.isAppearanceLightNavigationBars = !wallpaper.isDark
        onDispose {
            controller?.isAppearanceLightStatusBars = !themeIsDark
            controller?.isAppearanceLightNavigationBars = !themeIsDark
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .suiteWallpaper(wallpaper)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            StatusHeader(ink = ink)

            if (availableUpdateTag != null) {
                Spacer(Modifier.height(12.dp))
                UpdateBanner(tag = availableUpdateTag, ink = ink, onClick = onOpenUpdates)
            }

            if (vault.needsAttention) {
                Spacer(Modifier.height(12.dp))
                NoticeLine(
                    icon = Icons.Filled.LockClock,
                    text = vault.message(),
                    ink = ink,
                    onClick = { onOpenApp(AppId.SECRETS) }
                )
            }

            if (weather != null) {
                LaunchedEffect(weather) { weather.start() }
                Spacer(Modifier.height(16.dp))
                WeatherWidget(controller = weather, onOpenWeather = onOpenWeather)
            }

            Spacer(Modifier.height(24.dp))

            // The grid takes the space between the clock and the dock and scrolls inside it, so a
            // eighth and ninth app can arrive without pushing the dock off a short screen.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                appearance.homeApps.chunked(COLUMNS).forEach { row ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        row.forEach { info ->
                            AppTile(
                                info = info,
                                accent = appearance.accentArgb(info.appId),
                                onOpen = { onOpenApp(info.appId) },
                                onMenu = { menuFor = info.appId },
                                ink = ink,
                                onDark = wallpaper.isDark,
                                // Only Secrets can have one; zero draws nothing.
                                badge = if (info.appId == AppId.SECRETS) vault.pending else null,
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

        menuFor?.let { appId ->
            AppTileMenu(
                appId = appId,
                appearance = appearance,
                onDismiss = { menuFor = null },
                onCustomize = {
                    menuFor = null
                    onCustomizeApp(appId)
                }
            )
        }
    }
}

private const val COLUMNS = 3

/**
 * "v1.4.2 is available — tap to update", written on the wallpaper.
 *
 * A line rather than a card or a dialog. This is a home screen, and the update is the least urgent
 * thing on it: it must be noticeable on the way past and ignorable indefinitely, so it carries no
 * dismiss button — the next release replaces it, installing removes it.
 */
@Composable
private fun UpdateBanner(tag: String, ink: Color, onClick: () -> Unit) {
    NoticeLine(
        icon = Icons.Filled.SystemUpdateAlt,
        text = "$tag is available — tap to update",
        ink = ink,
        onClick = onClick
    )
}

/**
 * One row of "you might want to know", drawn under the clock.
 *
 * Shared by the update banner and the vault's waiting-credentials line because they are the same
 * kind of thing and should not look like two: a fact the shell noticed, one tap to the screen that
 * can act on it, and no way to dismiss it — both conditions end by being dealt with rather than by
 * being acknowledged.
 *
 * Drawn in the wallpaper's own ink over a faint wash of it, so it reads on a light or a dark
 * backdrop without introducing a colour of its own. A notice that wanted attention through colour
 * would be a notice competing with eleven app tiles that have earned theirs.
 *
 * The text wraps to two lines rather than ellipsing: "3 credentials are waiting for your vault —
 * tap to unlock" truncated at the dash is a sentence that has lost the half that says what to do.
 */
@Composable
private fun NoticeLine(icon: ImageVector, text: String, ink: Color, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(ink.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = ink,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** The clock strip, so the shell reads as a home screen rather than a menu. */
@Composable
private fun StatusHeader(ink: Color) {
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
            color = ink
        )
        Text(
            dateFormat.format(now),
            style = MaterialTheme.typography.titleSmall,
            color = ink.copy(alpha = 0.7f)
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(ink.copy(alpha = 0.55f))
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "Operations Sandbox",
                style = MaterialTheme.typography.labelMedium,
                color = ink.copy(alpha = 0.6f)
            )
        }
    }
}

/**
 * One app: its glyph in its colour, its name under it.
 *
 * [badge] is a count drawn on the corner of the mark, in the manner of every launcher the household
 * has ever used. Exactly one app can currently have one — Secrets, when credentials are queued for
 * a vault that is shut — and the parameter is deliberately a plain number rather than a hook other
 * apps can start hanging their own counts on: a home screen where every tile has a red circle is a
 * home screen where none of them mean anything.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppTile(
    info: SuiteAppInfo,
    accent: Long,
    onOpen: () -> Unit,
    onMenu: () -> Unit,
    ink: Color,
    onDark: Boolean,
    badge: Int? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .combinedClickable(onClick = onOpen, onLongClick = onMenu)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box {
            AppGlyph(appId = info.appId, argb = accent, onDark = onDark)
            if (badge != null && badge > 0) {
                TileBadge(count = badge, modifier = Modifier.align(Alignment.TopEnd))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            info.label,
            style = MaterialTheme.typography.labelLarge,
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * The count on the corner of a tile.
 *
 * Drawn in the theme's error colour, which is the one role in the palette that already means "this
 * is not resolved" and is legible against every wallpaper the suite ships — an accent would have to
 * be chosen against eleven app colours and would lose to at least one of them.
 *
 * A count over nine reads as "9+": the number stops being useful past that point, and a three-digit
 * badge on a 54dp mark is a smear.
 */
@Composable
private fun TileBadge(count: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(18.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.error),
        contentAlignment = Alignment.Center
    ) {
        Text(
            if (count > 9) "9+" else count.toString(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onError,
            maxLines = 1
        )
    }
}

/**
 * One hosted app's icon: the mark itself, drawn straight onto whatever is behind it. There is no
 * tile — the drawing is the icon, and the wallpaper (or the settings surface) shows through it.
 *
 * A mark is tinted with the app's accent unless that app has **icon colours** — a pair it ships
 * with, as LifeOps does, or a pair chosen for it in the sandbox settings — in which case it is
 * drawn in those. [SuiteAppearance.iconColorsFor] is where that is decided, the sandbox-wins
 * setting included; nothing about it is settled here.
 */
@Composable
fun AppGlyph(
    appId: AppId,
    argb: Long,
    size: Dp = 54.dp,
    onDark: Boolean = MaterialTheme.colorScheme.surface.luminance() < 0.5f,
    modifier: Modifier = Modifier
) {
    val appearance = LocalSuiteAppearance.current
    val colours = appearance.iconColorsFor(appId)
    val own = remember(appId, onDark, colours) {
        colours?.let { SuiteIcons.inColoursForApp(appId, it, onDark) }
    }
    if (own == null) {
        AppGlyph(
            icon = SuiteIcons.forApp(appId),
            argb = argb,
            size = size,
            onDark = onDark,
            modifier = modifier
        )
    } else {
        Image(
            imageVector = own,
            contentDescription = null,
            modifier = modifier.size(size)
        )
    }
}

/**
 * The single-colour form, and what a mark with no colours of its own gets: the whole vector tinted
 * with [argb], nudged only as far as the backdrop demands ([SuiteColors.fitForMode]) so a dark
 * accent on a dark wallpaper — or a pale one on a light theme — cannot disappear into it.
 */
@Composable
fun AppGlyph(
    icon: ImageVector,
    argb: Long,
    size: Dp = 54.dp,
    onDark: Boolean = MaterialTheme.colorScheme.surface.luminance() < 0.5f,
    modifier: Modifier = Modifier
) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = Color(SuiteColors.fitForMode(argb, onDark)),
        modifier = modifier.size(size)
    )
}

/**
 * What a long-press on a tile offers.
 *
 * A menu, where a long-press used to jump straight to the app's colour. That was one useful action
 * hidden behind a gesture with no way to discover it and no way to find out what else it might do —
 * and there is now more it can do than one action's worth. A sheet says what the gesture is for,
 * which is the whole reason every launcher's long-press opens one.
 *
 * The moves are stated as left and right rather than up and down because that is how the grid
 * flows, and a tile at the end of a row moves into the next one exactly as reading does. Each is
 * offered only when it would change something: a greyed-out row is an honest answer to "can this go
 * further left?" where a row that does nothing is not.
 *
 * Everything here writes through [SuiteAppearanceStore], which is the same store the settings write
 * through, so the grid behind the sheet has already rearranged itself by the time it closes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppTileMenu(
    appId: AppId,
    appearance: SuiteAppearance,
    onDismiss: () -> Unit,
    onCustomize: () -> Unit
) {
    val context = LocalContext.current
    val store = remember(context) { SuiteAppearanceStore.get(context) }
    val info = remember(appId) { SuiteApps.of(appId) }
    // Asked once: whether the phone's launcher does pinning at all. A few do not, and offering a
    // row that silently does nothing would be worse than not offering it.
    val canPin = remember(context) { SuiteShortcuts.isPinSupported(context) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppGlyph(appId = appId, argb = appearance.accentArgb(appId), size = 40.dp)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(info.label, style = MaterialTheme.typography.titleMedium)
                    Text(
                        info.tagline,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(4.dp))

            MenuRow(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                label = "Move left",
                enabled = appearance.canMove(appId, forward = false),
                onClick = { store.moveApp(appId, forward = false) }
            )
            MenuRow(
                icon = Icons.AutoMirrored.Filled.ArrowForward,
                label = "Move right",
                enabled = appearance.canMove(appId, forward = true),
                onClick = { store.moveApp(appId, forward = true) }
            )
            MenuRow(
                icon = Icons.Filled.VisibilityOff,
                label = "Hide from this screen",
                // The last tile standing cannot go: an empty grid reads as a broken app rather
                // than as a choice somebody made. Settings is where hidden apps come back from,
                // and the subtitle is where somebody finds that out.
                subtitle = if (appearance.canHide(appId)) {
                    "Keeps its data and reminders. Settings → Appearance brings it back."
                } else {
                    "The last app on the screen has to stay."
                },
                enabled = appearance.canHide(appId),
                onClick = {
                    store.setHidden(appId, true)
                    onDismiss()
                }
            )
            if (canPin) {
                MenuRow(
                    icon = Icons.Filled.AddToHomeScreen,
                    label = "Add to phone home screen",
                    subtitle = "Its own icon, outside the suite.",
                    onClick = {
                        SuiteShortcuts.pin(context, appId)
                        onDismiss()
                    }
                )
            }
            MenuRow(
                icon = Icons.Filled.Palette,
                label = "Customise colour",
                onClick = onCustomize
            )
        }
    }
}

/** One line of the tile menu. */
@Composable
private fun MenuRow(
    icon: ImageVector,
    label: String,
    subtitle: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val tint = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Column {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = tint)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                        alpha = if (enabled) 1f else 0.38f
                    )
                )
            }
        }
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
        AppGlyph(icon = icon, argb = accent, size = 40.dp)
        Spacer(Modifier.height(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** A composable's context is usually a wrapper around the activity, not the activity itself. */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Compose paints in floats; the suite's colour maths speaks `0xAARRGGBB`. */
private fun Color.toArgbLong(): Long = toArgb().toLong() and 0xFFFFFFFFL
