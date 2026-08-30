package com.people.app.partner

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * What two paired instances exchange, and — more importantly — what they do **not**.
 *
 * A partner's week is *theirs*. It arrives here as a **mirror**: a read-only copy of the week they
 * published, kept in People's own tables and shown on People's own screen. It is never folded into
 * this household's LifeOps. That is the line the whole feature is drawn around, because the failure
 * it prevents is the obvious one — your partner's twenty tasks landing on your planner, in your
 * aspects, against your capacity, the first time you paired with anybody.
 *
 * Exactly one thing crosses that line, and only because a person asked for it by name: a
 * [Contribution] — a task you deliberately added *onto their* week while looking at it. Their
 * instance creates that one task in their LifeOps and it becomes an ordinary task of theirs. Nothing
 * else in an envelope is ever written to a planner.
 *
 * The scope is one week, and that is a feature too: it ages out on its own. Close the week and last
 * week's sharing stops being published without anybody having to revoke anything.
 */

/**
 * One task on a partner's published week, as we mirror it.
 *
 * Deliberately far smaller than a LifeOps `Task`. Aspects, categories, priorities, resource values,
 * estimates, carry-forward chains and notes all stay home: they are how *that* household runs its
 * week, they mean nothing in ours, and publishing them would invite exactly the merging this design
 * refuses. What crosses is what a person needs to see somebody else's week: what, when, and whether
 * it is done.
 *
 * [fromContribution] is set by the publisher on a task that began life as somebody's contribution to
 * their week. It is how the contributor recognises their own suggestion once it has landed, so the
 * view can stop showing it as pending without either side having to send an acknowledgement.
 */
data class SharedTask(
    val taskId: String,
    val title: String,
    /** ISO `yyyy-MM-dd`. Null = "some time this week", which LifeOps allows and people use. */
    val dueDate: String? = null,
    val done: Boolean = false,
    val fromContribution: String? = null
)

/** A partner's week: the window, and what is on it. */
data class SharedWeek(
    val weekStart: String,
    val weekEnd: String,
    val tasks: List<SharedTask> = emptyList()
)

/**
 * A task one side is adding onto the *other* side's week.
 *
 * The only thing on this seam that becomes a real task in somebody's planner, which is why it is its
 * own type rather than a [SharedTask] with a flag. Making it a distinct thing on the wire means the
 * receiving code path that writes into LifeOps is reached by exactly one kind of payload, and every
 * other field of every other message is structurally incapable of getting there.
 *
 * [id] is minted by the contributor and is what the receiver dedupes on. That id is remembered for
 * good on the receiving side: taking a contribution twice would put a second copy on somebody's week
 * every time they opened the app, and a contribution that was taken and then deleted was *decided
 * about* — handing it back would be the app arguing with them.
 */
data class Contribution(
    val id: String,
    val title: String,
    val dueDate: String? = null,
    val createdAt: Long = 0L
)

/**
 * One partner's slice of an envelope: my week for you, and what I have added to yours.
 *
 * An instance may be paired with several people, and each pairing is addressed separately — pairing
 * with your sister does not put her week in front of your neighbour.
 *
 * [token] is [PairSecret.token]: the sender asserting it holds *both* halves of this pairing's
 * secret. The receiver recomputes it and drops the share if it does not match, which is what stops
 * anyone who learned an instance id from posting a week — or a task — into somebody's app.
 */
data class PartnerShare(
    val partnerInstanceId: String,
    val token: String,
    val week: SharedWeek,
    val contributions: List<Contribution> = emptyList()
)

/** What one instance publishes: who it is, and a share for each partner it has scanned. */
data class PartnerEnvelope(
    val instanceId: String,
    val displayName: String,
    val issuedAt: Long = 0L,
    val shares: List<PartnerShare> = emptyList()
) {
    fun shareFor(partnerInstanceId: String): PartnerShare? =
        shares.firstOrNull { it.partnerInstanceId == partnerInstanceId }
}

/**
 * Where a week begins.
 *
 * Monday, matching `com.lifeops.app.util.DateUtil.weekStartFor`. The two households are looking at
 * the same seven days, and a partner week that started on a Sunday would sit an argument apart from
 * the week LifeOps is actually planning.
 */
object SharedWeeks {

    fun startOf(date: LocalDate): LocalDate = date.with(DayOfWeek.MONDAY)

    fun windowFor(date: LocalDate): SharedWeek {
        val start = startOf(date)
        return SharedWeek(weekStart = start.toString(), weekEnd = start.plusDays(6).toString())
    }

    /** The seven days of the week starting at [weekStart], for the day-by-day view. */
    fun daysOf(weekStart: String): List<LocalDate> =
        runCatching { LocalDate.parse(weekStart) }.getOrNull()
            ?.let { start -> (0L..6L).map { start.plusDays(it) } }
            .orEmpty()

    /** Whether [weekStart] is the week we are in now — the only week this seam ever publishes. */
    fun isCurrent(weekStart: String, today: LocalDate = LocalDate.now()): Boolean =
        weekStart == startOf(today).toString()
}
