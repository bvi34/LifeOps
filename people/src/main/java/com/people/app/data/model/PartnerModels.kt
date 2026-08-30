package com.people.app.data.model

import com.people.app.partner.PartnerWeekDiff

/**
 * What the partner screens work in.
 *
 * The split from `partner/` is the same one the rest of this app makes: the `partner` package is the
 * seam's *rules* — framework-free, JVM-tested, and shared with whatever is on the other end of the
 * wire — while these are the shapes a Compose screen wants, with the enums resolved and the
 * derived questions already answered.
 */

/** How far a pairing has got. */
enum class PartnerLinkState {
    /** We have shown our code; nobody has scanned it and we have not scanned theirs. */
    AWAITING_SCAN,

    /** We have scanned theirs, but their app has not scanned ours back yet. */
    AWAITING_THEM,

    /** Both scans done, and their envelope has come back bearing the pairing token. */
    LINKED
}

/**
 * One pairing, as the person detail screen shows it.
 *
 * [lastRejection] is deliberately carried through to the UI rather than swallowed. "Nothing has
 * arrived" and "their week is last week's" and "that pairing is stale, re-scan" all look identical
 * from the outside — an empty screen — and each needs a different thing done about it.
 */
data class PartnerLink(
    val id: String,
    val personId: String,
    val partnerInstanceId: String,
    val partnerName: String,
    val state: PartnerLinkState,
    val lastSyncAt: Long?,
    val lastRejection: String?,
    val unseenChanges: Int
)

/** One task on a partner's week, as we last mirrored it. */
data class PartnerTask(
    val taskId: String,
    val title: String,
    val dueDate: String?,
    val done: Boolean,
    /** True when this is a task we added to their week and it has landed. */
    val addedByUs: Boolean
)

/**
 * A task added to their week that has not landed yet.
 *
 * Shown beside the mirrored week rather than in it, and labelled as pending, because until their app
 * has taken it, it is a thing we have said rather than a thing on their week — and a partner who has
 * not opened their app for two days should not have that look like a task they are ignoring.
 */
data class PartnerPendingTask(
    val id: String,
    val title: String,
    val dueDate: String?
)

/** What a partner did, in the words the notification uses. */
enum class PartnerEventKind(val key: String) {
    ADDED("added"),
    EDITED("edited"),
    COMPLETED("completed"),
    REOPENED("reopened"),
    REMOVED("removed"),

    /** They added something to *our* week, and it is now a task in LifeOps. */
    ADDED_TO_OUR_WEEK("added_to_ours"),

    /** The handshake completed — the one event that is about the link rather than the week. */
    CONNECTED("connected");

    companion object {
        fun fromKey(key: String): PartnerEventKind = entries.firstOrNull { it.key == key } ?: EDITED

        fun of(kind: PartnerWeekDiff.ChangeKind): PartnerEventKind = when (kind) {
            PartnerWeekDiff.ChangeKind.ADDED -> ADDED
            PartnerWeekDiff.ChangeKind.EDITED -> EDITED
            PartnerWeekDiff.ChangeKind.COMPLETED -> COMPLETED
            PartnerWeekDiff.ChangeKind.REOPENED -> REOPENED
            PartnerWeekDiff.ChangeKind.REMOVED -> REMOVED
        }
    }
}

/** One line of "here is what happened since you last looked". */
data class PartnerEvent(
    val id: String,
    val kind: PartnerEventKind,
    val title: String,
    val at: Long,
    val seen: Boolean
)

/** One partner round's outcome, as the screens report it. */
data class PartnerSyncStatus(
    val ranAt: Long,
    val linksSynced: Int,
    val changes: Int,
    /** Contributions from partners that became tasks on this household's LifeOps week. */
    val takenOntoOurWeek: Int,
    val error: String? = null
)
