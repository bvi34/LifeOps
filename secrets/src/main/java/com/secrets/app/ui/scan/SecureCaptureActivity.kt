package com.secrets.app.ui.scan

import android.os.Bundle
import android.view.WindowManager
import com.journeyapps.barcodescanner.CaptureActivity

/**
 * The scanner, with the screenshot blocked.
 *
 * `zxing-android-embedded` supplies a perfectly good capture activity, and it does not set
 * `FLAG_SECURE` — it has no reason to, since the thing it was written for is scanning a ticket. What
 * is in front of the camera here is a **QR code containing a second-factor seed**, which is a
 * long-lived secret in the most readable form it will ever take. Without this flag the system writes
 * a thumbnail of that camera view to disk to draw the recents card, which would be a picture of the
 * seed, taken by the platform rather than by anybody's decision — the same leak `MainActivity`
 * documents, arriving through a screen this app did not write.
 *
 * So the library's activity is subclassed for one line. The flag is set before `super.onCreate`
 * because that is where the content view is attached, and a window that gains the flag afterwards
 * has already had a frame it could not protect.
 */
class SecureCaptureActivity : CaptureActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        super.onCreate(savedInstanceState)
    }
}
