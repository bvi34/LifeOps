package com.utilities.app.messages

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.utilities.app.MainActivity

/**
 * The `sms:` link, answered.
 *
 * ## Why this exists as its own activity
 *
 * Two reasons, and they point the same way. The messaging role requires an app to declare an
 * activity that handles `SENDTO` for the `sms`, `smsto`, `mms` and `mmsto` schemes — an app without
 * one is not offered in the role picker at all. And the suite's rule is that hosted apps' screens
 * are **not exported**: there is one launcher entry point and the sandbox opens everything else
 * in-process.
 *
 * Rather than bend the second rule to satisfy the first, the exported surface is this: an activity
 * with no layout, no state and no result, whose whole life is to read a number out of a URI and
 * start the real screen. It is the same shape as Secrets' share-sheet import — narrow, takes and
 * never gives — and it means `MainActivity` stays closed.
 *
 * What another app learns by starting it: nothing. It returns no result, and it cannot be asked
 * whether the number is known, whether there is a thread, or what is in one.
 *
 * `sms:555-0199?body=hello` is the shape these arrive in. The body is deliberately **not** carried
 * across into the composer: a link that could pre-fill a message is a link that, one mistaken tap
 * from the send button, sends somebody else's words from your number.
 */
class SendToActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val address = intent?.data
            ?.schemeSpecificPart
            ?.substringBefore('?')
            ?.split(',', ';')
            ?.firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_MESSAGES)
                if (address != null) putExtra(MainActivity.EXTRA_ADDRESS, address)
            }
        )
        finish()
    }
}
