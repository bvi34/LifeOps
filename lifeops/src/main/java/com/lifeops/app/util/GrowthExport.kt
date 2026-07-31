package com.lifeops.app.util

import kotlin.math.roundToLong

/**
 * Serialisers for the Growth Record. Both consume the same pure primitives as the
 * on-screen renderer:
 *   - CSV  (`week,<aspect names>`; one row per week, hours per column) — round-trips.
 *   - SVG  (filters + gradients intact) — the shareable vector artifact.
 */
object GrowthExport {

    data class ParsedRingsCsv(
        val aspectNames: List<String>,
        /** label -> hours, positionally aligned to [aspectNames]. */
        val weeks: List<Pair<String, List<Double>>>
    )

    // ---- CSV ---------------------------------------------------------------

    fun buildRingsCsv(aspects: List<GrowthRings.AspectRef>, weeks: List<GrowthRings.WeekInput>): String {
        val sb = StringBuilder()
        sb.append(Csv.row(listOf("week") + aspects.map { it.name })).append('\n')
        for (w in weeks) {
            sb.append(Csv.row(listOf(w.label) + aspects.map { formatHours(w.hoursByAspect[it.id] ?: 0.0) })).append('\n')
        }
        return sb.toString()
    }

    /** Parses a rings CSV. Errors speak in the interface's own (lightly sardonic) voice. */
    fun parseRingsCsv(text: String): Result<ParsedRingsCsv> {
        val lines = text.split('\n', '\r').filter { it.isNotBlank() }
        if (lines.isEmpty()) {
            return Result.failure(IllegalArgumentException("Couldn't read that file — it's empty. A record of nothing is still nothing."))
        }
        val header = Csv.parseLine(lines.first())
        if (header.size < 2 || !header.first().trim().equals("week", ignoreCase = true)) {
            return Result.failure(IllegalArgumentException("Couldn't read that file — expected a \"week\" column followed by one column per aspect."))
        }
        val aspectNames = header.drop(1).map { it.trim() }
        val weeks = ArrayList<Pair<String, List<Double>>>()
        for (i in 1 until lines.size) {
            val cells = Csv.parseLine(lines[i])
            val label = cells.firstOrNull()?.trim().orEmpty()
            val hours = aspectNames.indices.map { idx ->
                val cell = cells.getOrNull(idx + 1)?.trim().orEmpty()
                if (cell.isEmpty()) 0.0
                else cell.toDoubleOrNull() ?: return Result.failure(
                    IllegalArgumentException("Couldn't read that file — \"$cell\" in row ${i + 1} isn't a number of hours.")
                )
            }
            weeks.add(label to hours)
        }
        return Result.success(ParsedRingsCsv(aspectNames, weeks))
    }

    // ---- SVG ---------------------------------------------------------------

    fun buildSvg(
        scene: GrowthRings.Scene,
        backgroundHex: String = "#0E0E12",
        seedHex: String = "#FFFFFF"
    ): String {
        val pad = GrowthRings.GLOW_STDDEV * 3 + 4
        val r = scene.contentRadius + pad
        val size = r * 2
        val cx = r
        val cy = r
        val sb = StringBuilder()
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ")
            .append(fmt(size)).append(' ').append(fmt(size)).append("\" width=\"")
            .append(fmt(size)).append("\" height=\"").append(fmt(size)).append("\">\n")
        sb.append("  <defs>\n")
        sb.append("    <radialGradient id=\"bg\" cx=\"50%\" cy=\"50%\" r=\"70%\">\n")
        sb.append("      <stop offset=\"0%\" stop-color=\"").append(GrowthColor.lighten(backgroundHex)).append("\"/>\n")
        sb.append("      <stop offset=\"100%\" stop-color=\"").append(backgroundHex).append("\"/>\n")
        sb.append("    </radialGradient>\n")
        sb.append("    <filter id=\"glow\" x=\"-50%\" y=\"-50%\" width=\"200%\" height=\"200%\">\n")
        sb.append("      <feGaussianBlur stdDeviation=\"").append(fmt(GrowthRings.GLOW_STDDEV)).append("\"/>\n")
        sb.append("    </filter>\n")
        sb.append("  </defs>\n")
        sb.append("  <rect x=\"0\" y=\"0\" width=\"").append(fmt(size)).append("\" height=\"")
            .append(fmt(size)).append("\" fill=\"url(#bg)\"/>\n")

        val glows = scene.glowBands()
        if (glows.isNotEmpty()) {
            sb.append("  <g filter=\"url(#glow)\">\n")
            for (b in glows) sb.append("    ").append(svgBand(cx, cy, b)).append('\n')
            sb.append("  </g>\n")
        }
        sb.append("  <g>\n")
        for (b in scene.fillBands()) sb.append("    ").append(svgBand(cx, cy, b)).append('\n')
        sb.append("  </g>\n")
        sb.append("  <circle cx=\"").append(fmt(cx)).append("\" cy=\"").append(fmt(cy))
            .append("\" r=\"").append(fmt(scene.seedR)).append("\" fill=\"").append(seedHex)
            .append("\" fill-opacity=\"0.9\"/>\n")
        sb.append("</svg>\n")
        return sb.toString()
    }

    private fun svgBand(cx: Double, cy: Double, b: GrowthRings.Band): String {
        val mid = (b.innerR + b.outerR) / 2.0
        val width = b.outerR - b.innerR
        val opacity = if (b.alpha < 1.0) " stroke-opacity=\"${fmt(b.alpha)}\"" else ""
        return "<circle cx=\"${fmt(cx)}\" cy=\"${fmt(cy)}\" r=\"${fmt(mid)}\" fill=\"none\" " +
            "stroke=\"${b.colorHex}\" stroke-width=\"${fmt(width)}\"$opacity/>"
    }

    // ---- helpers -----------------------------------------------------------

    /** Up-to-2-decimal hours, trailing zeros trimmed ("3", "2.5", "0"). */
    fun formatHours(h: Double): String {
        if (h <= 0.0) return "0"
        val rounded = (h * 100.0).roundToLong() / 100.0
        return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString()
        else rounded.toString().trimEnd('0').trimEnd('.')
    }

    private fun fmt(d: Double): String {
        val r = (d * 1000.0).roundToLong() / 1000.0
        return if (r == r.toLong().toDouble()) r.toLong().toString() else r.toString()
    }
}
