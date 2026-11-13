package com.operations.sandbox.transfer

import android.os.Bundle
import android.view.WindowManager
import com.journeyapps.barcodescanner.CaptureActivity

/**
 * The scanner for "Restore from old phone", with screenshots blocked.
 *
 * The code being scanned carries the key the transfer is sealed with. Without `FLAG_SECURE`, the
 * system saves a recents thumbnail of the camera view, which would be a picture of that key. This
 * is the same one-line subclass Secrets uses (`SecureCaptureActivity`), for the same reason.
 */
class TransferCaptureActivity : CaptureActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        super.onCreate(savedInstanceState)
    }
}
