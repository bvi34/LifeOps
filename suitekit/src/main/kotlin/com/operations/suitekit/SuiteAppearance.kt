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
 * One app's icon colours as the settings hold them: hex strings, like [SuitePalette], because that
 * is what the text fields contain — plus whether they are in use at all, so switching them off for
 * an app remembers the pair rather than forgetting it.
 *
 * A blank or unparseable colour is not an error: it falls back to what the app would have used
 * anyway ([SuiteAppearance.defaultIconColors]), so a half-typed `#3B1` never blanks a mark.
 */
data class SuiteIconPaint(
    val line: String = "",
    val highlight: String = "",
    val enabled: Boolean = true
) {
    fun resolve(fallback: SuiteIconColors): SuiteIconColors = SuiteIconColors(
        line = SuiteColors.parseHex(line, fallback.line),
        highlight = SuiteColors.parseHex(highlight, fallback.highlight)
    )
}

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
    val accents: Map<String, String> = emptyMap(),
    /**
     * The tie-breaker when an app brings icon colours of its own *and* has been repainted here.
     * True — the default — means the sandbox wins: a colour chosen in these settings beats the ones
     * the app ships with. False means the app wins and keeps its own icon colours regardless.
     *
     * It only ever decides that one conflict. Icon colours chosen *here* for an app are the
     * sandbox's own choice, so they are drawn either way.
     */
    val sandboxWins: Boolean = true,
    /**
     * Icon colours chosen in these settings, keyed by [AppId.key] as [accents] is. An app with no
     * entry wears whatever it ships (which for all but LifeOps is nothing, and means its mark is
     * tinted with its accent).
     */
    val iconPaints: Map<String, SuiteIconPaint> = emptyMap(),
    /** The Operations Sandbox home screen's backdrop; nothing inside a hosted app reads this. */
    val wallpaper: SuiteWallpaper = SuiteWallpaper()
) {
    /** The accent hex chosen for [appId], or that app's shipped default. */
    fun accentHex(appId: AppId): String =
        accents[appId.key] ?: SuiteColors.toHex(SuiteApps.of(appId).defaultAccent)

    /** The accent [appId] should actually be tinted with — null when identity tints are off. */
    fun accentFor(appId: AppId?): Long? {
        if (appId == null || !appAccentsEnabled) return null
        return SuiteColors.parseHex(accentHex(appId), SuiteApps.of(appId).defaultAccent)
    }

    /** True when the user has repainted [appId] rather than leaving the colour it shipped with. */
    fun hasCustomAccent(appId: AppId): Boolean = accents.containsKey(appId.key)

    /** True when [appId]'s icon colours were chosen here rather than shipped with the app. */
    fun hasCustomIconColors(appId: AppId): Boolean = iconPaints.containsKey(appId.key)

    /**
     * The colours [appId]'s mark is actually drawn in, or null when it is tinted with the accent
     * instead — which is what every app does until it is given a pair.
     *
     * Three sources, in this order: what was chosen here for this app; what the app ships (unless
     * [sandboxWins] and a colour was chosen here for it, which is the conflict that flag settles);
     * nothing.
     */
    fun iconColorsFor(appId: AppId): SuiteIconColors? {
        iconPaints[appId.key]?.let { chosen ->
            return if (chosen.enabled) chosen.resolve(defaultIconColors(appId)) else null
        }
        val shipped = SuiteApps.of(appId).iconColors ?: return null
        return if (sandboxWins && hasCustomAccent(appId)) null else shipped
    }

    /**
     * Where [appId]'s icon colours start from before anyone edits them: what the app ships, or —
     * for the seven that ship none — the suite's own secondary and tertiary for that app. That
     * pairing is not arbitrary: it is the mapping LifeOps' icon has always used (`icon_dial` is a
     * secondary, `icon_check` a tertiary), so an app given icon colours starts out looking like it
     * belongs to the same suite rather than like a colour accident.
     */
    fun defaultIconColors(appId: AppId): SuiteIconColors {
        SuiteApps.of(appId).iconColors?.let { return it }
        val scheme = SuiteThemes.scheme(this, appId)
        return SuiteIconColors(line = scheme.secondary, highlight = scheme.tertiary)
    }

    /**
     * What the settings show for [appId] — the choice made here, or, with none, the colours it
     * would start from and whether they are in use as things stand.
     */
    fun iconPaintFor(appId: AppId): SuiteIconPaint = iconPaints[appId.key] ?: defaultIconColors(appId).let {
        SuiteIconPaint(
            line = SuiteColors.toHex(it.line),
            highlight = SuiteColors.toHex(it.highlight),
            enabled = iconColorsFor(appId) != null
        )
    }

    fun withIconPaint(appId: AppId, paint: SuiteIconPaint): SuiteAppearance =
        copy(iconPaints = iconPaints + (appId.key to paint))

    /** Drop [appId]'s icon colours, so it is back to what it ships (or to being tinted). */
    fun withoutIconPaint(appId: AppId): SuiteAppearance =
        copy(iconPaints = iconPaints - appId.key)

    fun withAccent(appId: AppId, hex: String): SuiteAppearance =
        copy(accents = accents + (appId.key to hex))

    /** Drop [appId]'s override so it falls back to its shipped identity colour. */
    fun withDefaultAccent(appId: AppId): SuiteAppearance =
        copy(accents = accents - appId.key)

    fun withWallpaper(transform: (SuiteWallpaper) -> SuiteWallpaper): SuiteAppearance =
        copy(wallpaper = transform(wallpaper))
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
                accents = (parsed.accents ?: emptyMap()).filterValues { it != null },
                iconPaints = (parsed.iconPaints ?: emptyMap())
                    .filterValues { it != null }
                    .mapValues { (_, paint) -> sanitize(paint) },
                wallpaper = sanitize(parsed.wallpaper)
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

    /**
     * The same guard for the wallpaper: a document written before wallpapers existed has no
     * `wallpaper` key at all, and one written by a newer build may name a design this one does not
     * know — both land on the shipped default rather than on a null field or a blank home screen.
     */
    @Suppress("SENSELESS_COMPARISON", "USELESS_ELVIS")
    private fun sanitize(wallpaper: SuiteWallpaper?): SuiteWallpaper {
        val w = wallpaper ?: return SuiteWallpaper()
        val d = SuiteWallpaper()
        return SuiteWallpaper(
            design = w.design ?: d.design,
            style = w.style ?: d.style,
            angle = w.angle ?: d.angle,
            startColor = w.startColor ?: d.startColor,
            endColor = w.endColor ?: d.endColor,
            glowColor = w.glowColor ?: d.glowColor,
            dim = w.dim.coerceIn(0f, SuiteWallpaper.MAX_DIM)
        )
    }

    /**
     * The same guard for one app's icon colours. A blank colour is *not* repaired here: it means
     * "whatever this app would have used", which [SuiteIconPaint.resolve] already honours.
     */
    @Suppress("SENSELESS_COMPARISON", "USELESS_ELVIS")
    private fun sanitize(paint: SuiteIconPaint): SuiteIconPaint {
        val d = SuiteIconPaint()
        return SuiteIconPaint(
            line = paint.line ?: d.line,
            highlight = paint.highlight ?: d.highlight,
            enabled = paint.enabled
        )
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
