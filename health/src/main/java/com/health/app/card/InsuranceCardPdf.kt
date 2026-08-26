package com.health.app.card

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import com.health.app.HealthFileProvider
import com.health.app.data.model.CoverageCard
import com.health.app.data.store.CardImageStore
import com.health.app.logic.CardFace
import com.health.app.logic.Insurance
import java.io.File
import kotlin.math.min

/**
 * The card, on demand, as a PDF.
 *
 * ### What it is for
 *
 * The wallet card is the one health record a household is asked to *produce* rather than consult: at
 * a reception desk, at a pharmacy counter, on a form, or emailed to a school before a trip. The app
 * already holds everything on it, so producing a page ought to be a tap — and a page is what people
 * can actually use, because a PDF prints, attaches to an email, and opens on a laptop, where a
 * screenshot of a phone app does none of those things well.
 *
 * ### What is on it
 *
 * Three pages at most, in the order somebody needs them:
 *
 *  1. **The typed card**, always. Both faces drawn from [Insurance.card] — the same layout the screen
 *     shows, so the member number cannot read one way in the app and another on the page.
 *  2. **The front photograph**, if one was saved.
 *  3. **The back photograph**, if one was saved.
 *
 * The typed page comes first even when there are photographs, and that is deliberate. A photograph of
 * a card is authoritative and hard to read; typed fields are legible, searchable and selectable, and
 * a receptionist reading a member number off a page wants the second thing. The photograph is the
 * evidence behind it.
 *
 * ### What it says about itself
 *
 * Every page carries [Insurance.CARD_DISCLAIMER]. A PDF that reproduces a card faithfully enough to
 * be useful is also faithful enough to be mistaken for the card itself, so it says plainly what it
 * is: a copy of what somebody typed into a phone, not proof of coverage. That line is not decoration
 * and is not optional — it is the same reflex as the fever disclaimer, and the reason this feature
 * can exist without pretending to be something it isn't.
 *
 * Pages are drawn at 72 points to the inch, which is the PDF unit, on a page the size of a real
 * card's paper carrier rather than a sheet of A4 — the thing being reproduced is a card.
 */
object InsuranceCardPdf {

    /** 3.375in × 2.125in, the ISO/IEC 7810 ID-1 card, at 72pt to the inch. */
    private const val CARD_WIDTH_PT = 243
    private const val CARD_HEIGHT_PT = 153

    private const val MARGIN = 14f
    private const val TITLE_SIZE = 12f
    private const val SUBTITLE_SIZE = 8f
    private const val LABEL_SIZE = 5.5f
    private const val VALUE_SIZE = 8f
    private const val FOOTNOTE_SIZE = 4.4f
    private const val ROW_GAP = 2f

    private const val INK = Color.BLACK
    private val MUTED = Color.rgb(0x55, 0x5F, 0x6B)
    private val RULE = Color.rgb(0xCF, 0xD6, 0xDE)

    /**
     * Write the card to a file in `cacheDir/exports` and return it.
     *
     * The cache, not `filesDir`: the export is disposable by design — it is regenerated from the
     * database the next time anybody asks, and the sovereign copy of the card is the row and the
     * photograph, not this. The same reasoning as Citation's notes export.
     *
     * Returns null on any I/O failure rather than throwing. Nothing here is on a path where a
     * half-written PDF should be able to take a screen down.
     */
    fun write(context: Context, card: CoverageCard, images: CardImageStore): File? = runCatching {
        val document = PdfDocument()
        try {
            drawFace(document, card.layout.front, pageNumber = 1)
            drawFace(document, card.layout.back, pageNumber = 2)
            images.load(card.frontImagePath)?.let { drawPhoto(document, it, "Card front", 3) }
            images.load(card.backImagePath)?.let { drawPhoto(document, it, "Card back", 4) }

            val dir = File(context.cacheDir, EXPORT_DIR).apply { mkdirs() }
            val file = File(dir, card.exportFileName)
            file.outputStream().use { document.writeTo(it) }
            file
        } finally {
            document.close()
        }
    }.getOrNull()

    /**
     * Hand an already-written card to the system share sheet — print it, mail it, drop it in a
     * folder.
     *
     * Separate from [write] so the caller can do the drawing off the main thread and only come back
     * to it for the intent, which is where a `startActivity` belongs. Returns false when nothing on
     * the device will take a PDF, so the screen can say so rather than leaving somebody looking at a
     * tap that did nothing.
     */
    fun shareFile(context: Context, file: File, subject: String): Boolean = runCatching {
        val uri = FileProvider.getUriForFile(context, HealthFileProvider.authority(context.packageName), file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(intent, "Share insurance card").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
        true
    }.getOrDefault(false)

    /** One typed face: heading, the labelled lines, and the small print along the bottom. */
    private fun drawFace(document: PdfDocument, face: CardFace, pageNumber: Int) {
        val page = document.startPage(
            PdfDocument.PageInfo.Builder(CARD_WIDTH_PT, CARD_HEIGHT_PT, pageNumber).create()
        )
        val canvas = page.canvas
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        var y = MARGIN + TITLE_SIZE

        paint.color = INK
        paint.textSize = TITLE_SIZE
        paint.isFakeBoldText = true
        canvas.drawText(face.title, MARGIN, y, paint)
        paint.isFakeBoldText = false

        face.subtitle?.let { subtitle ->
            y += SUBTITLE_SIZE + ROW_GAP
            paint.color = MUTED
            paint.textSize = SUBTITLE_SIZE
            canvas.drawText(subtitle, MARGIN, y, paint)
        }

        y += 6f
        paint.color = RULE
        paint.strokeWidth = 0.6f
        canvas.drawLine(MARGIN, y, CARD_WIDTH_PT - MARGIN, y, paint)

        // Two columns, because a card is wider than it is tall and a single column of eight fields
        // would run off the bottom of it.
        val columnWidth = (CARD_WIDTH_PT - MARGIN * 2) / 2f
        val rowHeight = LABEL_SIZE + VALUE_SIZE + ROW_GAP * 2
        val top = y + 8f
        val rowsPerColumn = (((CARD_HEIGHT_PT - MARGIN) - top - FOOTNOTE_SIZE * 3) / rowHeight).toInt()
            .coerceAtLeast(1)

        face.fields.forEachIndexed { index, field ->
            val column = index / rowsPerColumn
            if (column > 1) return@forEachIndexed  // Anything past two columns has no card to go on.
            val row = index % rowsPerColumn
            val x = MARGIN + column * columnWidth
            val rowTop = top + row * rowHeight

            paint.color = MUTED
            paint.textSize = LABEL_SIZE
            canvas.drawText(field.label.uppercase(), x, rowTop + LABEL_SIZE, paint)

            paint.color = INK
            paint.textSize = VALUE_SIZE
            canvas.drawText(
                ellipsize(field.value, paint, columnWidth - 6f),
                x,
                rowTop + LABEL_SIZE + ROW_GAP + VALUE_SIZE,
                paint
            )
        }

        drawFootnotes(canvas, paint, face.footnotes)
        document.finishPage(page)
    }

    /** A saved photograph, on its own page, fitted rather than stretched. */
    private fun drawPhoto(document: PdfDocument, bitmap: Bitmap, caption: String, pageNumber: Int) {
        val page = document.startPage(
            PdfDocument.PageInfo.Builder(CARD_WIDTH_PT, CARD_HEIGHT_PT, pageNumber).create()
        )
        val canvas = page.canvas
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        val available = RectF(
            MARGIN / 2f,
            MARGIN / 2f + LABEL_SIZE,
            CARD_WIDTH_PT - MARGIN / 2f,
            CARD_HEIGHT_PT - MARGIN / 2f - FOOTNOTE_SIZE * 2
        )
        // Fit, never fill: a card cropped to fill the page loses the corner the group number is printed
        // in, and the whole point of keeping the photograph is that it is the card as it actually is.
        val scale = min(available.width() / bitmap.width, available.height() / bitmap.height)
        val width = bitmap.width * scale
        val height = bitmap.height * scale
        val left = available.left + (available.width() - width) / 2f
        val top = available.top + (available.height() - height) / 2f

        canvas.drawBitmap(
            bitmap,
            Rect(0, 0, bitmap.width, bitmap.height),
            RectF(left, top, left + width, top + height),
            paint
        )

        paint.color = MUTED
        paint.textSize = LABEL_SIZE
        canvas.drawText(caption.uppercase(), MARGIN / 2f, MARGIN / 2f + LABEL_SIZE - 2f, paint)

        drawFootnotes(canvas, paint, listOf(Insurance.CARD_DISCLAIMER))
        document.finishPage(page)
    }

    /**
     * The small print, wrapped by hand along the bottom of the page.
     *
     * By hand because `StaticLayout` wants a `TextPaint` and a measured width and brings a whole
     * layout engine along for two lines of 4pt type; `breakText` does the same job here and keeps
     * this file free of anything that needs a view to exist.
     */
    private fun drawFootnotes(canvas: android.graphics.Canvas, paint: Paint, footnotes: List<String>) {
        paint.color = MUTED
        paint.textSize = FOOTNOTE_SIZE
        paint.isFakeBoldText = false
        val width = CARD_WIDTH_PT - MARGIN
        val lines = footnotes.flatMap { wrap(it, paint, width) }.takeLast(MAX_FOOTNOTE_LINES)
        var y = CARD_HEIGHT_PT - MARGIN / 2f - (lines.size - 1) * (FOOTNOTE_SIZE + 1f)
        lines.forEach { line ->
            canvas.drawText(line, MARGIN / 2f, y, paint)
            y += FOOTNOTE_SIZE + 1f
        }
    }

    private fun wrap(text: String, paint: Paint, width: Float): List<String> {
        val lines = mutableListOf<String>()
        var remaining = text.trim()
        while (remaining.isNotEmpty() && lines.size < MAX_FOOTNOTE_LINES) {
            val fits = paint.breakText(remaining, true, width, null)
            if (fits <= 0) break
            if (fits >= remaining.length) {
                lines += remaining
                break
            }
            // Break on the last space inside what fits, so words don't split mid-syllable.
            val cut = remaining.lastIndexOf(' ', fits - 1).takeIf { it > 0 } ?: fits
            lines += remaining.substring(0, cut).trim()
            remaining = remaining.substring(cut).trim()
        }
        return lines
    }

    /** A value too long for its column ends in an ellipsis rather than running into the next one. */
    private fun ellipsize(text: String, paint: Paint, width: Float): String {
        if (paint.measureText(text) <= width) return text
        val fits = paint.breakText(text, true, width - paint.measureText("…"), null)
        return text.substring(0, fits.coerceAtLeast(0)).trimEnd() + "…"
    }

    private const val EXPORT_DIR = "exports"

    /** Matches the `<provider>` authority in Health's manifest. */

    /** Three lines of 4pt type is as much small print as a card-sized page can carry legibly. */
    private const val MAX_FOOTNOTE_LINES = 3
}
