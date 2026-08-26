package com.operations.suite.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.operations.backupkit.AppId
import com.operations.suitekit.SuiteAppearance
import com.operations.suitekit.SuiteApps
import com.operations.suitekit.SuiteColors
import com.operations.suitekit.SuiteScheme
import com.operations.suitekit.SuiteThemes

/** The appearance in force, for the rare screen that needs to know more than its colours. */
val LocalSuiteAppearance = staticCompositionLocalOf { SuiteAppearance() }

/** The app whose identity this subtree is themed with; null inside the sandbox shell itself. */
val LocalSuiteAppId = staticCompositionLocalOf<AppId?> { null }

/**
 * The one theme in the suite. An app wraps its content in `SuiteTheme(AppId.HEALTH) { … }` and gets
 * the look chosen in the Operations Sandbox — preset, light/dark, custom palette — carrying its own
 * accent. No app picks colours any more; it names itself and the suite answers.
 *
 * Because the appearance comes from a [SuiteAppearanceStore] flow, editing it in the sandbox
 * repaints every hosted screen that is currently composed, immediately.
 */
@Composable
fun SuiteTheme(appId: AppId? = null, content: @Composable () -> Unit) {
    val store = SuiteAppearanceStore.get(LocalContext.current)
    val appearance by store.state.collectAsState()
    SuiteTheme(appearance = appearance, appId = appId, content = content)
}

/** The stateless form — used by previews and by the settings screen's live theme preview. */
@Composable
fun SuiteTheme(appearance: SuiteAppearance, appId: AppId? = null, content: @Composable () -> Unit) {
    val colorScheme = remember(appearance, appId) {
        SuiteThemes.scheme(appearance, appId).toColorScheme(appearance.darkMode)
    }
    CompositionLocalProvider(
        LocalSuiteAppearance provides appearance,
        LocalSuiteAppId provides appId
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography(),
            content = content
        )
    }
}

/**
 * A hosted app's identity colour as `0xAARRGGBB` — what a tile, badge or accent stripe is painted
 * from. This is the colour the app is *known by*, so it is read straight from the chosen accent and
 * ignores [SuiteAppearance.appAccentsEnabled]: turning identity tints off stops apps from being
 * repainted inside, it does not make every icon on the home screen the same colour.
 */
fun SuiteAppearance.accentArgb(appId: AppId): Long =
    SuiteColors.parseHex(accentHex(appId), SuiteApps.of(appId).defaultAccent)

/** The same colour, for Compose. */
fun SuiteAppearance.accentColor(appId: AppId): Color = Color(accentArgb(appId))

/** `0xAARRGGBB` → Compose. The scheme is resolved on the JVM; this is the whole Android half. */
fun Long.toSuiteColor(): Color = Color(this)

private fun SuiteScheme.toColorScheme(dark: Boolean): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = primary.toSuiteColor(),
        onPrimary = onPrimary.toSuiteColor(),
        primaryContainer = primaryContainer.toSuiteColor(),
        onPrimaryContainer = onPrimaryContainer.toSuiteColor(),
        secondary = secondary.toSuiteColor(),
        onSecondary = onSecondary.toSuiteColor(),
        secondaryContainer = secondaryContainer.toSuiteColor(),
        onSecondaryContainer = onSecondaryContainer.toSuiteColor(),
        tertiary = tertiary.toSuiteColor(),
        onTertiary = onTertiary.toSuiteColor(),
        background = background.toSuiteColor(),
        onBackground = onBackground.toSuiteColor(),
        surface = surface.toSuiteColor(),
        onSurface = onSurface.toSuiteColor(),
        surfaceVariant = surfaceVariant.toSuiteColor(),
        onSurfaceVariant = onSurfaceVariant.toSuiteColor(),
        // Material draws menus, sheets and cards on the container roles; keep them on the suite's
        // surfaces so an elevated sheet cannot fall back to a stock purple in a themed app.
        surfaceContainer = surfaceVariant.toSuiteColor(),
        surfaceContainerHigh = surfaceVariant.toSuiteColor(),
        surfaceContainerHighest = surfaceVariant.toSuiteColor(),
        surfaceContainerLow = surface.toSuiteColor(),
        surfaceContainerLowest = background.toSuiteColor(),
        // Material tints elevated surfaces with this; left at its stock value it drags a foreign
        // purple into every card the suite raises.
        surfaceTint = primary.toSuiteColor(),
        inverseSurface = onSurface.toSuiteColor(),
        inverseOnSurface = surface.toSuiteColor(),
        outline = outline.toSuiteColor(),
        outlineVariant = surfaceVariant.toSuiteColor(),
        error = error.toSuiteColor(),
        onError = onError.toSuiteColor()
    )
}
