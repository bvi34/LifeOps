package com.citation.app

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import kotlin.math.abs

/**
 * The **floating quick-capture bubble** — a chat-head style overlay, so you can capture a manual note
 * over *any* app, even one that offers no selection and no share button. This is the last rung of the
 * capture surfaces: the catch-all.
 *
 * It requires the "display over other apps" permission (`SYSTEM_ALERT_WINDOW`); [canDraw] gates that,
 * and [ensureRunning] only starts the service once it's granted. The bubble does exactly one thing —
 * tapping it opens [ManualCaptureActivity] to type a note. It reads **nothing** from the screen
 * beneath it (that would cross into the overlay-that-reads-the-page territory the feature rules out);
 * it is only a launcher for your own typed capture. Dragging repositions it; a tap (no real drag)
 * triggers capture.
 */
class QuickCaptureBubbleService : Service() {

    private var windowManager: WindowManager? = null
    private var bubble: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (bubble == null) addBubble()
        return START_STICKY
    }

    private fun addBubble() {
        if (!canDraw(this)) {
            stopSelf()
            return
        }
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val view = ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
            contentDescription = "Capture to Citation"
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 240
        }
        view.setOnTouchListener(DragToMoveTapToCapture(wm, params))
        wm.addView(view, params)
        windowManager = wm
        bubble = view
    }

    /** Launch the typed-note capture; the bubble stays put for next time. */
    private fun openCapture() {
        startActivity(
            Intent(this, ManualCaptureActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        bubble?.let { runCatching { windowManager?.removeView(it) } }
        bubble = null
    }

    /**
     * Distinguishes a tap (open capture) from a drag (reposition the bubble) on the overlay view,
     * so the bubble both moves out of the way and acts as a one-tap capture button.
     */
    private inner class DragToMoveTapToCapture(
        private val wm: WindowManager,
        private val params: WindowManager.LayoutParams
    ) : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0

        override fun onTouch(view: View, event: MotionEvent): Boolean = when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX; downY = event.rawY
                startX = params.x; startY = params.y
                true
            }
            MotionEvent.ACTION_MOVE -> {
                params.x = startX + (event.rawX - downX).toInt()
                params.y = startY + (event.rawY - downY).toInt()
                runCatching { wm.updateViewLayout(view, params) }
                true
            }
            MotionEvent.ACTION_UP -> {
                val moved = abs(event.rawX - downX) > TAP_SLOP || abs(event.rawY - downY) > TAP_SLOP
                if (!moved) { view.performClick(); openCapture() }
                true
            }
            else -> false
        }
    }

    companion object {
        private const val TAP_SLOP = 12f

        @Suppress("DEPRECATION")
        private fun overlayType(): Int =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE

        /** Whether the "display over other apps" permission is granted (required to show the bubble). */
        fun canDraw(context: android.content.Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

        /** Start the bubble if the overlay permission is granted; a no-op otherwise. */
        fun ensureRunning(context: android.content.Context) {
            if (canDraw(context)) {
                context.startService(Intent(context, QuickCaptureBubbleService::class.java))
            }
        }
    }
}
