package com.utilities.app.messages.pdu

/**
 * How big a picture message is allowed to be, and what to do about it.
 *
 * ## Why this is not a detail
 *
 * Carriers cap MMS, usually at 300KB and sometimes lower, and a message over the cap does not come
 * back with a helpful error — it is accepted by the radio and then silently dropped somewhere in the
 * network, or rejected minutes later with a status code nobody surfaces. A modern phone camera
 * produces four megabytes a shot. So **every** picture sent from this app has to be re-encoded
 * smaller, and the only question is by how much.
 *
 * The arithmetic is here, separately from the pixels, because it is the part that is wrong in
 * interesting ways: overhead that was not accounted for, a budget divided among attachments without
 * a floor, a ladder that gives up before it has tried the quality setting that would have worked.
 * `MmsBudgetTest` pins all three. The Android half (`mms/MmsImages`) does nothing but run the ladder
 * this produces.
 */
object MmsBudget {

    /**
     * What to assume when the carrier will not say.
     *
     * 300KB is the figure `SmsManager`'s own config defaults to and the one almost every network
     * actually enforces. Assuming more and being wrong means a message that vanishes; assuming less
     * costs a slightly smaller photograph, so the asymmetry decides it.
     */
    const val DEFAULT_MAX_BYTES = 300 * 1024

    /**
     * Room left for everything that is not the pictures: the headers, the recipients, the subject,
     * the layout part, and the per-part framing.
     *
     * Deliberately generous. Being 2KB pessimistic costs nothing anybody can see; being 200 bytes
     * optimistic costs the whole message.
     */
    const val OVERHEAD_BYTES = 2 * 1024

    /**
     * Below this there is no point trying.
     *
     * A photograph squeezed under 8KB is a mosaic, and sending one is worse than telling somebody
     * the message will not fit — they can then send two.
     */
    const val MINIMUM_USEFUL_BYTES = 8 * 1024

    /**
     * The ladder.
     *
     * Scale first, then quality, and in that order for a reason: halving the dimensions removes
     * three quarters of the pixels and is nearly invisible on a phone screen, while dropping JPEG
     * quality below about 60 produces artefacts around text in a photograph — which is exactly the
     * picture people send, being a screenshot or a receipt as often as a face.
     *
     * Each rung is tried in order and the first that fits the budget wins; the last rung is the
     * smallest thing worth sending.
     */
    val LADDER: List<Rung> = listOf(
        Rung(scale = 1.0f, quality = 85),
        Rung(scale = 1.0f, quality = 70),
        Rung(scale = 0.75f, quality = 70),
        Rung(scale = 0.5f, quality = 70),
        Rung(scale = 0.5f, quality = 55),
        Rung(scale = 0.35f, quality = 55),
        Rung(scale = 0.25f, quality = 50),
        Rung(scale = 0.18f, quality = 45)
    )

    /** One attempt at fitting a picture into the budget. */
    data class Rung(val scale: Float, val quality: Int)

    /**
     * How many bytes the attachments may take between them.
     *
     * Returns 0 when there is nothing left after the text and the overhead — which is a real case
     * on a carrier with a small cap and a long message, and is answered by refusing rather than by
     * sending an unreadable thumbnail.
     */
    fun headroom(maxBytes: Int = DEFAULT_MAX_BYTES, textBytes: Int = 0): Int =
        (maxBytes - OVERHEAD_BYTES - textBytes).coerceAtLeast(0)

    /**
     * How many bytes *each* attachment may take.
     *
     * An even split rather than a proportional one. Proportional sounds fairer and is worse: it
     * gives the biggest share to whichever photograph the camera happened to compress least, which
     * is not a property anybody cares about, and it means adding a second picture silently degrades
     * the first.
     */
    fun perAttachment(headroom: Int, count: Int): Int {
        if (count <= 0) return 0
        return headroom / count
    }

    /** Whether a budget is worth attempting at all. See [MINIMUM_USEFUL_BYTES]. */
    fun workable(perAttachment: Int): Boolean = perAttachment >= MINIMUM_USEFUL_BYTES

    /**
     * The whole calculation, from a carrier cap to a per-picture budget.
     *
     * Null means "this will not fit, and no amount of squeezing will fix it" — the caller says so
     * rather than sending something nobody can make out.
     */
    fun plan(maxBytes: Int = DEFAULT_MAX_BYTES, textBytes: Int = 0, attachments: Int = 0): Int? {
        if (attachments <= 0) return null
        val each = perAttachment(headroom(maxBytes, textBytes), attachments)
        return if (workable(each)) each else null
    }

    /** Whether an assembled message is inside the cap, with the overhead already counted. */
    fun fits(totalBytes: Int, maxBytes: Int = DEFAULT_MAX_BYTES): Boolean =
        totalBytes + OVERHEAD_BYTES <= maxBytes

    /**
     * A sane cap from whatever the carrier config said.
     *
     * A zero, a negative or an absurd value all mean the same thing — nobody answered — and the
     * default is a better guess than any of them. The ceiling is there because a config claiming
     * 10MB is a config nobody tested.
     */
    fun clampCarrierMax(reported: Int): Int = when {
        reported < MINIMUM_USEFUL_BYTES -> DEFAULT_MAX_BYTES
        reported > MAX_PLAUSIBLE_BYTES -> MAX_PLAUSIBLE_BYTES
        else -> reported
    }

    /** No carrier delivers more than this, whatever its config file says. */
    const val MAX_PLAUSIBLE_BYTES = 2 * 1024 * 1024
}
