package com.operations.suitekit

import com.operations.backupkit.AppId

/**
 * A fully resolved colour scheme as plain `0xAARRGGBB` longs — the handover between the suite's
 * appearance rules (here, on the JVM) and Material 3 (in :suiteui). Every slot is decided; nothing
 * downstream picks a colour of its own.
 */
data class SuiteScheme(
    val primary: Long,
    val onPrimary: Long,
    val primaryContainer: Long,
    val onPrimaryContainer: Long,
    val secondary: Long,
    val onSecondary: Long,
    val secondaryContainer: Long,
    val onSecondaryContainer: Long,
    val tertiary: Long,
    val onTertiary: Long,
    val background: Long,
    val onBackground: Long,
    val surface: Long,
    val onSurface: Long,
    val surfaceVariant: Long,
    val onSurfaceVariant: Long,
    val outline: Long,
    val error: Long,
    val onError: Long
)

/**
 * How the suite decides what an app looks like.
 *
 * Two inputs, in this order:
 *  1. the **shared look** — one preset (or the user's custom palette) and one light/dark mode,
 *     chosen once in the Operations Sandbox and obeyed by every app. This is what makes the suite
 *     feel like one product rather than six.
 *  2. the **app's accent** — its colour identity, also chosen in the sandbox. It repaints the
 *     primary/secondary roles and washes the merest tint into the surfaces, so Health still reads
 *     as Health and Logistics as Logistics without either leaving the shared layout.
 *
 * Turning accents off ([SuiteAppearance.appAccentsEnabled]) leaves step 2 out entirely and every
 * app renders the identical scheme.
 */
object SuiteThemes {

    /** The colours a preset is built from; everything else in the scheme is derived from these. */
    private data class Seed(
        val primary: Long,
        val secondary: Long,
        val tertiary: Long,
        val background: Long,
        val surface: Long,
        val surfaceVariant: Long
    )

    /** How strongly an accent stains the background/surface — a wash, not a repaint. */
    private const val SURFACE_TINT_DARK = 0.10f
    private const val SURFACE_TINT_LIGHT = 0.06f

    /** The scheme [appId] should render under [appearance]; pass a null id for the sandbox shell. */
    fun scheme(appearance: SuiteAppearance, appId: AppId?): SuiteScheme = scheme(
        preset = appearance.preset,
        dark = appearance.darkMode,
        palette = appearance.palette,
        accent = appearance.accentFor(appId)
    )

    fun scheme(
        preset: SuitePreset,
        dark: Boolean,
        palette: SuitePalette = SuitePalette(),
        accent: Long? = null
    ): SuiteScheme {
        val seed = seedFor(preset, dark, palette)
        val tinted = if (accent == null) seed else tint(seed, accent, dark)
        return build(tinted, dark)
    }

    /** Fold an app's identity colour into the shared seed. */
    private fun tint(seed: Seed, accent: Long, dark: Boolean): Seed {
        val primary = SuiteColors.fitForMode(accent, dark)
        val secondary = if (dark) SuiteColors.lighten(primary, 0.22f) else SuiteColors.darken(primary, 0.18f)
        val wash = if (dark) SURFACE_TINT_DARK else SURFACE_TINT_LIGHT
        return seed.copy(
            primary = primary,
            secondary = secondary,
            // The tertiary stays the preset's: one colour in every app that is the *suite's*, not
            // the app's, so a themed screen never loses the thread back to the shared look.
            background = SuiteColors.blend(seed.background, accent, wash),
            surface = SuiteColors.blend(seed.surface, accent, wash),
            surfaceVariant = SuiteColors.blend(seed.surfaceVariant, accent, wash)
        )
    }

    /** Derive the full Material role set from a seed. */
    private fun build(seed: Seed, dark: Boolean): SuiteScheme {
        val onSurface = SuiteColors.contrastOn(seed.surface)
        val primaryContainer =
            if (dark) SuiteColors.darken(seed.primary, 0.55f) else SuiteColors.lighten(seed.primary, 0.80f)
        val secondaryContainer =
            if (dark) SuiteColors.darken(seed.secondary, 0.60f) else SuiteColors.lighten(seed.secondary, 0.82f)
        val error = if (dark) 0xFFFC8181L else 0xFFC53030L
        return SuiteScheme(
            primary = seed.primary,
            onPrimary = SuiteColors.contrastOn(seed.primary),
            primaryContainer = primaryContainer,
            onPrimaryContainer = SuiteColors.contrastOn(primaryContainer),
            secondary = seed.secondary,
            onSecondary = SuiteColors.contrastOn(seed.secondary),
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = SuiteColors.contrastOn(secondaryContainer),
            tertiary = seed.tertiary,
            onTertiary = SuiteColors.contrastOn(seed.tertiary),
            background = seed.background,
            onBackground = SuiteColors.contrastOn(seed.background),
            surface = seed.surface,
            onSurface = onSurface,
            surfaceVariant = seed.surfaceVariant,
            onSurfaceVariant = SuiteColors.blend(SuiteColors.contrastOn(seed.surfaceVariant), seed.surfaceVariant, 0.28f),
            outline = SuiteColors.blend(onSurface, seed.surface, 0.55f),
            error = error,
            onError = SuiteColors.contrastOn(error)
        )
    }

    /**
     * The preset seeds. DEFAULT/BEACON/OCEAN/SUNSET are the palettes LifeOps shipped, kept
     * value-for-value so an existing install's look survives the move to a suite-wide theme.
     */
    private fun seedFor(preset: SuitePreset, dark: Boolean, palette: SuitePalette): Seed = when (preset) {
        SuitePreset.DEFAULT -> if (dark) Seed(
            primary = 0xFFBB86FCL, secondary = 0xFF03DAC6L, tertiary = 0xFF7F5AF0L,
            background = 0xFF121212L, surface = 0xFF1E1E1EL, surfaceVariant = 0xFF2A2A2AL
        ) else Seed(
            primary = 0xFF6200EEL, secondary = 0xFF03DAC6L, tertiary = 0xFF018786L,
            background = 0xFFF5F5F5L, surface = 0xFFFFFFFFL, surfaceVariant = 0xFFE8E8E8L
        )
        SuitePreset.BEACON -> if (dark) Seed(
            primary = 0xFFC8B3E0L, secondary = 0xFF9B72CFL, tertiary = 0xFFFFB74DL,
            background = 0xFF111827L, surface = 0xFF1C2333L, surfaceVariant = 0xFF253045L
        ) else Seed(
            primary = 0xFF3B1F5EL, secondary = 0xFF6B4C9AL, tertiary = 0xFFE65100L,
            background = 0xFFF9FAFBL, surface = 0xFFFFFFFFL, surfaceVariant = 0xFFEDE8F5L
        )
        SuitePreset.OCEAN -> if (dark) Seed(
            primary = 0xFF90CAF9L, secondary = 0xFF4FC3F7L, tertiary = 0xFF80DEEAL,
            background = 0xFF0A1628L, surface = 0xFF0D1B2AL, surfaceVariant = 0xFF152035L
        ) else Seed(
            primary = 0xFF0277BDL, secondary = 0xFF0288D1L, tertiary = 0xFF00838FL,
            background = 0xFFF0F7FFL, surface = 0xFFFFFFFFL, surfaceVariant = 0xFFD1E4FFL
        )
        SuitePreset.SUNSET -> if (dark) Seed(
            primary = 0xFFFFAB40L, secondary = 0xFFFF7043L, tertiary = 0xFFFFCC02L,
            background = 0xFF1A0F00L, surface = 0xFF261500L, surfaceVariant = 0xFF341C00L
        ) else Seed(
            primary = 0xFFBF360CL, secondary = 0xFFE65100L, tertiary = 0xFFF57F17L,
            background = 0xFFFFF8F0L, surface = 0xFFFFFFFFL, surfaceVariant = 0xFFFFECDCL
        )
        SuitePreset.CUSTOM -> {
            val primary = SuiteColors.parseHex(palette.primary)
            val secondary = SuiteColors.parseHex(palette.secondary, primary)
            val tertiary = SuiteColors.parseHex(palette.tertiary, secondary)
            if (dark) {
                val background = SuiteColors.parseHex(palette.darkBackground, 0xFF121212L)
                Seed(
                    primary = primary, secondary = secondary, tertiary = tertiary,
                    background = background,
                    surface = SuiteColors.lighten(background, 0.06f),
                    surfaceVariant = SuiteColors.lighten(background, 0.12f)
                )
            } else {
                val background = SuiteColors.parseHex(palette.lightBackground, 0xFFF5F5F5L)
                Seed(
                    primary = primary, secondary = secondary, tertiary = tertiary,
                    background = background,
                    surface = 0xFFFFFFFFL,
                    surfaceVariant = SuiteColors.darken(background, 0.04f)
                )
            }
        }
    }
}
