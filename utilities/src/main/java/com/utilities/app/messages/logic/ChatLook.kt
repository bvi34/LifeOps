package com.utilities.app.messages.logic

import com.utilities.app.look.UtilityLook
import com.utilities.app.look.UtilityPalette
import com.utilities.app.look.UtilityPalettes

/**
 * How the message thread is drawn, on top of the suite-wide [UtilityLook].
 *
 * The split is the point. Everything a *surface* can be — its colours, its warmth, its face, its
 * corner radius — is the shared value, so the keyboard and the threads can be set to match in one
 * gesture and a third takeover inherits the whole picker for free. What is here is only what a
 * conversation has and a keyboard does not: which side a bubble sits on, whether it has a tail,
 * how often the clock is shown.
 *
 * This is the half of the app the user asked Citation for. Citation's display sheet is the best
 * screen in the suite — not because it has a lot of switches, but because each one is a decision
 * somebody defended, and the presets are covers for a page you can still take apart. A messaging
 * app is the other surface people stare at for an hour, and it got the same treatment.
 */
data class ChatLook(
    val look: UtilityLook = UtilityLook(),

    /** How round a bubble is. Separate from [UtilityLook.cornerDp]: a key and a bubble disagree. */
    val bubbleCornerDp: Float = 20f,

    /**
     * The little point where a bubble meets its sender.
     *
     * On by default, and worth a switch rather than being a style nobody can turn off: a tail is
     * what makes a run of five messages from one person read as five messages rather than one
     * paragraph, and it is also the first thing people who find chat apps fussy want gone.
     */
    val tails: Boolean = true,

    /** The sent bubble's colour, ARGB, or null to use the look's accent. */
    val sentColor: Int? = null,

    /** The received bubble's colour, ARGB, or null to derive one from the surface. */
    val receivedColor: Int? = null,

    val timestamps: TimestampStyle = TimestampStyle.GROUPED,

    /**
     * The coloured circle with an initial in it, beside each incoming run.
     *
     * Its colour is derived from the address rather than picked, so the same person is the same
     * colour on every phone this is restored onto — and so a group thread is readable without
     * anybody naming a colour per member.
     */
    val avatars: Boolean = true,

    /**
     * Enter sends, rather than starting a new line.
     *
     * Off by default and deliberately so: a phone keyboard's return key is where people's thumbs go
     * to break a line, and the failure mode of getting this wrong is sending half a sentence. On
     * for anybody who types the way they type at a desk.
     */
    val enterSends: Boolean = false,

    /**
     * Show the thread newest-at-the-bottom (the convention) or newest-at-the-top.
     *
     * The second one is not a joke: a thread with a thousand messages opened to read the last one
     * is a scroll to the bottom every single time, and some people would rather it just started
     * there facing the other way.
     */
    val newestFirst: Boolean = false
) {
    fun sanitized(): ChatLook = copy(
        look = look.sanitized(),
        bubbleCornerDp = bubbleCornerDp.coerceIn(0f, 28f),
        sentColor = sentColor?.let { UtilityPalettes.opaque(it) },
        receivedColor = receivedColor?.let { UtilityPalettes.opaque(it) }
    )
}

/**
 * How often the clock appears.
 *
 * [GROUPED] is the default and the one worth explaining: a timestamp above each *run* of messages
 * that started more than a few minutes after the last one. It is what makes a thread readable as a
 * conversation with pauses in it, rather than as a list with a number beside every line.
 */
enum class TimestampStyle(val label: String) {
    NONE("Never"),
    GROUPED("Between pauses"),
    EVERY("On every message")
}

/** The colours a thread is actually painted with, derived from a [ChatLook] and the app's own. */
data class ChatPalette(
    val base: UtilityPalette,
    val sent: Int,
    val onSent: Int,
    val received: Int,
    val onReceived: Int
)

object ChatPalettes {

    /**
     * How far a received bubble is lifted off the surface when nobody has picked a colour for it.
     *
     * Derived rather than chosen, and lightly: a received bubble has to be distinguishable from the
     * background without becoming a second accent competing with the sent one. Eight per cent of
     * the way toward the text colour is about the faintest step that survives on both a paper
     * surface and a true-black one.
     */
    const val RECEIVED_LIFT = 0.08f

    /**
     * The gap that has to exist between a bubble and the surface behind it. Below this, the derived
     * lift is doubled — which is what rescues a custom surface somebody tinted mid-grey, where the
     * default step lands on a colour indistinguishable from it.
     */
    const val MIN_BUBBLE_CONTRAST = 1.08f

    fun resolve(
        chat: ChatLook,
        fallbackSurface: Int,
        fallbackText: Int,
        fallbackAccent: Int = UtilityPalettes.DEFAULT_ACCENT
    ): ChatPalette {
        val clean = chat.sanitized()
        val base = UtilityPalettes.resolve(clean.look, fallbackSurface, fallbackText, fallbackAccent)

        val sent = clean.sentColor
            ?.let { UtilityPalettes.readable(UtilityPalettes.warm(it, clean.look.warmth), base.surface, base.text) }
            ?: base.accent

        val received = clean.receivedColor
            ?.let { UtilityPalettes.warm(it, clean.look.warmth) }
            ?: liftedFrom(base)

        return ChatPalette(
            base = base,
            sent = sent,
            onSent = UtilityPalettes.contrastOn(sent),
            received = received,
            onReceived = UtilityPalettes.contrastOn(received)
        )
    }

    /** A received bubble derived from the surface, stepped up again if the first step vanished. */
    private fun liftedFrom(base: UtilityPalette): Int {
        val once = UtilityPalettes.mix(base.surface, base.text, RECEIVED_LIFT)
        if (UtilityPalettes.contrast(once, base.surface) >= MIN_BUBBLE_CONTRAST) return once
        return UtilityPalettes.mix(base.surface, base.text, RECEIVED_LIFT * 2)
    }

    /**
     * The avatar colour for an address: a hue derived from the number itself.
     *
     * A hash rather than a stored choice, so it needs no state, survives a restore, and gives the
     * same person the same colour on a new phone. Lifted toward the surface so it sits in the
     * thread's palette rather than on top of it.
     */
    fun avatar(address: String, base: UtilityPalette): Int {
        val hue = (Addresses.key(address).hashCode().toLong() and 0xFFFFFFFFL) % 360L
        val raw = hsl(hue.toFloat(), 0.45f, if (base.dark) 0.42f else 0.62f)
        return UtilityPalettes.mix(raw, base.surface, 0.12f)
    }

    /** HSL to ARGB. Here rather than in a colour library because it is the only conversion needed. */
    private fun hsl(h: Float, s: Float, l: Float): Int {
        val c = (1f - kotlin.math.abs(2f * l - 1f)) * s
        val hp = h / 60f
        val x = c * (1f - kotlin.math.abs(hp % 2f - 1f))
        val (r1, g1, b1) = when {
            hp < 1f -> Triple(c, x, 0f)
            hp < 2f -> Triple(x, c, 0f)
            hp < 3f -> Triple(0f, c, x)
            hp < 4f -> Triple(0f, x, c)
            hp < 5f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val m = l - c / 2f
        fun ch(v: Float) = ((v + m) * 255f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(r1) shl 16) or (ch(g1) shl 8) or ch(b1)
    }
}
