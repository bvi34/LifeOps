package com.operations.suitekit

/**
 * What an app makes of something a person has just picked.
 *
 * The pickers used to enforce their own bounds — a calendar that would not offer a future day, and
 * an error message baked into the control. That was wrong twice over. It assumed every app answers
 * the same question the same way, when they plainly do not: a date next Tuesday is a plan in
 * LifeOps and a typo in Health. And it only had two answers, when the interesting one is the third:
 *
 * - **Fine** — nothing to say.
 * - **Note** — allowed, and worth saying out loud. *"Recorded as added late."* A choice that is
 *   accepted but not silent. Bounds cannot express this at all, which is why so much of it ended up
 *   as a comment in a repository that quietly dropped the write instead.
 * - **Refused** — not allowed here, and **why**. The reason is required: a disabled button with no
 *   explanation is a bug report waiting to be filed.
 *
 * So the picker offers everything and asks. The app owns the rule, states it in its own words, and
 * two apps can disagree about the same Tuesday without either of them forking the control.
 */
sealed class SuiteVerdict {

    /** Nothing to say about this choice. */
    data object Fine : SuiteVerdict()

    /** Allowed, with a remark the person should see before and after they confirm. */
    data class Note(val message: String) : SuiteVerdict()

    /** Not allowed, and why. Confirming is blocked until the choice changes. */
    data class Refused(val reason: String) : SuiteVerdict()

    /** The line to show, if any — a note's remark or a refusal's reason. */
    val text: String?
        get() = when (this) {
            is Fine -> null
            is Note -> message
            is Refused -> reason
        }

    /** Whether the choice may be confirmed. */
    val allowed: Boolean get() = this !is Refused
}
