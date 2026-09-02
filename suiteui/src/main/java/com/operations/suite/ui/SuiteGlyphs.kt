package com.operations.suite.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import com.operations.suitekit.SuiteIconColors

/**
 * The suite's own icon set: one hand-drawn mark per hosted app.
 *
 * These replaced a row of stock Material icons, for two reasons. The first is that the stock icons
 * were *categories* — a dashboard, a box, a group of people — when what the home screen needs is a
 * picture of the work: the thing you'd draw if someone asked what the app is for. The second is
 * plain legibility. Four of the seven were rectangles with something inside them, so at tile size
 * the grid read as four grey boxes and a book, and the colour was doing all the identifying. Every
 * mark here has a silhouette no other one has — a dial, a thermometer, a roofline, a board, a
 * basket, an open book, a bubble with a tail — so a tile is recognisable in the corner of your eye,
 * before the colour registers and well before the label is read.
 *
 * LifeOps' is not a new drawing at all: it is the mark LifeOps already wore as its launcher icon
 * (`ic_app_logo` — a dial, four ticks, a checkmark for a needle), redrawn at icon scale. Its
 * proportions are adapted rather than transcribed, because the original is a 120dp logo: a faint
 * hairline ring that reads as a delicate dial at that size disappears entirely at 18dp, so the
 * strokes are heavier and the ring less faint while the 1 : 1.6 : 2.6 weight ladder between ring,
 * ticks and needle is kept.
 *
 * Everything is drawn in a 24×24 viewport as line art with selective solid fills, and every mark
 * has to work in a single colour: the home screen tints the whole vector with the app's accent, so
 * a mark can never rely on a second hue to be readable. What it can rely on is alpha — a stroke at
 * 0.5 survives tinting and is how the secondary detail (a book's text lines, a board's header rule)
 * stays subordinate to the shape that carries the identity.
 *
 * Every mark can *also* be built in two colours ([inColour]), for an app given icon colours of its
 * own. The split is the same everywhere and it is the one LifeOps' launcher icon already draws:
 * [Ink.line] takes the structure that holds the mark up — a dial's ring, a thermometer's tube, a
 * house's walls — and [Ink.highlight] takes the one element the mark exists to show, the checkmark
 * standing in for a needle, the mercury in the tube, the figure under the roof. Two colours, not
 * eight: a mark that needed its own palette to be read would be back to the problem this set fixed.
 */
object SuiteGlyphs {

    /**
     * What a mark is drawn with: either nothing — the placeholder the caller tints over, which is
     * how a mark is drawn for an app with no colours of its own — or a colour per role.
     */
    class Ink(val line: Color? = null, val highlight: Color? = null) {
        companion object {
            /** The one every mark is built with by default; [byKey] holds those. */
            val Tintable = Ink()
        }
    }

    /** LifeOps — its own launcher mark: a dial with a checkmark for a needle. */
    val Dial: ImageVector by lazy { dial(Ink.Tintable) }

    /**
     * The dial. Ring and ticks are the structure, the checkmark needle the highlight — the same
     * split the original launcher icon draws with `icon_dial` and `icon_check`.
     */
    private fun dial(ink: Ink): ImageVector =
        glyph("SuiteDial") {
            // The ring, faint like the original, and the four quarter ticks a little heavier.
            line(width = 1.4f, alpha = 0.55f, ink = ink.line) { circle(cx = 12f, cy = 12f, r = 7.6f) }
            line(width = 2f, ink = ink.line) {
                moveTo(12f, 4.4f); lineTo(12f, 6.5f)
                moveTo(19.6f, 12f); lineTo(17.5f, 12f)
                moveTo(12f, 19.6f); lineTo(12f, 17.5f)
                moveTo(4.4f, 12f); lineTo(6.5f, 12f)
            }
            // The needle that is really a checkmark — the whole idea of the app in one stroke.
            line(width = 2.4f, ink = ink.highlight) {
                moveTo(8.8f, 12.4f); lineTo(11.2f, 14.7f); lineTo(15.8f, 9.3f)
            }
        }

    /**
     * Health — a clinical thermometer: temperatures, symptoms, medicines. The tube and its scale
     * are the structure; the mercury standing in it is the reading, so it takes the highlight.
     */
    private fun thermometer(ink: Ink): ImageVector =
        glyph("SuiteThermometer") {
            // Tube and bulb as one outline, so the two never drift apart at small sizes.
            line(width = 1.6f, ink = ink.line) {
                moveTo(10.3f, 5.9f)
                arcTo(1.7f, 1.7f, 0f, false, true, 13.7f, 5.9f)
                lineTo(13.7f, 13.5f)
                arcTo(3.7f, 3.7f, 0f, true, true, 10.3f, 13.5f)
                close()
            }
            // The mercury standing in it: the one solid mass, so the eye lands on the reading.
            line(width = 1.8f, ink = ink.highlight) { moveTo(12f, 10.6f); lineTo(12f, 14.6f) }
            solid(ink = ink.highlight) { circle(cx = 12f, cy = 16.6f, r = 2.2f) }
            // Scale ticks — texture at tile size, gone at settings size, missed at neither.
            line(width = 1.4f, alpha = 0.5f, ink = ink.line) {
                moveTo(15.4f, 8f); lineTo(17.2f, 8f)
                moveTo(15.4f, 10.8f); lineTo(17.2f, 10.8f)
            }
        }

    /**
     * People — a household under one roof, not a crowd.
     *
     * The figure is drawn large and made to stand *on* the floor line, because a smaller one
     * floating inside the walls stopped reading as a person at settings size and became a dot
     * under an arch. The house is the structure and the figure under it the highlight.
     */
    private fun household(ink: Ink): ImageVector =
        glyph("SuiteHousehold") {
            line(width = 1.7f, ink = ink.line) {
                // Roofline: the shape that makes this "household" rather than "contacts".
                moveTo(2.6f, 10.2f); lineTo(12f, 3.2f); lineTo(21.4f, 10.2f)
                // Walls and floor, drawn open so the roof's peak stays the silhouette.
                moveTo(5.2f, 8.8f); lineTo(5.2f, 20.6f); lineTo(18.8f, 20.6f); lineTo(18.8f, 8.8f)
            }
            solid(ink = ink.highlight) {
                circle(cx = 12f, cy = 13.1f, r = 2.15f)
                // Shoulders as a filled bust rather than an arc: at 18dp a stroked arc and the
                // floor line under it turn into two parallel lines instead of a body.
                moveTo(8.1f, 20.6f)
                arcTo(3.9f, 3.9f, 0f, false, true, 15.9f, 20.6f)
                close()
            }
        }

    /**
     * Project — a board of work in columns: outlines, docs, lore. The board is the structure; the
     * cards on it are the work, so they take the highlight.
     */
    private fun board(ink: Ink): ImageVector =
        glyph("SuiteBoard") {
            line(width = 1.7f, ink = ink.line) {
                moveTo(5.2f, 4f)
                lineTo(18.8f, 4f)
                arcTo(2f, 2f, 0f, false, true, 20.8f, 6f)
                lineTo(20.8f, 18f)
                arcTo(2f, 2f, 0f, false, true, 18.8f, 20f)
                lineTo(5.2f, 20f)
                arcTo(2f, 2f, 0f, false, true, 3.2f, 18f)
                lineTo(3.2f, 6f)
                arcTo(2f, 2f, 0f, false, true, 5.2f, 4f)
                close()
            }
            line(width = 1.3f, alpha = 0.45f, ink = ink.line) { moveTo(3.2f, 7.9f); lineTo(20.8f, 7.9f) }
            // Cards, not columns: one lane holding two and the others holding one of unequal
            // length is what makes this a board mid-work rather than a window with mullions.
            // Three equal bars read as a grid, which is the thing this icon replaced.
            solid(ink = ink.highlight) {
                rect(left = 5.9f, top = 9.6f, right = 9.1f, bottom = 13.1f)
                rect(left = 5.9f, top = 14.3f, right = 9.1f, bottom = 17.6f)
                rect(left = 10.4f, top = 9.6f, right = 13.6f, bottom = 15.7f)
                rect(left = 14.9f, top = 9.6f, right = 18.1f, bottom = 12.3f)
            }
        }

    /**
     * Logistics — a basket: pantry, groceries, recipes. The handle is its own path rather than part
     * of the body, because the arc above the rim is what makes the trapezoid a basket at all — so
     * it is the element that takes the highlight while body and ribs hold the structure.
     */
    private fun basket(ink: Ink): ImageVector =
        glyph("SuiteBasket") {
            // Handle first — the arc above the rim is what makes the trapezoid a basket.
            line(width = 1.7f, ink = ink.highlight) {
                moveTo(8.2f, 8.6f)
                arcTo(3.8f, 3.8f, 0f, false, false, 15.8f, 8.6f)
            }
            // The tapered body.
            line(width = 1.7f, ink = ink.line) {
                moveTo(3.5f, 9.6f)
                lineTo(20.5f, 9.6f)
                lineTo(18.1f, 20.3f)
                lineTo(5.9f, 20.3f)
                close()
            }
            // Ribs following the taper, so the body reads as woven rather than as a bucket.
            line(width = 1.4f, alpha = 0.5f, ink = ink.line) {
                moveTo(9.8f, 12.2f); lineTo(9.3f, 17.9f)
                moveTo(14.2f, 12.2f); lineTo(14.7f, 17.9f)
            }
        }

    /**
     * Citation — an open book with text on both pages. The book is the structure; the text on it is
     * what the app is actually about, so the lines take the highlight.
     */
    private fun openBook(ink: Ink): ImageVector =
        glyph("SuiteOpenBook") {
            line(width = 1.6f, ink = ink.line) {
                // Two pages meeting at a spine. Left, then right, then the spine that joins them.
                moveTo(12f, 7.7f)
                curveTo(9.6f, 5.9f, 6.2f, 5.7f, 3.6f, 6.7f)
                lineTo(3.6f, 18.4f)
                curveTo(6.2f, 17.4f, 9.6f, 17.6f, 12f, 19.4f)
                moveTo(12f, 7.7f)
                curveTo(14.4f, 5.9f, 17.8f, 5.7f, 20.4f, 6.7f)
                lineTo(20.4f, 18.4f)
                curveTo(17.8f, 17.4f, 14.4f, 17.6f, 12f, 19.4f)
                moveTo(12f, 7.7f)
                lineTo(12f, 19.4f)
            }
            line(width = 1.2f, alpha = 0.5f, ink = ink.highlight) {
                moveTo(5.9f, 10.5f); lineTo(9.9f, 10.5f)
                moveTo(5.9f, 13.4f); lineTo(9.9f, 13.4f)
                moveTo(14.1f, 10.5f); lineTo(18.1f, 10.5f)
                moveTo(14.1f, 13.4f); lineTo(18.1f, 13.4f)
            }
        }

    /**
     * Advisor — an answer coming back: a bubble with a spark in it. The bubble is the structure and
     * the spark inside it the answer, so the spark takes the highlight.
     */
    private fun answerSpark(ink: Ink): ImageVector =
        glyph("SuiteAnswerSpark") {
            line(width = 1.7f, ink = ink.line) {
                moveTo(6.4f, 4.2f)
                lineTo(17.6f, 4.2f)
                arcTo(3.2f, 3.2f, 0f, false, true, 20.8f, 7.4f)
                lineTo(20.8f, 13.4f)
                arcTo(3.2f, 3.2f, 0f, false, true, 17.6f, 16.6f)
                lineTo(12.4f, 16.6f)
                // The tail. Nothing else in the set breaks its own outline, which is the point.
                lineTo(7.6f, 20.4f)
                lineTo(8.6f, 16.6f)
                lineTo(6.4f, 16.6f)
                arcTo(3.2f, 3.2f, 0f, false, true, 3.2f, 13.4f)
                lineTo(3.2f, 7.4f)
                arcTo(3.2f, 3.2f, 0f, false, true, 6.4f, 4.2f)
                close()
            }
            solid(ink = ink.highlight) {
                moveTo(12f, 6.9f)
                curveTo(12.5f, 9f, 13.4f, 9.9f, 15.5f, 10.4f)
                curveTo(13.4f, 10.9f, 12.5f, 11.8f, 12f, 13.9f)
                curveTo(11.5f, 11.8f, 10.6f, 10.9f, 8.5f, 10.4f)
                curveTo(10.6f, 9.9f, 11.5f, 9f, 12f, 6.9f)
                close()
            }
        }

    /**
     * Maintenance — an open-ended spanner, laid across the tile on the diagonal.
     *
     * The diagonal is doing the work. Every other mark in the set is upright and roughly square in
     * its bounding box; this one crosses corner to corner, so it is told apart by its angle before
     * any of its detail resolves. The jaws are drawn open rather than as a closed ring because a
     * ring at 18dp is a doughnut, and a doughnut on a stick is a magnifying glass. Handle and grip
     * are the structure; the head and its jaws are the working end, so they take the highlight.
     */
    private fun wrench(ink: Ink): ImageVector =
        glyph("SuiteWrench") {
            // The handle: the heaviest stroke, and the line the whole silhouette is read from.
            line(width = 2.6f, ink = ink.line) { moveTo(9.6f, 9.6f); lineTo(18.6f, 18.6f) }
            // The head — three quarters of a ring, opening up and to the left.
            line(width = 1.9f, ink = ink.highlight) {
                moveTo(7.4f, 4.3f)
                arcTo(3.1f, 3.1f, 0f, true, true, 4.3f, 7.4f)
            }
            // The jaws themselves, carried past the ring so the opening reads as a bite.
            line(width = 1.9f, ink = ink.highlight) {
                moveTo(7.4f, 4.3f); lineTo(5.9f, 2.8f)
                moveTo(4.3f, 7.4f); lineTo(2.8f, 5.9f)
            }
            // The grip, crossing the handle: secondary, so it survives tinting without competing.
            line(width = 1.3f, alpha = 0.5f, ink = ink.line) {
                moveTo(12.7f, 15.1f); lineTo(15.1f, 12.7f)
                moveTo(14.9f, 17.3f); lineTo(17.3f, 14.9f)
            }
        }

    /**
     * Repository — a folder standing on a shelf.
     *
     * The two halves are the app: a document (the tab and the body, drawn as the structural line)
     * sitting on a rule (the shelf, drawn as the highlight). It has to be told apart from Project's
     * board and Citation's book at tile size, so the shelf runs the full width of the mark and the
     * folder is deliberately narrower than a page — a folder among folders, not a page on its own.
     */
    private fun folderShelf(ink: Ink): ImageVector =
        glyph("SuiteFolderShelf") {
            // The shelf: one rule the whole mark stands on, with a lip at each end.
            line(width = 1.9f, ink = ink.highlight) {
                moveTo(3.2f, 19.4f); lineTo(20.8f, 19.4f)
                moveTo(3.2f, 19.4f); lineTo(3.2f, 17.4f)
                moveTo(20.8f, 19.4f); lineTo(20.8f, 17.4f)
            }
            // The folder: a tab along the top edge, then the body squared off onto the shelf.
            line(width = 1.9f, ink = ink.line) {
                moveTo(6.1f, 16.4f)
                lineTo(6.1f, 5.6f)
                lineTo(11.2f, 5.6f)
                lineTo(12.6f, 7.6f)
                lineTo(17.9f, 7.6f)
                lineTo(17.9f, 16.4f)
                close()
            }
            // A second folder behind it, secondary so it survives tinting without competing: the
            // shelf holds more than one thing, which is the whole point of the app.
            line(width = 1.3f, alpha = 0.5f, ink = ink.line) {
                moveTo(8.6f, 10.6f); lineTo(15.4f, 10.6f)
            }
        }

    /**
     * How to draw each mark, by the [com.operations.suitekit.SuiteAppInfo.iconKey] that names it.
     * The drawing is a *function* of its ink rather than a finished vector, so the tintable form
     * and the two-colour one can never be different drawings.
     */
    private val marks: Map<String, (Ink) -> ImageVector> = mapOf(
        "lifeops-dial" to ::dial,
        "thermometer" to ::thermometer,
        "household" to ::household,
        "board" to ::board,
        "basket" to ::basket,
        "open-book" to ::openBook,
        "answer-spark" to ::answerSpark,
        "wrench" to ::wrench,
        "folder-shelf" to ::folderShelf
    )

    /** Every mark in its tintable form, by icon key — what an app with no icon colours is drawn with. */
    val byKey: Map<String, ImageVector> by lazy { marks.mapValues { (_, draw) -> draw(Ink.Tintable) } }

    /**
     * The mark for [iconKey] built in [colours], or null when nothing is drawn under that name.
     * The result is a fresh vector each call — the colours are the user's and change with the
     * theme, so there is nothing stable to cache; callers `remember` it against what they asked for.
     */
    fun inColour(iconKey: String, colours: SuiteIconColors): ImageVector? =
        marks[iconKey]?.invoke(Ink(line = Color(colours.line), highlight = Color(colours.highlight)))
}

// --- Drawing helpers -------------------------------------------------------------------------
//
// A 24×24 viewport for every mark, so the coordinates above can be compared across apps by eye and
// a stroke width means the same thing in all of them.

private fun glyph(name: String, paths: ImageVector.Builder.() -> Unit): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply(paths).build()

/**
 * A stroked path. With no [ink] the colour is a placeholder — the caller tints the finished vector,
 * so only the [alpha] survives to say "this line is secondary". A mark drawn in an app's own icon
 * colours passes one, and then the colour is the drawing's.
 */
private fun ImageVector.Builder.line(
    width: Float = 1.7f,
    alpha: Float = 1f,
    ink: Color? = null,
    body: PathBuilder.() -> Unit
) = path(
    stroke = SolidColor(ink ?: Color.Black),
    strokeAlpha = alpha,
    strokeLineWidth = width,
    strokeLineCap = StrokeCap.Round,
    strokeLineJoin = StrokeJoin.Round,
    pathBuilder = body
)

/** A filled path — used sparingly, for the one mass in a mark that the eye should land on. */
private fun ImageVector.Builder.solid(
    alpha: Float = 1f,
    ink: Color? = null,
    body: PathBuilder.() -> Unit
) = path(
    fill = SolidColor(ink ?: Color.Black),
    fillAlpha = alpha,
    pathBuilder = body
)

/** A full circle as two half arcs — the vector format has no circle primitive. */
private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
    moveTo(cx, cy - r)
    arcTo(r, r, 0f, false, true, cx, cy + r)
    arcTo(r, r, 0f, false, true, cx, cy - r)
    close()
}

private fun PathBuilder.rect(left: Float, top: Float, right: Float, bottom: Float) {
    moveTo(left, top)
    lineTo(right, top)
    lineTo(right, bottom)
    lineTo(left, bottom)
    close()
}
