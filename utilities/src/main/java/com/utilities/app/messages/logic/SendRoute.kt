package com.utilities.app.messages.logic

/**
 * Which kind of message to send, which is a decision and not a detail.
 *
 * A text and a picture message are different protocols with different costs, different size limits
 * and different failure modes, and the choice between them is made from four facts. Getting it wrong
 * is visible: send a group message as texts and everybody gets a private note with no idea the
 * others were written to; send a one-line reply as an MMS and it counts against an allowance nobody
 * meant to spend.
 *
 * Pure, and tested, because the rule has four inputs and one of them — being the default messaging
 * app — is the kind of thing that gets forgotten in a `when`.
 */
enum class SendRoute {
    /** SMS. One recipient, words only. */
    TEXT,

    /** MMS. Pictures, several recipients, or a subject line. */
    PICTURE,

    /** Neither is possible, and the caller has to say why. */
    REFUSE
}

/** Why a message cannot be sent, in the words the composer shows. */
enum class SendRefusal(val reason: String) {
    NOBODY("There is nobody to send that to."),
    EMPTY("There is nothing to send."),
    NEEDS_DEFAULT(
        "Sending a picture needs Utilities to be your messaging app — only the app holding that " +
            "job may record one in your history, and a photograph that vanished the moment you " +
            "sent it would be worse than this message."
    ),
    NO_PERMISSION("Sending needs the SMS permission.")
}

/** What the composer is allowed to do, and why not when it is not. */
data class SendPlan(val route: SendRoute, val refusal: SendRefusal? = null)

object Routing {

    /**
     * How to send this.
     *
     * The rules, in the order they are applied:
     *
     *  1. **nothing to send, or nobody to send it to** — refuse, and say which;
     *  2. **no permission** — refuse; this is the reading rung with sending not granted;
     *  3. **attachments** — a picture message, and one that needs the default-app role, because only
     *     the app holding it may write a sent MMS into the store. A photograph that disappears the
     *     instant it is sent is worse than being told to switch;
     *  4. **several recipients** — a picture message when the household asked for group threads,
     *     and several separate texts when they did not. Both are defensible, which is why it is a
     *     setting;
     *  5. **a subject line** — a picture message. SMS has nowhere to put one;
     *  6. otherwise a text.
     */
    fun plan(
        recipients: Int,
        attachments: Int,
        hasBody: Boolean,
        hasSubject: Boolean = false,
        groupAsPicture: Boolean = true,
        canSend: Boolean = true,
        isDefaultApp: Boolean = false
    ): SendPlan {
        if (recipients <= 0) return SendPlan(SendRoute.REFUSE, SendRefusal.NOBODY)
        if (!hasBody && attachments <= 0) return SendPlan(SendRoute.REFUSE, SendRefusal.EMPTY)
        if (!canSend) return SendPlan(SendRoute.REFUSE, SendRefusal.NO_PERMISSION)

        if (attachments > 0) {
            return if (isDefaultApp) SendPlan(SendRoute.PICTURE)
            else SendPlan(SendRoute.REFUSE, SendRefusal.NEEDS_DEFAULT)
        }

        val group = recipients > 1
        if (group && groupAsPicture) {
            return if (isDefaultApp) SendPlan(SendRoute.PICTURE)
            // Without the role, a group message still goes — as a text to each person. That is what
            // every phone did before group messaging existed, and it is better than not sending.
            else SendPlan(SendRoute.TEXT)
        }
        if (hasSubject) {
            return if (isDefaultApp) SendPlan(SendRoute.PICTURE) else SendPlan(SendRoute.TEXT)
        }
        return SendPlan(SendRoute.TEXT)
    }
}
