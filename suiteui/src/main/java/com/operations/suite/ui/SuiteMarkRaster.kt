package com.operations.suite.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorNode
import androidx.compose.ui.graphics.vector.VectorPath
import com.operations.backupkit.AppId
import com.operations.suitekit.SuiteAppearance
import com.operations.suitekit.SuiteColors

/**
 * The suite's marks, drawn into a bitmap.
 *
 * Compose draws an [ImageVector] and nothing else does, which is fine everywhere in this suite
 * except one place: the *operating system* wants an icon. A launcher shortcut is a `Bitmap` handed
 * to another process, long after any composition has ended, so the mark has to be rasterised by
 * hand — otherwise the eleven hand-drawn silhouettes stop at the edge of the app and every shortcut
 * the household pins looks like the same anonymous square.
 *
 * That is all this does: walk the vector's paths, turn each one's nodes into an `android.graphics`
 * path, and stroke or fill it with the same width, cap, join and alpha the vector declares. The
 * drawing is the same drawing; only the renderer is different.
 *
 * **One deliberate limit.** Group transforms — a rotation or a scale wrapped around part of a mark —
 * are ignored here rather than implemented, because the icon set does not use any and code for a
 * case that never happens is code nobody will maintain. [hasTransformedGroup] is what keeps that
 * honest: `SuiteMarkRasterTest` fails the day a mark starts using one, which is the day this
 * renderer needs the other twenty lines.
 */
object SuiteMarkRaster {

    /**
     * How much of the icon the mark occupies.
     *
     * An adaptive icon is masked to whatever shape the launcher fancies and only the centre
     * 66/108ths of it is guaranteed to survive the crop. Half leaves the drawing comfortably inside
     * that, and the margin is what makes a line drawing read as an icon rather than as something
     * that overflowed its frame.
     */
    private const val MARK_FRACTION = 0.5f

    /**
     * [appId]'s icon for the phone's own launcher: its mark, centred on a field of its accent.
     *
     * A field, where the suite's home screen deliberately draws no tile behind a mark. The reason
     * the home screen can is that it owns what is behind it; a pinned shortcut lands on a wallpaper
     * this app has never seen, where an untinted line drawing is one photograph away from being
     * invisible. So the accent — the colour the app is already known by — becomes the ground, and
     * the mark is drawn in whatever reads on it.
     *
     * Drawn in one colour even for an app that has icon colours of its own: on a field of its own
     * accent, LifeOps' purple dial would be purple on purple. Every mark in the set is built to
     * survive being a single colour ([SuiteGlyphs]), so this costs nothing but the second hue.
     */
    fun launcherIcon(appearance: SuiteAppearance, appId: AppId, sizePx: Int = DEFAULT_SIZE_PX): Bitmap {
        val accent = appearance.accentFor(appId) ?: SuiteColors.parseHex(appearance.accentHex(appId))
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(accent.toInt())
        draw(
            vector = SuiteIcons.forApp(appId),
            canvas = canvas,
            sizePx = sizePx * MARK_FRACTION,
            offsetPx = sizePx * (1f - MARK_FRACTION) / 2f,
            tint = SuiteColors.contrastOn(accent).toInt()
        )
        return bitmap
    }

    /**
     * Draw [vector] at [sizePx], [offsetPx] from the top-left corner, every path forced to [tint].
     *
     * The vector's own viewport is what the coordinates are in, so the scale comes from it rather
     * than from an assumption about 24×24: a mark drawn in a different viewport still lands in the
     * right place.
     */
    private fun draw(vector: ImageVector, canvas: Canvas, sizePx: Float, offsetPx: Float, tint: Int) {
        val scale = Matrix().apply {
            setScale(sizePx / vector.viewportWidth, sizePx / vector.viewportHeight)
            postTranslate(offsetPx, offsetPx)
        }
        val strokeScale = sizePx / vector.viewportWidth
        pathsOf(vector).forEach { spec ->
            val path = PathParser().addPathNodes(spec.pathData).toPath().asAndroidPath()
            path.transform(scale)
            spec.fill?.let { fill ->
                canvas.drawPath(path, paint(tint, spec.fillAlpha, fill.solidAlpha()).apply {
                    style = Paint.Style.FILL
                })
            }
            spec.stroke?.let { stroke ->
                canvas.drawPath(path, paint(tint, spec.strokeAlpha, stroke.solidAlpha()).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = spec.strokeLineWidth * strokeScale
                    strokeCap = spec.strokeLineCap.toAndroid()
                    strokeJoin = spec.strokeLineJoin.toAndroid()
                })
            }
        }
    }

    /**
     * A mark's own alpha is how its secondary detail stays subordinate — a book's text lines at
     * 0.5 against its cover at 1. The vector carries that in two places at once (the path's alpha
     * and the brush's own), and both have to survive being recoloured or the detail comes forward
     * and the mark flattens.
     */
    private fun paint(tint: Int, pathAlpha: Float, brushAlpha: Float): Paint = Paint().apply {
        isAntiAlias = true
        color = tint
        alpha = (255 * pathAlpha.coerceIn(0f, 1f) * brushAlpha.coerceIn(0f, 1f)).toInt()
    }

    /** A solid brush's own alpha, or 1 for anything else — nothing in the set uses a gradient. */
    private fun Brush.solidAlpha(): Float =
        (this as? SolidColor)?.value?.alpha ?: 1f

    private fun StrokeCap.toAndroid(): Paint.Cap = when (this) {
        StrokeCap.Round -> Paint.Cap.ROUND
        StrokeCap.Square -> Paint.Cap.SQUARE
        else -> Paint.Cap.BUTT
    }

    private fun StrokeJoin.toAndroid(): Paint.Join = when (this) {
        StrokeJoin.Round -> Paint.Join.ROUND
        StrokeJoin.Bevel -> Paint.Join.BEVEL
        else -> Paint.Join.MITER
    }

    /** Every drawn path in [vector], in draw order, groups flattened. */
    internal fun pathsOf(vector: ImageVector): List<VectorPath> = buildList { collect(vector.root, this) }

    private fun collect(node: VectorNode, into: MutableList<VectorPath>) {
        when (node) {
            is VectorPath -> into += node
            is VectorGroup -> node.forEach { collect(it, into) }
        }
    }

    /**
     * Does [vector] wrap any of its paths in a group that moves, turns, scales or clips them?
     *
     * Nothing in the icon set does, and this renderer ignores such a group rather than honouring
     * it, so a mark that started using one would be drawn wrong and silently. The test asserts this
     * is false for every mark in the suite; when it stops being false, that is the signal to teach
     * [draw] about group transforms rather than to relax the assertion.
     */
    internal fun hasTransformedGroup(vector: ImageVector): Boolean = transformed(vector.root)

    private fun transformed(node: VectorNode): Boolean = when (node) {
        is VectorPath -> false
        is VectorGroup -> node.rotation != 0f ||
            node.scaleX != 1f || node.scaleY != 1f ||
            node.translationX != 0f || node.translationY != 0f ||
            node.clipPathData.isNotEmpty() ||
            node.any { transformed(it) }
        else -> false
    }

    /**
     * 192px square — a comfortable size for every density the suite runs on, and small enough that
     * eleven of them cost less memory than one screenshot. The launcher scales what it is given.
     */
    const val DEFAULT_SIZE_PX = 192
}
