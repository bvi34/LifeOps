package com.utilities.app.messages.logic

import kotlin.math.abs

/**
 * A message this app sent that the system's store does not have.
 *
 * ## Why this exists at all
 *
 * Only the **default SMS app** may write to the message store. That is Android being right — an app
 * that could insert texts into your history could forge a conversation — and it leaves a real gap
 * in the rung of the Messages takeover that is worth having on its own: reading and replying while
 * the carrier's app stays the default. In that mode `SmsManager` will happily send the text, the
 * recipient receives it, and it appears nowhere, because nothing is allowed to record it. A reply
 * that vanishes the instant it is sent is worse than no reply button.
 *
 * So this app keeps its own small record of what *it* sent, shows those alongside the stored
 * messages, and throws each one away the moment the real store has it — which, on most phones,
 * happens within a few seconds, because the default app records outgoing texts it sees. The echo is
 * a bridge over those seconds, and over the phones where it never happens at all.
 *
 * Once Utilities *is* the default app, nothing is ever written here: it can record its own sent
 * messages properly, which is the whole difference between the two rungs.
 */
data class OutboxEntry(
    val address: String,
    val body: String,
    val at: Long,
    val failed: Boolean = false
)

/**
 * Folding the echoes into what the provider holds.
 *
 * Pure, because the matching rule — "the store now has this message" — is a judgement call with an
 * off-by-a-minute in it, and getting it wrong shows the household two copies of everything they
 * send. The rule: same recipient, same body once whitespace is normalised, and sent within
 * [SETTLE_WINDOW_MS] of each other.
 */
object Outbox {

    /**
     * How far apart an echo and the provider's copy of it may be.
     *
     * Generous, on purpose. The timestamp the store records is the carrier's, not the moment the
     * send button was pressed, and a phone that was out of signal for a minute when the text was
     * queued will show a gap. Two minutes is long enough to cover that and short enough that
     * somebody who deliberately sent the same three words twice sees both.
     */
    const val SETTLE_WINDOW_MS = 120_000L

    /** Whether [stored] is the provider's copy of [echo]. */
    fun matches(echo: OutboxEntry, stored: ChatMessage): Boolean =
        stored.outgoing &&
            Addresses.same(echo.address, stored.address) &&
            normalize(echo.body) == normalize(stored.body) &&
            abs(stored.at - echo.at) <= SETTLE_WINDOW_MS

    /**
     * The thread as it should be drawn: what the store has, plus the echoes it does not yet, in
     * time order.
     *
     * A failed echo is kept and shown as failed rather than dropped — the one thing worse than a
     * reply that disappears is a reply that disappears after failing.
     */
    fun merge(stored: List<ChatMessage>, echoes: List<OutboxEntry>, threadId: Long): List<ChatMessage> {
        val unsettled = echoes.filterNot { echo -> stored.any { matches(echo, it) } }
        val drawn = unsettled.mapIndexed { index, echo ->
            ChatMessage(
                // Negative and distinct, so a list keyed by id has no collisions with the store's.
                id = -(index + 1L),
                threadId = threadId,
                address = echo.address,
                body = echo.body,
                at = echo.at,
                outgoing = true,
                pending = !echo.failed,
                failed = echo.failed
            )
        }
        return (stored + drawn).sortedBy { it.at }
    }

    /** The echoes the store has caught up with — the ones to delete. */
    fun settled(stored: List<ChatMessage>, echoes: List<OutboxEntry>): List<OutboxEntry> =
        echoes.filter { echo -> stored.any { matches(echo, it) } }

    /**
     * Echoes worth keeping at all.
     *
     * An entry older than [EXPIRY_MS] is dropped whether or not the store ever picked it up: by then
     * either the default app recorded it and the match failed for a reason this rule cannot fix, or
     * it was never going to. Keeping it forever would mean a thread that shows the same message
     * twice, for good.
     */
    fun live(echoes: List<OutboxEntry>, now: Long): List<OutboxEntry> =
        echoes.filter { now - it.at <= EXPIRY_MS }

    const val EXPIRY_MS = 24 * 60 * 60 * 1000L

    private fun normalize(body: String): String = body.trim().replace(WHITESPACE, " ")

    private val WHITESPACE = Regex("\\s+")
}
