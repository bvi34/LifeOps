package com.utilities.app.shelf

/**
 * The shelf: one entry per part of the phone Utilities is offering to take over.
 *
 * ## Why this app exists
 *
 * Every other app in this suite replaces a *service* — a planner, a shelf of documents, a vault.
 * This one replaces a **piece of the operating system**, and the reason is the same each time: the
 * stock component is fine, and it reports to somebody else. A keyboard sees every password, every
 * message and every search typed on the phone, and the two that ship on most handsets both send
 * what they see somewhere to improve themselves. A messaging app sees every conversation. Neither
 * of those is a thing the household is choosing to publish.
 *
 * So the shelf is not a list of features. It is a list of **places the phone leaks**, each with the
 * state of whether Utilities has plugged it, and each plugged by the same mechanism: Android already
 * lets a third-party app be the keyboard or the messenger, behind a switch in system settings that
 * nobody can find. This app is the screen that finds it, plus the replacement it hands over to.
 *
 * ## Adding the third one
 *
 * An entry here, a [TakeoverState] resolver on the Android side, and a screen. Nothing else in the
 * suite has to know — the home tile, the backup slice and the settings route are all already this
 * app's. The two shipped here are the two that leak the most; the dialler, the launcher, the
 * clipboard and the share sheet are all takeovers Android permits and none of them is wired yet.
 */
enum class Utility(
    val id: String,
    val title: String,
    /** One line, on the tile. What it replaces, not what it does. */
    val blurb: String,
    /** What is actually stopped by turning it on. This is the sentence the shelf leads with. */
    val leak: String
) {
    KEYBOARD(
        id = "keyboard",
        title = "Keyboard",
        blurb = "The keys, drawn here instead",
        leak = "A keyboard reads every password, message and search typed on this phone. " +
            "This one has no way to send them anywhere: no network permission, and nothing learned " +
            "leaves the device."
    ),
    MESSAGES(
        id = "messages",
        title = "Messages",
        blurb = "Your threads, in a window you chose",
        leak = "Texts are already on this phone. What changes is who draws them, who is told when " +
            "one arrives, and whether the app doing it also wants an account."
    );

    companion object {
        fun byId(id: String): Utility? = entries.firstOrNull { it.id == id }
    }
}

/**
 * How far a takeover has got.
 *
 * [PARTIAL] is the state that earns this enum. Both of the shipped takeovers have a halfway house
 * that is genuinely useful and genuinely not the whole thing — a keyboard that is installed but not
 * selected, a message reader that draws the threads while the carrier's app still owns delivery —
 * and collapsing that into "off" would make the shelf lie about a phone that is already half done.
 */
enum class TakeoverState {
    /** Nothing is set up. */
    OFF,

    /** Some of it is working, and the shelf can say which part is missing. */
    PARTIAL,

    /** Utilities is the one doing this job. */
    ON,

    /** This phone cannot do it at all — no SIM, no telephony, a managed profile that forbids it. */
    UNAVAILABLE
}

/**
 * One row on the shelf: what state the takeover is in, what that means in a sentence, and the one
 * thing tapping it should do next.
 *
 * [nextStep] is null exactly when there is nothing left to do, which is what the UI keys off rather
 * than comparing states — a row that is [TakeoverState.ON] and still has a step (Messages, with
 * contacts not granted) must still offer it.
 */
data class TakeoverStatus(
    val utility: Utility,
    val state: TakeoverState,
    val detail: String,
    val nextStep: String? = null
)

/**
 * What each takeover's state *is*, given the handful of facts the platform can be asked for.
 *
 * Deliberately pure: the Android side (see `shelf/PhoneFacts`) does nothing but read those booleans
 * out of `InputMethodManager`, `RoleManager` and the permission checker, and every judgement about
 * what they add up to — including the wording, which is the part most likely to be wrong — is
 * settled here where it can be tested.
 */
object Takeovers {

    /**
     * The keyboard.
     *
     * Two switches, in order, and they are genuinely separate: Android makes you *enable* an input
     * method (a warning dialog about what a keyboard can see, which is the operating system being
     * right) before it will let you *select* it. A household that did the first and not the second
     * has a keyboard installed and is still typing on the old one, which looks from the outside
     * exactly like nothing having happened — so that is the state the shelf is loudest about.
     */
    fun keyboard(enabled: Boolean, selected: Boolean): TakeoverStatus = when {
        selected -> TakeoverStatus(
            utility = Utility.KEYBOARD,
            state = TakeoverState.ON,
            detail = "You are typing on it now."
        )

        enabled -> TakeoverStatus(
            utility = Utility.KEYBOARD,
            state = TakeoverState.PARTIAL,
            detail = "Switched on in system settings, but the phone is still using another keyboard.",
            nextStep = "Switch to it"
        )

        else -> TakeoverStatus(
            utility = Utility.KEYBOARD,
            state = TakeoverState.OFF,
            detail = "Not switched on yet. Android asks first, and it is right to.",
            nextStep = "Switch it on"
        )
    }

    /**
     * Messages, which has two rungs rather than two switches, and the difference matters enough to
     * be modelled rather than explained in a tooltip.
     *
     * **Reading** needs one permission and changes nothing else about the phone: the threads are
     * already in the system's own store, and Utilities draws them. The carrier's app keeps
     * delivering, keeps notifying, keeps handling picture messages. This is the rung that costs
     * nothing and is worth having on its own — it is the one that answers "I want my own window".
     *
     * **Default** is the whole job: arriving texts are handed to this app, it stores them, it
     * notifies. Android gives it out through a role, all at once, and it is the rung where what
     * this app does *not* do yet starts to matter — see [mmsWarning].
     */
    fun messages(
        canRead: Boolean,
        canSend: Boolean,
        isDefault: Boolean,
        hasTelephony: Boolean = true,
        canReadContacts: Boolean = true
    ): TakeoverStatus {
        if (!hasTelephony) return TakeoverStatus(
            utility = Utility.MESSAGES,
            state = TakeoverState.UNAVAILABLE,
            detail = "This device has no telephony, so there are no texts to take over."
        )
        return when {
            isDefault && canRead -> TakeoverStatus(
                utility = Utility.MESSAGES,
                state = TakeoverState.ON,
                detail = if (canReadContacts) {
                    "Texts arrive here, and this app is the one that tells you about them."
                } else {
                    "Texts arrive here. Without contacts, threads are titled by number."
                },
                nextStep = if (canReadContacts) null else "Allow contacts"
            )

            canRead -> TakeoverStatus(
                utility = Utility.MESSAGES,
                state = TakeoverState.PARTIAL,
                detail = if (canSend) {
                    "Reading and replying here. Your carrier's app still delivers and notifies, " +
                        "and pictures cannot be sent until this app holds the job."
                } else {
                    "Reading here. Sending needs one more permission."
                },
                nextStep = if (canSend) "Make it the default" else "Allow sending"
            )

            else -> TakeoverStatus(
                utility = Utility.MESSAGES,
                state = TakeoverState.OFF,
                detail = "Not reading your threads yet.",
                nextStep = "Allow messages"
            )
        }
    }

    /**
     * What is said before the role picker opens.
     *
     * A constant rather than a string in a Compose file, for the same reason the earlier version of
     * it was: taking over somebody's messaging is the most consequential thing this app does, and it
     * has to be impossible to change the behaviour without walking past the sentence that describes
     * it.
     *
     * It leads with what is **reversible**, because that is the fact that makes the decision easy
     * and the fact nobody believes without being told: nothing is moved, nothing is copied, and the
     * messages stay in the same place the carrier's app keeps them. Switching back is one tap in
     * system settings and loses nothing.
     */
    const val defaultAppNote: String =
        "Utilities will receive your texts and picture messages, store them, and be the app that " +
            "tells you when one arrives. Nothing moves: every message stays where Android keeps " +
            "it, your old app keeps all of them, and switching back in system settings undoes " +
            "this immediately."
}
