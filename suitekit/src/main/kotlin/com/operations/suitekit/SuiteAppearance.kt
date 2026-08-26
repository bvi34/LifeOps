package com.operations.suitekit

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.operations.backupkit.AppId

/**
 * A named look for the whole suite. These are the presets LifeOps grew — they are declared here now
 * because the Operations Sandbox owns appearance for every hosted app, not just for LifeOps.
 *
 * [CUSTOM] defers to the user's [SuitePalette]; the rest are fixed seeds (see [SuiteThemes]).
 */
enum class SuitePreset(val displayName: String) {
    DEFAULT("Default"),
    BEACON("Beacon"),
    OCEAN("Ocean"),
    SUNSET("Sunset"),
    CUSTOM("Custom");

    companion object {
        /** Tolerant lookup by enum name; anything unknown (or null) falls back to [DEFAULT]. */
        fun from(value: String?): SuitePreset = entries.firstOrNull { it.name == value } ?: DEFAULT
    }
}

/**
 * The colours behind [SuitePreset.CUSTOM], stored as hex strings because that is what the settings
 * text fields hold. Unparseable values are not an error — [SuiteColors.parseHex] substitutes a
 * sensible colour so a half-typed `#3B1` never blanks the screen.
 *
 * Field names and defaults match the shape LifeOps has always written into its backup JSON, so old
 * backups keep restoring into this type unchanged.
 */
data class SuitePalette(
    val primary: String = "#BB86FC",
    val secondary: String = "#03DAC6",
    val tertiary: String = "#3700B3",
    val darkBackground: String = "#121212",
    val lightBackground: String = "#F5F5F5"
)

/**
 * Everything the suite knows about how it should look: one shared preset and mode, the custom
 * palette behind them, and each hosted app's colour identity.
 *
 * This is the single source of truth the Operations Sandbox settings edit and every hosted app
 * reads. An app never decides its own colours any more — it asks for the scheme belonging to its
 * [AppId] and gets the suite's look, tinted with that app's accent.
 *
 * [accents] is keyed by [AppId.key] (never by ordinal or display name) so an accent survives apps
 * being added, renamed or reordered; an app with no entry uses its [SuiteApps] default.
 */
data class SuiteAppearance(
    val preset: SuitePreset = SuitePreset.DEFAULT,
    val darkMode: Boolean = true,
    val palette: SuitePalette = SuitePalette(),
    /** When false, every app renders the unified scheme with no identity tint at all. */
    val appAccentsEnabled: Boolean = true,
    val accents: Map<String, String> = emptyMap()
) {
    /** The accent hex chosen for [appId], or that app's shipped default. */
    fun accentHex(appId: AppId): String =
        accents[appId.key] ?: SuiteColors.toHex(SuiteApps.of(appId).defaultAccent)

    /** The accent [appId] should actually be tinted with — null when identity tints are off. */
    fun accentFor(appId: AppId?): Long? {
        if (appId == null || !appAccentsEnabled) return null
        return SuiteColors.parseHex(accentHex(appId), SuiteApps.of(appId).defaultAccent)
    }

    fun withAccent(appId: AppId, hex: String): SuiteAppearance =
        copy(accents = accents + (appId.key to hex))

    /** Drop [appId]'s override so it falls back to its shipped identity colour. */
    fun withDefaultAccent(appId: AppId): SuiteAppearance =
        copy(accents = accents - appId.key)
}

/**
 * The appearance document's wire codec — one JSON blob, pretty-printed so it is inspectable in a
 * backup or `adb shell run-as` dump. Malformed or partial JSON yields null rather than throwing;
 * the store then keeps the defaults instead of losing the app to a bad preference file.
 */
object SuiteAppearanceCodec {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    fun toJson(appearance: SuiteAppearance): String = gson.toJson(appearance)

    fun fromJson(json: String?): SuiteAppearance? {
        if (json.isNullOrBlank()) return null
        return try {
            val parsed = gson.fromJson(json, SuiteAppearance::class.java) ?: return null
            // Gson fills fields reflectively and happily leaves a non-null-typed field null when the
            // key is absent, so these guards are real at runtime however constant they look to the
            // compiler. A half-written document degrades to defaults instead of crashing an app.
            @Suppress("SENSELESS_COMPARISON", "USELESS_ELVIS")
            parsed.copy(
                preset = parsed.preset ?: SuitePreset.DEFAULT,
                palette = sanitize(parsed.palette),
                accents = (parsed.accents ?: emptyMap()).filterValues { it != null }
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Decode a bare palette object — the shape LifeOps stored under its own `custom_palette`
     * preference before appearance moved to the suite. Used only by that one-time migration.
     */
    fun paletteFromJson(json: String?): SuitePalette? {
        if (json.isNullOrBlank()) return null
        return try {
            sanitize(gson.fromJson(json, SuitePalette::class.java) ?: return null)
        } catch (_: Exception) {
            null
        }
    }

    /** Replace any field Gson left null with the shipped default for that slot. */
    @Suppress("SENSELESS_COMPARISON", "USELESS_ELVIS")
    private fun sanitize(palette: SuitePalette?): SuitePalette {
        val p = palette ?: return SuitePalette()
        val d = SuitePalette()
        return SuitePalette(
            primary = p.primary ?: d.primary,
            secondary = p.secondary ?: d.secondary,
            tertiary = p.tertiary ?: d.tertiary,
            darkBackground = p.darkBackground ?: d.darkBackground,
            lightBackground = p.lightBackground ?: d.lightBackground
        )
    }
}
