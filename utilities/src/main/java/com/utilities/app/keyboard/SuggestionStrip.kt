package com.utilities.app.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import com.utilities.app.look.UtilityPalette
import com.utilities.app.look.UtilityPalettes
import kotlin.math.roundToInt

/**
 * The row of completions above the keys.
 *
 * Three at most, evenly divided, with a hairline between them. Three because a strip that offers
 * five is a strip people tap the wrong one of, and because the third suggestion is already rarely
 * the right one — the [com.utilities.app.keyboard.logic.Lexicon] is a word list, not a language
 * model, and pretending otherwise by showing more would be a worse keyboard wearing a better one's
 * clothes.
 *
 * The strip keeps its height when there is nothing to suggest, and that is on purpose: a bar that
 * appears and disappears moves every key on the keyboard up and down by forty pixels while
 * somebody is typing, which is the single most disorienting thing a keyboard can do.
 */
@SuppressLint("ViewConstructor")
class SuggestionStrip(
    context: Context,
    private val onPick: (String) -> Unit
) : View(context) {

    private var words: List<String> = emptyList()
    private var palette: UtilityPalette =
        UtilityPalettes.resolve(com.utilities.app.look.UtilityLook(), 0xFFEFEFF2.toInt(), 0xFF1A1A1A.toInt())
    private var scale: Float = 1f
    private var pressedIndex: Int = -1

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun show(words: List<String>, palette: UtilityPalette, textScale: Float, typeface: Typeface?) {
        this.words = words.take(SLOTS)
        this.palette = palette
        this.scale = textScale
        textPaint.typeface = typeface
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(width, (HEIGHT_DP * resources.displayMetrics.density * scale).roundToInt())
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(palette.surface)
        val density = resources.displayMetrics.density
        linePaint.color = palette.line
        canvas.drawRect(0f, height - density, width.toFloat(), height.toFloat(), linePaint)
        if (words.isEmpty()) return

        textPaint.textSize = TEXT_DP * density * scale
        val slot = width.toFloat() / SLOTS
        val baseline = height / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        words.forEachIndexed { index, word ->
            // The first suggestion is the one a space would take, so it is the one drawn in the
            // text colour; the others are quieter. Nothing about it is a button — it is the default,
            // and looking like the default is how it says so.
            textPaint.color = when {
                index == pressedIndex -> palette.accent
                index == 0 -> palette.text
                else -> palette.muted
            }
            canvas.drawText(word, slot * index + slot / 2f, baseline, textPaint)
            if (index > 0) {
                canvas.drawRect(
                    slot * index,
                    height * 0.25f,
                    slot * index + density,
                    height * 0.75f,
                    linePaint
                )
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val index = (event.x / (width.toFloat() / SLOTS)).toInt()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedIndex = if (index in words.indices) index else -1
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP -> {
                val hit = pressedIndex
                pressedIndex = -1
                invalidate()
                if (hit in words.indices && hit == index) onPick(words[hit])
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                pressedIndex = -1
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    companion object {
        /** See the class note for why three. */
        const val SLOTS = 3
        private const val HEIGHT_DP = 40f
        private const val TEXT_DP = 15f
    }
}
