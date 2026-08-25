package com.citation.app.ui.reader

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.citation.core.reader.ReaderSettings
import com.citation.core.reader.ScreenOrientation

/**
 * The window-level things a reader asks for: full screen, a locked orientation, its own brightness.
 *
 * Each is applied as an effect scoped to the reader and **undone on the way out**, which is the part
 * that is easy to get wrong: an app that leaves the status bar hidden, the orientation pinned, or
 * the brightness overridden after you close a book has broken the rest of itself to serve one
 * screen. Every one of these restores on dispose.
 *
 * All of it degrades silently when there is no Activity to talk to — Citation is a library module
 * hosted by the sandbox app, and a preview or a test host has no window at all.
 *
 * Applied once for whichever reader is open — the flowing text, a PDF's pages, a licensed book in
 * its own WebView — rather than per screen, so a PDF does not dim mid-page just because the setting
 * happens to live in the flowing reader's Display sheet.
 */
@Composable
fun ReaderWindowEffects(settings: ReaderSettings, active: Boolean) {
    val activity = LocalContext.current.findActivity()
    val view = LocalView.current

    // The window flag rather than a wake lock: scoped to this composable, released the moment
    // reading stops, and needing no permission.
    DisposableEffect(view, active, settings.keepAwake) {
        view.keepScreenOn = active && settings.keepAwake
        onDispose { view.keepScreenOn = false }
    }

    // Full screen. The bars come back on a swipe from the edge and hide again by themselves, which
    // is what lets immersive reading stay immersive without trapping anyone in it.
    DisposableEffect(activity, active, settings.immersive) {
        val window = activity?.window
        val decor = window?.decorView
        val controller = if (window != null && decor != null) {
            WindowInsetsControllerCompat(window, decor)
        } else {
            null
        }
        if (controller != null) {
            if (active && settings.immersive) {
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    DisposableEffect(activity, active, settings.orientation) {
        val previous = activity?.requestedOrientation
        if (activity != null) {
            activity.requestedOrientation = if (!active) {
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            } else {
                when (settings.orientation) {
                    ScreenOrientation.FOLLOW_SYSTEM -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    ScreenOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
                    ScreenOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
                }
            }
        }
        onDispose {
            activity?.requestedOrientation = previous ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // Brightness set here is a *window* attribute, so it applies only while the reader is up and
    // never touches the device's own setting — which is why it can be turned right down for reading
    // in the dark without leaving the phone unusable afterwards.
    DisposableEffect(activity, active, settings.brightness) {
        val window = activity?.window
        val previous = window?.attributes?.screenBrightness
        if (window != null) {
            window.attributes = window.attributes.apply {
                screenBrightness = if (active && !settings.followsSystemBrightness) {
                    settings.brightness.coerceIn(0.01f, 1f)
                } else {
                    WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
            }
        }
        onDispose {
            window?.let {
                it.attributes = it.attributes.apply {
                    screenBrightness = previous ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
            }
        }
    }
}

/**
 * The Activity behind a Compose context, or null.
 *
 * Written as an unwrap loop rather than a cast because a themed Compose context is a
 * [ContextWrapper] around the Activity, not the Activity itself — the naive cast works in a plain
 * host and returns null in a themed one, which is the sort of difference that shows up only on
 * someone else's device.
 */
fun Context.findActivity(): Activity? {
    var context: Context? = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}

/** Whether the window is currently drawing edge to edge, for callers that need to pad for bars. */
fun Activity.decorFitsSystemWindows(fits: Boolean) {
    WindowCompat.setDecorFitsSystemWindows(window, fits)
}
