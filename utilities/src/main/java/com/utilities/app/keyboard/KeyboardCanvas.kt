package com.utilities.app.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.utilities.app.keyboard.logic.Key
import com.utilities.app.keyboard.logic.KeyboardLayout
import com.utilities.app.keyboard.logic.KeyboardLook
import com.utilities.app.keyboard.logic.ShiftState
import com.utilities.app.look.UtilityPalette
import com.utilities.app.look.UtilityPalettes
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The keys, drawn.
 *
 * ## Why this is a View and not Compose
 *
 * Everything else the household sees in this suite is Compose, and this deliberately is not. Two
 * reasons, and the second is the one that decided it:
 *
 *  1. **An input method has no activity.** Its window is put up by the system over somebody else's
 *     app, so a `ComposeView` here needs a lifecycle owner, a saved-state registry and a view-model
 *     store bolted onto a decor view by hand, each of which is a thing to get wrong in a process
 *     that is hosting eleven other apps. The recipe exists; it is machinery in service of nothing
 *     this screen needs.
 *  2. **A keyboard is thirty rounded rectangles and thirty pieces of text**, relaid out only when
 *     the layer changes and repainted on every touch. That is precisely what a `Canvas` is, and
 *     doing it directly means a keypress repaints without a recomposition anywhere in it.
 *
 * The settings screen for this keyboard *is* Compose, like the rest of the suite. It is the surface
 * that is different, not the app.
 *
 * ## What it does not decide
 *
 * Nothing. It is handed a [KeyboardLayout] and a [KeyboardLook] and reports presses back through
 * [onKey]. Which layer is showing, whether shift is held, what a long press means — all of that is
 * [com.utilities.app.keyboard.logic.KeyboardMachine], where it can be tested. This file owns
 * geometry, paint and the touch slop, and owns them alone.
 */
@SuppressLint("ViewConstructor")
class KeyboardCanvas(
    context: Context,
    private val onKey: (Key, Boolean) -> Unit
) : View(context) {

    private var layout: KeyboardLayout = KeyboardLayout(emptyList())
    private var look: KeyboardLook = KeyboardLook()
    private var palette: UtilityPalette = UtilityPalettes.resolve(look.look, 0xFFEFEFF2.toInt(), 0xFF1A1A1A.toInt())
    private var shift: ShiftState = ShiftState.OFF

    /** Where each key ended up, recomputed on layout. Parallel to [layout]'s rows, flattened. */
    private val placed = ArrayList<Placed>()

    private var pressed: Placed? = null
    private var longPressFired = false

    private val handler = Handler(Looper.getMainLooper())
    private val longPress = Runnable {
        val target = pressed ?: return@Runnable
        longPressFired = true
        feedback()
        onKey(target.key, true)
        invalidate()
    }
    private val repeat = object : Runnable {
        override fun run() {
            val target = pressed ?: return
            onKey(target.key, false)
            handler.postDelayed(this, REPEAT_INTERVAL_MS)
        }
    }

    private val capPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.RIGHT }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private val capRect = RectF()

    init {
        isHapticFeedbackEnabled = true
    }

    /** Draw a different set of keys. Re-measures only when the number of rows changed. */
    fun show(layout: KeyboardLayout, look: KeyboardLook, palette: UtilityPalette, shift: ShiftState) {
        val rowsChanged = layout.rows.size != this.layout.rows.size
        val heightChanged = look.heightScale != this.look.heightScale
        this.layout = layout
        this.look = look
        this.palette = palette
        this.shift = shift
        if (rowsChanged || heightChanged) requestLayout() else place()
        invalidate()
    }

    /** The face the caps are set in — the household's, if they supplied one. */
    fun setTypeface(typeface: Typeface?) {
        textPaint.typeface = typeface
        hintPaint.typeface = typeface
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val rows = max(layout.rows.size, 1)
        val rowHeight = ROW_HEIGHT_DP * resources.displayMetrics.density * look.sanitized().heightScale
        setMeasuredDimension(width, (rows * rowHeight).roundToInt())
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        place()
    }

    /**
     * Work out where every key is.
     *
     * Widths are relative and normalised per row, so a row whose weights add up to anything at all
     * still fills the keyboard exactly — which is what stops a layout mistake becoming a strip of
     * dead space down one side that people press and nothing happens.
     */
    private fun place() {
        placed.clear()
        if (layout.rows.isEmpty() || width == 0 || height == 0) return
        val gap = GAP_DP * resources.displayMetrics.density
        val rowHeight = height.toFloat() / layout.rows.size
        layout.rows.forEachIndexed { rowIndex, row ->
            val total = row.keys.sumOf { it.weight.toDouble() }.toFloat().takeIf { it > 0f } ?: return@forEachIndexed
            var x = 0f
            val top = rowIndex * rowHeight
            row.keys.forEach { key ->
                val keyWidth = width * (key.weight / total)
                placed.add(
                    Placed(
                        key = key,
                        left = x,
                        top = top,
                        right = x + keyWidth,
                        bottom = top + rowHeight,
                        inset = gap / 2f
                    )
                )
                x += keyWidth
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(palette.surface)
        if (placed.isEmpty()) return

        val density = resources.displayMetrics.density
        val corner = look.look.cornerDp.coerceIn(0f, 20f) * density
        val scale = look.look.textScale
        textPaint.textSize = CAP_TEXT_DP * density * scale
        hintPaint.textSize = HINT_TEXT_DP * density * scale
        edgePaint.strokeWidth = density
        edgePaint.color = palette.line

        placed.forEach { spot ->
            val down = spot === pressed
            capRect.set(
                spot.left + spot.inset,
                spot.top + spot.inset,
                spot.right - spot.inset,
                spot.bottom - spot.inset
            )

            // Caps are lifted off the surface; the wide keys (shift, backspace, the layer toggle)
            // sit a shade lower so the letters read as the thing you aim at.
            val resting = if (spot.key.action == null) {
                UtilityPalettes.mix(palette.surface, palette.text, if (palette.dark) 0.14f else 0.06f)
            } else {
                UtilityPalettes.mix(palette.surface, palette.text, if (palette.dark) 0.07f else 0.12f)
            }
            capPaint.color = if (down) palette.accent else resting
            if (look.keyEdges || down) {
                canvas.drawRoundRect(capRect, corner, corner, capPaint)
                if (look.keyEdges && !down) canvas.drawRoundRect(capRect, corner, corner, edgePaint)
            }

            val ink = if (down) palette.onAccent else palette.text
            textPaint.color = ink
            val cap = spot.key.cap(shift)
            val baseline = capRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(cap, capRect.centerX(), baseline, textPaint)

            // The alternate, printed small in the top-right corner. This is what makes the long
            // press discoverable by reading rather than by accident — see Key.alternate.
            val alternate = spot.key.alternate
            if (alternate != null && shift == ShiftState.OFF) {
                hintPaint.color = UtilityPalettes.mix(resting, ink, 0.55f)
                canvas.drawText(
                    alternate,
                    capRect.right - HINT_INSET_DP * density,
                    capRect.top + (HINT_INSET_DP + HINT_TEXT_DP * 0.8f) * density,
                    hintPaint
                )
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                begin(at(event.x, event.y))
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                // Sliding off a key cancels it rather than typing whatever you slid onto. A keyboard
                // that commits the key under your finger when you let go is a keyboard that types a
                // letter every time somebody scrolls the page behind it.
                val under = at(event.x, event.y)
                if (under !== pressed) cancel()
                return true
            }

            MotionEvent.ACTION_UP -> {
                val target = pressed
                val fired = longPressFired
                cancel()
                if (target != null && !fired && !target.key.repeats) onKey(target.key, false)
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                cancel()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun begin(target: Placed?) {
        cancel()
        if (target == null) return
        pressed = target
        longPressFired = false
        feedback()
        invalidate()
        if (target.key.repeats) {
            // A repeating key fires once on the way down and then keeps going: waiting for the
            // release to delete the first character makes backspace feel a beat slow.
            onKey(target.key, false)
            handler.postDelayed(repeat, REPEAT_DELAY_MS)
        } else if (target.key.alternate != null || target.key.action != null) {
            handler.postDelayed(longPress, look.sanitized().longPressMs.toLong())
        }
    }

    private fun cancel() {
        handler.removeCallbacks(longPress)
        handler.removeCallbacks(repeat)
        if (pressed != null) {
            pressed = null
            invalidate()
        }
    }

    private fun feedback() {
        if (!look.haptics) return
        performHapticFeedback(
            HapticFeedbackConstants.KEYBOARD_TAP,
            HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
        )
    }

    private fun at(x: Float, y: Float): Placed? =
        placed.firstOrNull { x >= it.left && x < it.right && y >= it.top && y < it.bottom }

    override fun onDetachedFromWindow() {
        cancel()
        super.onDetachedFromWindow()
    }

    private class Placed(
        val key: Key,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val inset: Float
    )

    private companion object {
        /** One row's height before the household's scale is applied. */
        const val ROW_HEIGHT_DP = 52f
        const val GAP_DP = 5f
        const val CAP_TEXT_DP = 20f
        const val HINT_TEXT_DP = 10f
        const val HINT_INSET_DP = 6f

        /** How long backspace is held before it starts repeating, and how fast it then goes. */
        const val REPEAT_DELAY_MS = 400L
        const val REPEAT_INTERVAL_MS = 55L
    }
}
