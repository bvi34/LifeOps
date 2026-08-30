package com.maintenance.app.logic

/**
 * Turning a schedule pack into an asset's plans.
 *
 * The whole of the interesting behaviour is what happens the *second* time. A pack gets applied
 * again — you switch from the generic list to a proper one, or a later build of the app adds items
 * to a pack you already have — and the answer that keeps the feature usable is: **add what is
 * missing, touch nothing else.**
 *
 * Nothing is ever updated in place and nothing is deleted. An interval you tuned, a plan you paused,
 * a title you rewrote: all of it survives, because the moment a pack is applied its items stop being
 * the manufacturer's and become yours. The pack is a starting point, not a policy the app keeps
 * re-imposing — that is the difference between a template and a manager.
 *
 * An item counts as already present if a plan carries its **provenance** (the pack id and item key
 * it came from), or — for the case where somebody typed "Engine oil & filter" by hand before
 * applying anything — if a plan on the asset already has that **title**. Adopting an existing row
 * rather than adding a second beside it is the same call this codebase makes about a LifeOps task
 * you had already written onto your week.
 */
object SchedulePlans {

    /** What applying a pack would do, worked out before anything is written. */
    data class Application(
        val pack: SchedulePack,
        /** Items with no plan yet: these become plans. */
        val toCreate: List<ScheduleItem>,
        /** Items already covered, and the plan that covers each. */
        val alreadyThere: List<Pair<ScheduleItem, UpkeepPlan>>
    ) {
        val isNoOp: Boolean get() = toCreate.isEmpty()

        /** "9 to add, 3 already here" — the line the confirm button sits under. */
        val summary: String
            get() = when {
                toCreate.isEmpty() && alreadyThere.isEmpty() -> "Nothing in this schedule"
                toCreate.isEmpty() -> "Every item is already here"
                alreadyThere.isEmpty() -> "${toCreate.size} to add"
                else -> "${toCreate.size} to add · ${alreadyThere.size} already here"
            }
    }

    fun plan(pack: SchedulePack, existing: List<UpkeepPlan>): Application {
        val byProvenance = existing
            .filter { it.sourcePack == pack.id && it.sourceItem != null }
            .associateBy { it.sourceItem }
        val byTitle = existing.associateBy { it.title.trim().lowercase() }

        val create = mutableListOf<ScheduleItem>()
        val present = mutableListOf<Pair<ScheduleItem, UpkeepPlan>>()

        pack.items.forEach { item ->
            val match = byProvenance[item.key] ?: byTitle[item.title.trim().lowercase()]
            if (match != null) present += item to match else create += item
        }

        return Application(pack, create, present)
    }

    /**
     * The plan an item becomes.
     *
     * [now] is the plan's creation stamp, which is also where a date interval starts counting from —
     * so applying a schedule on a Tuesday does not declare half of it overdue on the Tuesday.
     */
    fun toPlan(item: ScheduleItem, pack: SchedulePack, assetId: String, planId: String, now: Long): UpkeepPlan =
        UpkeepPlan(
            id = planId,
            assetId = assetId,
            title = item.title,
            notes = item.notes,
            everyDays = item.everyDays,
            everyMeter = item.everyMeter,
            atMeter = item.atMeter,
            createdAt = now,
            active = true,
            kind = item.kind,
            sourcePack = pack.id,
            sourceItem = item.key
        )
}
