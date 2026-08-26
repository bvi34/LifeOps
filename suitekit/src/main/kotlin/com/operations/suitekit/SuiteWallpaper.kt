package com.operations.suitekit

/**
 * The Operations Sandbox home screen's backdrop.
 *
 * The launcher is the one screen in the suite that is nobody's app, so it is the one screen a user
 * can decorate without arguing with a hosted app's layout. This file decides *what is painted*;
 * :suiteui only turns the decision into a Compose brush, exactly as :suitekit decides colour
 * schemes and :suiteui only hands them to Material.
 *
 * Three moving parts:
 *  - [WallpaperDesign] — the shipped looks, plus [WallpaperDesign.THEME] (follow the suite's own
 *    colours, which is what the home screen always did) and [WallpaperDesign.CUSTOM] (the user's).
 *  - [SuiteWallpaper] — what the user chose: a design, the custom knobs behind it, and a dim that
 *    applies to every design so a busy backdrop can be quietened until the clock reads cleanly.
 *  - [WallpaperSpec] — the resolved answer: a base colour, gradient layers over it, a veil, and the
 *    ink that stays readable on the result. Nothing downstream picks a colour of its own.
 */

/** How a wallpaper's colours are laid out. */
enum class WallpaperStyle(val displayName: String) {
    /** One flat colour. */
    SOLID("Solid"),

    /** Two colours running along a [WallpaperAngle]. */
    LINEAR("Gradient"),

    /** A single soft glow over a flat field. */
    RADIAL("Glow"),

    /** Several offset glows over a flat field — the "northern lights" look. */
    AURORA("Aurora")
}

/**
 * The direction a linear gradient runs, as fractions of the screen box. Kept as an enum rather than
 * free degrees: five directions cover every wallpaper anyone actually wants, and each one is a
 * tappable chip instead of a number to type.
 */
enum class WallpaperAngle(
    val displayName: String,
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float
) {
    TOP_BOTTOM("Down", 0.5f, 0f, 0.5f, 1f),
    BOTTOM_TOP("Up", 0.5f, 1f, 0.5f, 0f),
    LEFT_RIGHT("Across", 0f, 0.5f, 1f, 0.5f),
    DIAGONAL_DOWN("Diagonal", 0f, 0f, 1f, 1f),
    DIAGONAL_UP("Rising", 0f, 1f, 1f, 0f);

    companion object {
        fun from(value: String?): WallpaperAngle = entries.firstOrNull { it.name == value } ?: TOP_BOTTOM
    }
}

/**
 * The shipped backdrops. [THEME] is the default and the one that is not a fixed picture: it mixes
 * itself from whatever preset and mode the suite is wearing, so choosing Ocean repaints the home
 * screen too. The rest are deliberate designs that hold their look whatever the theme does — which
 * is the point of choosing one — and [CUSTOM] hands the same controls to the user.
 */
enum class WallpaperDesign(val displayName: String, val description: String) {
    THEME("Theme", "Mixed from the suite's own preset — repaints when the theme does"),
    MIDNIGHT("Midnight", "Deep indigo falling to near-black"),
    NEBULA("Nebula", "A violet glow off a dark field"),
    AURORA("Aurora", "Green and teal lights over a cold night"),
    TIDE("Tide", "Ocean blue, deepening downward"),
    EMBER("Ember", "Warm coals rising from the bottom edge"),
    SUNRISE("Sunrise", "Amber into rose, corner to corner"),
    FOREST("Forest", "Pine and moss under a dark canopy"),
    GRAPHITE("Graphite", "Flat neutral slate — the quietest option"),
    PAPER("Paper", "Warm off-white, for light mode"),
    DAYLIGHT("Daylight", "Pale sky blue washing downward"),
    CUSTOM("Custom", "Your own colours, style and direction");

    /** True when this design ignores the suite's preset and paints its own fixed colours. */
    val isFixed: Boolean get() = this != THEME && this != CUSTOM

    companion object {
        /** Tolerant lookup by enum name; anything unknown (or null) falls back to [THEME]. */
        fun from(value: String?): WallpaperDesign = entries.firstOrNull { it.name == value } ?: THEME
    }
}

/**
 * The user's wallpaper choice. [style], [angle] and the three colours only matter under
 * [WallpaperDesign.CUSTOM] — they are kept whatever design is showing, so trying a shipped design
 * and coming back to Custom does not lose the colours that were typed there.
 *
 * [dim] applies to every design: a veil of black (or, over a light backdrop, of the same black at a
 * lower weight) drawn last, so any wallpaper can be pushed back behind the clock and the labels.
 */
data class SuiteWallpaper(
    val design: WallpaperDesign = WallpaperDesign.THEME,
    val style: WallpaperStyle = WallpaperStyle.LINEAR,
    val angle: WallpaperAngle = WallpaperAngle.TOP_BOTTOM,
    /** The first colour: the whole field for SOLID, the gradient's start, the glows' backdrop. */
    val startColor: String = "#3B1F5E",
    /** The gradient's far end; unused by SOLID. */
    val endColor: String = "#0B1020",
    /** What RADIAL and AURORA light the field with; unused by SOLID and LINEAR. */
    val glowColor: String = "#7F5AF0",
    /** How far the backdrop is pushed back, `0f..1f`. Stored coerced; see [MAX_DIM]. */
    val dim: Float = 0f
) {
    companion object {
        /** A dim past this stops being "quieter" and starts being "black", so the UI caps here. */
        const val MAX_DIM = 0.7f
    }
}

/** One colour of a gradient, at a fraction along it. */
data class WallpaperStop(val color: Long, val position: Float)

/** Whether a layer's stops run along a line or out from a point. */
enum class WallpaperShape { LINEAR, RADIAL }

/**
 * One painted layer, in fractions of the screen box so the renderer needs no measurement of its
 * own. Layers are drawn in order over [WallpaperSpec.base]; translucent stops are the norm, since
 * that is how a glow sits on a field without hiding it.
 */
data class WallpaperLayer(
    val shape: WallpaperShape,
    val stops: List<WallpaperStop>,
    val startX: Float = 0.5f,
    val startY: Float = 0f,
    val endX: Float = 0.5f,
    val endY: Float = 1f,
    val centerX: Float = 0.5f,
    val centerY: Float = 0.5f,
    /** Radius as a fraction of the box's longer side. */
    val radius: Float = 0.8f
)

/**
 * A wallpaper resolved down to what a renderer needs: fill with [base], draw [layers] in order,
 * draw [veil] over the lot, and write text in [ink].
 *
 * [ink] is computed from the colours that actually end up on screen, so a light design gets dark
 * text and a dimmed one gets light text without anybody choosing it — a user cannot pick a
 * wallpaper that hides their own clock.
 */
data class WallpaperSpec(
    val base: Long,
    val layers: List<WallpaperLayer>,
    /** `0xAARRGGBB`; fully transparent when nothing is dimmed. */
    val veil: Long,
    val ink: Long
) {
    /** True when the wallpaper reads as dark — what a caller needs for status-bar icons. */
    val isDark: Boolean get() = ink == SuiteColors.SNOW
}

/** Turns a [SuiteWallpaper] into the [WallpaperSpec] the home screen paints. */
object SuiteWallpapers {

    /** The designs in the order the picker lays them out. */
    val designs: List<WallpaperDesign> = WallpaperDesign.entries.toList()

    /** The wallpaper [appearance] asks for, mixed against the scheme the shell renders under. */
    fun spec(appearance: SuiteAppearance, scheme: SuiteScheme): WallpaperSpec =
        spec(appearance.wallpaper, scheme)

    fun spec(wallpaper: SuiteWallpaper, scheme: SuiteScheme): WallpaperSpec {
        val painted = when (wallpaper.design) {
            WallpaperDesign.THEME -> themed(scheme)
            WallpaperDesign.CUSTOM -> custom(wallpaper)
            else -> fixed(wallpaper.design)
        }
        return dimmed(painted, wallpaper.dim)
    }

    /**
     * A preview of [design] without disturbing the stored choice — what a picker card paints. The
     * custom knobs come from [wallpaper], so the Custom card previews the user's actual colours.
     */
    fun preview(design: WallpaperDesign, wallpaper: SuiteWallpaper, scheme: SuiteScheme): WallpaperSpec =
        spec(wallpaper.copy(design = design), scheme)

    // -----------------------------------------------------------------------------------------

    /** The home screen's original backdrop: the suite's own colours, washed over its background. */
    private fun themed(scheme: SuiteScheme): Painted = Painted(
        base = scheme.background,
        layers = listOf(
            WallpaperLayer(
                shape = WallpaperShape.LINEAR,
                stops = listOf(
                    WallpaperStop(withAlpha(scheme.primary, 0.20f), 0f),
                    WallpaperStop(withAlpha(scheme.background, 0f), 0.55f),
                    WallpaperStop(withAlpha(scheme.tertiary, 0.12f), 1f)
                ),
                startX = 0.5f, startY = 0f, endX = 0.5f, endY = 1f
            )
        )
    )

    /** The user's own: the same four styles the shipped designs are built from. */
    private fun custom(wallpaper: SuiteWallpaper): Painted {
        val start = SuiteColors.parseHex(wallpaper.startColor, 0xFF3B1F5EL)
        val end = SuiteColors.parseHex(wallpaper.endColor, 0xFF0B1020L)
        val glow = SuiteColors.parseHex(wallpaper.glowColor, 0xFF7F5AF0L)
        return when (wallpaper.style) {
            WallpaperStyle.SOLID -> solid(start)
            WallpaperStyle.LINEAR -> linear(start, end, wallpaper.angle)
            WallpaperStyle.RADIAL -> radial(start, glow)
            WallpaperStyle.AURORA -> aurora(start, glow, end)
        }
    }

    /**
     * The shipped designs, spelled out. Each is a real choice of colours rather than a filter over
     * the theme, which is why picking one survives changing the preset.
     */
    private fun fixed(design: WallpaperDesign): Painted = when (design) {
        WallpaperDesign.MIDNIGHT ->
            linear(0xFF231B4EL, 0xFF07070FL, WallpaperAngle.TOP_BOTTOM)
        WallpaperDesign.NEBULA ->
            radial(0xFF0C0A1AL, 0xFF7F5AF0L)
        WallpaperDesign.AURORA ->
            aurora(0xFF071A24L, 0xFF35D6A4L, 0xFF3B82F6L)
        WallpaperDesign.TIDE ->
            linear(0xFF0F5C8CL, 0xFF04121FL, WallpaperAngle.TOP_BOTTOM)
        WallpaperDesign.EMBER ->
            linear(0xFF1A0B06L, 0xFFB4471AL, WallpaperAngle.TOP_BOTTOM)
        WallpaperDesign.SUNRISE ->
            linear(0xFFF2A65AL, 0xFFB4436CL, WallpaperAngle.DIAGONAL_DOWN)
        WallpaperDesign.FOREST ->
            aurora(0xFF0A1A12L, 0xFF2F855AL, 0xFF6BA368L)
        WallpaperDesign.GRAPHITE ->
            solid(0xFF1B1F24L)
        WallpaperDesign.PAPER ->
            linear(0xFFFDF8F0L, 0xFFEBDFCEL, WallpaperAngle.TOP_BOTTOM)
        WallpaperDesign.DAYLIGHT ->
            linear(0xFFDCEBFAL, 0xFFF6FAFDL, WallpaperAngle.TOP_BOTTOM)
        // Not reachable: THEME and CUSTOM are handled before this call. A flat field is still a
        // usable answer, so an added design cannot ship an empty screen.
        WallpaperDesign.THEME, WallpaperDesign.CUSTOM -> solid(0xFF121212L)
    }

    // -- the four styles ------------------------------------------------------------------------

    private fun solid(color: Long): Painted = Painted(base = opaque(color), layers = emptyList())

    private fun linear(from: Long, to: Long, angle: WallpaperAngle): Painted = Painted(
        base = opaque(from),
        layers = listOf(
            WallpaperLayer(
                shape = WallpaperShape.LINEAR,
                stops = listOf(
                    WallpaperStop(opaque(from), 0f),
                    WallpaperStop(opaque(to), 1f)
                ),
                startX = angle.startX, startY = angle.startY, endX = angle.endX, endY = angle.endY
            )
        ),
        // A two-colour gradient reads as the average of its ends, not as either one.
        representative = SuiteColors.blend(opaque(from), opaque(to), 0.5f)
    )

    private fun radial(field: Long, glow: Long): Painted = Painted(
        base = opaque(field),
        layers = listOf(
            glowLayer(glow, centerX = 0.5f, centerY = 0.28f, radius = 0.95f, strength = 0.55f)
        ),
        representative = SuiteColors.blend(opaque(field), opaque(glow), 0.28f)
    )

    private fun aurora(field: Long, first: Long, second: Long): Painted = Painted(
        base = opaque(field),
        layers = listOf(
            glowLayer(first, centerX = 0.18f, centerY = 0.18f, radius = 0.85f, strength = 0.48f),
            glowLayer(second, centerX = 0.86f, centerY = 0.46f, radius = 0.75f, strength = 0.40f),
            glowLayer(first, centerX = 0.42f, centerY = 0.92f, radius = 0.70f, strength = 0.28f)
        ),
        representative = SuiteColors.blend(
            SuiteColors.blend(opaque(field), opaque(first), 0.24f),
            opaque(second),
            0.16f
        )
    )

    /** One glow: the colour at [strength] in the middle, fading to nothing at the rim. */
    private fun glowLayer(
        color: Long,
        centerX: Float,
        centerY: Float,
        radius: Float,
        strength: Float
    ) = WallpaperLayer(
        shape = WallpaperShape.RADIAL,
        stops = listOf(
            WallpaperStop(withAlpha(color, strength), 0f),
            WallpaperStop(withAlpha(color, strength * 0.45f), 0.55f),
            WallpaperStop(withAlpha(color, 0f), 1f)
        ),
        centerX = centerX, centerY = centerY, radius = radius
    )

    // -- finishing ------------------------------------------------------------------------------

    /** What a style produced, before the dim and before the ink is decided. */
    private data class Painted(
        val base: Long,
        val layers: List<WallpaperLayer>,
        /** The single colour this wallpaper reads as; defaults to its base. */
        val representative: Long = base
    )

    /** Apply the dim, then pick the ink from what the dim actually left on screen. */
    private fun dimmed(painted: Painted, dim: Float): WallpaperSpec {
        val amount = dim.coerceIn(0f, SuiteWallpaper.MAX_DIM)
        val veil = if (amount <= 0f) 0x00000000L else SuiteColors.argb((amount * 255).toInt(), 0, 0, 0)
        val onScreen = SuiteColors.darken(painted.representative, amount)
        return WallpaperSpec(
            base = painted.base,
            layers = painted.layers,
            veil = veil,
            ink = SuiteColors.contrastOn(onScreen)
        )
    }

    /** Force full opacity: a wallpaper's own colours are a backdrop, never a see-through. */
    private fun opaque(color: Long): Long = color or 0xFF000000L

    /** The same colour at [fraction] opacity — how a glow or a wash is expressed as a stop. */
    private fun withAlpha(color: Long, fraction: Float): Long = SuiteColors.argb(
        (fraction.coerceIn(0f, 1f) * 255).toInt(),
        SuiteColors.red(color),
        SuiteColors.green(color),
        SuiteColors.blue(color)
    )
}
