package com.people.app.partner

/**
 * One round of partner sync, as a pure function of what both sides published.
 *
 * The shape follows [com.people.app.sync.PeopleSyncEngine] deliberately — build an outbound
 * envelope, fold an inbound one, transport outside — but the rules are not the same, because the
 * peer is not the same. On the People seam every peer is part of this install and its envelopes are
 * taken on sight. A partner is a stranger's device addressed by an id that travelled on a QR code,
 * so **every** inbound share is checked before a single line of it is believed:
 *
 *  1. the envelope must come from the instance this link names;
 *  2. the share must be addressed to us, specifically;
 *  3. it must carry the pairing token — which the sender can only compute if it holds both halves of
 *     the secret, i.e. if it really did scan us back;
 *  4. its week must be the week we are actually in.
 *
 * A share failing any of those is dropped with a reason rather than partly applied. Being strict
 * here is what lets everything downstream stay simple: by the time anything reaches the mirror — or,
 * for a contribution, LifeOps — the question "is this person allowed to write here?" is settled.
 */
class PartnerSyncEngine(
    private val instanceId: String,
    private val displayName: String
) {

    /**
     * A pairing as the engine sees it: who they are, and the two halves of the secret.
     *
     * [partnerSecret] is empty until their code has been scanned. A link in that state publishes
     * nothing and reads nothing, because it cannot compute the token — which is exactly what a
     * half-made pairing should do, rather than a special case anyone has to remember.
     */
    data class Link(
        val partnerInstanceId: String,
        val partnerName: String,
        val mySecret: String,
        val partnerSecret: String
    ) {
        val scanned: Boolean get() = partnerSecret.isNotBlank()

        val token: String get() = if (scanned) PairSecret.token(mySecret, partnerSecret) else ""
    }

    /** Why a share was not applied. Each names something the user can act on. */
    enum class Rejection {
        /** They have not published anything yet, or their envelope has not reached this folder. */
        NOTHING_PUBLISHED,

        /** The envelope in their name was written by a different instance. */
        WRONG_INSTANCE,

        /** They publish, but not to us: they have not scanned our code yet. */
        NOT_ADDRESSED_TO_US,

        /** Addressed to us, but the token does not match the halves we hold. The pairing is stale. */
        TOKEN_MISMATCH,

        /** Their week is not ours — one side has not opened the app since the week turned over. */
        DIFFERENT_WEEK
    }

    /**
     * One round's outcome.
     *
     * [mirror] is their week as we now hold it — a copy, for People's screen, and for nothing else.
     * [accepted] is the entirely separate matter of tasks they added onto *our* week, which the
     * caller creates in LifeOps. Keeping the two apart in the return type is not tidiness: it is the
     * reason no code path can accidentally write a mirrored week into a planner.
     *
     * [confirmed] is the handshake completing — reported separately from [changes] because it is
     * news in its own right ("you are connected now") on a round that may carry nothing else.
     */
    data class Applied(
        val mirror: SharedWeek,
        val changes: List<PartnerWeekDiff.Change>,
        val accepted: List<Contribution>,
        val confirmed: Boolean,
        val rejection: Rejection? = null
    )

    /**
     * Our half of one pairing: our own week, and what we have added to theirs.
     *
     * [myWeek] is this household's real LifeOps week, read fresh each round by the caller. It is
     * published whole — a partner sees the week, not a curated subset — which is worth being blunt
     * about in the UI, and is why pairing is a per-person, opt-in act rather than a setting.
     */
    fun buildShare(link: Link, myWeek: SharedWeek, contributions: List<Contribution>): PartnerShare? {
        if (!link.scanned) return null
        return PartnerShare(
            partnerInstanceId = link.partnerInstanceId,
            token = link.token,
            week = myWeek,
            contributions = contributions
        )
    }

    /** The envelope this instance publishes: one share per partner it has scanned. */
    fun buildOutbound(shares: List<PartnerShare>, at: Long): PartnerEnvelope = PartnerEnvelope(
        instanceId = instanceId,
        displayName = displayName,
        issuedAt = at,
        shares = shares
    )

    /**
     * Fold the partner's envelope in.
     *
     * @param previousMirror what we held of their week before this round — the baseline the reported
     *   changes are measured against, which is what makes "since you last looked" mean anything.
     * @param ourWeek the week *we* are in. Their share has to be about the same one.
     * @param takenContributionIds contributions we have already created in LifeOps. Passed in rather
     *   than inferred, because the durable answer lives in a table and the engine must not guess it:
     *   a contribution whose task was later deleted is still taken, and offering it again would be
     *   the app overruling somebody who already decided about it.
     *
     * Idempotent. Re-applying an envelope adopts the same mirror, reports no changes, and accepts no
     * contributions — which it has to be, because a round runs on every app open.
     */
    fun applyInbound(
        link: Link,
        envelope: PartnerEnvelope?,
        previousMirror: SharedWeek,
        ourWeek: SharedWeek,
        takenContributionIds: Set<String>
    ): Applied {
        fun held(rejection: Rejection, confirmed: Boolean = false) =
            Applied(previousMirror, emptyList(), emptyList(), confirmed, rejection)

        if (!link.scanned || envelope == null) return held(Rejection.NOTHING_PUBLISHED)
        if (envelope.instanceId != link.partnerInstanceId) return held(Rejection.WRONG_INSTANCE)

        val share = envelope.shareFor(instanceId) ?: return held(Rejection.NOT_ADDRESSED_TO_US)
        if (share.token != link.token) return held(Rejection.TOKEN_MISMATCH)

        // Past this line the pairing is proven, so anything they have added to our week is theirs to
        // add — even if their own week turns out to be one we cannot show. Those are separate facts:
        // "they haven't opened the app since Monday" is a different problem from "that pairing never
        // completed", and a task they wrote for us should not be held hostage to the first.
        val accepted = share.contributions.filter { it.id !in takenContributionIds }

        if (share.week.weekStart != ourWeek.weekStart) {
            return Applied(previousMirror, emptyList(), accepted, confirmed = true, rejection = Rejection.DIFFERENT_WEEK)
        }

        val mirror = share.week.copy(tasks = PartnerWeekDiff.sorted(share.week.tasks))

        // A week we have never mirrored is not a week of changes. On the first round of a new week —
        // or the first round after pairing — every task they hold would otherwise be announced as
        // something they *just did*, which is both untrue and the most likely moment for the
        // notification to be long enough that nobody reads the next one.
        val baselineIsThisWeek = previousMirror.weekStart == mirror.weekStart
        val changes =
            if (baselineIsThisWeek) PartnerWeekDiff.diff(previousMirror.tasks, mirror.tasks) else emptyList()

        return Applied(mirror, changes, accepted, confirmed = true)
    }
}
