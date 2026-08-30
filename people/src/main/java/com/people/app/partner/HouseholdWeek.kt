package com.people.app.partner

/**
 * This household's own week, as the partner seam needs it — declared here and implemented elsewhere.
 *
 * The partner seam has two ends that both involve a planner: it publishes **this** household's
 * current week to a paired partner, and it puts a task the partner added onto that same week. LifeOps
 * owns the week, so the obvious shape would be a call from People into LifeOps.
 *
 * That call cannot be a dependency. `:lifeops` already depends on `:people` — it mints people from
 * calendar attendees and publishes them over the directory seam — so an edge back would be a module
 * cycle. So People declares the port and LifeOps registers the adapter (`people/PartnerWeekBridge`
 * on its side, wired in `LifeOpsApp`), which is the same direction every other call between the two
 * already runs.
 *
 * The narrowness is not only about the cycle, and would be worth keeping even without it. These two
 * methods are the entire surface between a stranger's envelope and this household's planner:
 *
 * - **[current] reads.** It is the only thing a partner is ever shown, and it is the real week,
 *   published whole — which is why pairing is a deliberate, per-person act, and why the screen that
 *   hands over the code says so.
 * - **[createTask] writes, once per contribution.** It is reached only from
 *   [PartnerSyncEngine.Applied.accepted], which is populated only after a share has proved it holds
 *   both halves of the pairing secret. A mirrored week — the bulk of what arrives — has no path to it
 *   at all.
 */
interface HouseholdWeek {

    /**
     * This household's current week, reduced to what a partner is shown.
     *
     * Aspects, priorities, resource values, estimates, notes and carry-forward chains stay home:
     * they are how *this* household runs its week, they mean nothing in somebody else's, and putting
     * them on the wire would invite the merging this design refuses.
     */
    suspend fun current(): SharedWeek

    /**
     * Put a partner's contribution on this household's week, returning the created task's id.
     *
     * Null means the planner declined it — the caller still records the contribution as taken, since
     * a contribution offered and refused has been decided about.
     */
    suspend fun createTask(contribution: Contribution, partnerName: String): String?
}

/**
 * Where the adapter is left for People to find.
 *
 * A settable holder rather than constructor injection because the two modules are wired in opposite
 * directions and at different moments: People's container is built when its first screen opens, and
 * LifeOps registers during its own startup, which may be before or after. A holder makes the order
 * irrelevant — and its default is honest about the case where LifeOps was never installed at all.
 */
object HouseholdWeeks {

    @Volatile
    private var registered: HouseholdWeek? = null

    /** Called once by whoever owns the week — LifeOps, in this suite. */
    fun register(week: HouseholdWeek) {
        registered = week
    }

    /**
     * The week, or null in a host with no planner in it.
     *
     * Null is a normal answer, not a failure. The partner seam then degrades honestly rather than
     * throwing: a paired household publishes an empty week, and an arriving contribution is left
     * **untaken** rather than dropped, so it lands if a planner ever appears.
     */
    fun current(): HouseholdWeek? = registered
}
