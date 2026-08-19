package com.advisor.app.data.action

import android.content.Context
import com.advisor.app.logic.SourceApp
import com.advisor.app.logic.TaskCommand
import com.advisor.app.logic.TaskWriteResult
import com.advisor.app.logic.TaskWriter
import com.lifeops.app.LifeOpsApp
import com.lifeops.app.connection.ConnectionParams
import com.lifeops.app.connection.ConnectionResult
import com.lifeops.app.data.db.LifeOpsDatabase
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Creates a LifeOps task on the user's explicit instruction — the one thing Advisor writes into
 * another app.
 *
 * It does not touch LifeOps' tables itself. It resolves the target the user named ("life ops", "the
 * beacon project") to a real project/aspect/category and then calls LifeOps' own
 * `/v1/LifeOps/local/task/create` connection route, so the task gets the identical treatment a task
 * added from LifeOps' own UI gets: current week resolution, same-week duplicate-title protection,
 * resource-value scoring, queued-vs-pending placement and reminder scheduling. Nothing about task
 * policy is re-implemented here, and nothing is reported that the route didn't actually do.
 */
class LifeOpsTaskWriter(context: Context) : TaskWriter {

    private val appContext = context.applicationContext
    override val app = SourceApp.LIFEOPS

    override suspend fun create(command: TaskCommand): TaskWriteResult {
        // The route lives on LifeOps' runtime. Without it (a host that never installed LifeOps) there
        // is nothing to write to — say so rather than half-writing through the database.
        val lifeOps = LifeOpsApp.getOrNull()
            ?: return TaskWriteResult.Failed("LifeOps isn't running, so I couldn't add the task.")

        val db = LifeOpsDatabase.getInstance(appContext)
        val match = command.target?.let { resolve(db, it) }
        // A named target that matches nothing was probably part of the title ("… to the website"),
        // so keep the title the user actually typed.
        val title = if (command.target != null && match == null && command.titleWithTarget != null) {
            command.titleWithTarget
        } else {
            command.title
        }
        // Only worth mentioning when the name wasn't just part of the title after all.
        val unmatched = if (match == null && title == command.title) command.target else null

        val result = lifeOps.connectionDispatcher.dispatch(
            "/v1/LifeOps/local/task/create",
            ConnectionParams.of(
                "title" to title,
                "projectId" to match?.projectId,
                "aspectId" to match?.aspectId,
                "categoryId" to match?.categoryId,
                "priority" to (command.priority ?: "medium")
            )
        )

        return when (result) {
            is ConnectionResult.Failure -> TaskWriteResult.Failed(result.message)
            is ConnectionResult.Success -> {
                if (result["skipped"] == true) return TaskWriteResult.Duplicate(title)
                TaskWriteResult.Created(
                    title = title,
                    target = match?.label,
                    unmatchedTarget = unmatched,
                    weekLabel = currentWeekLabel(db)
                )
            }
        }
    }

    /** A resolved filing target: which project/aspect/category the task should hang off. */
    private data class Target(
        val label: String,
        val projectId: String? = null,
        val aspectId: String? = null,
        val categoryId: String? = null
    )

    /**
     * Resolve [name] as the user said it — against projects first (the most specific thing a task is
     * filed under), then aspects, then categories. A project also carries its aspect and category
     * across, so the task lands in the same group in This Week as the project's other tasks.
     */
    private suspend fun resolve(db: LifeOpsDatabase, name: String): Target? {
        val projects = db.projectDao().getAll().filter { it.status == ACTIVE }
        projects.match(name) { it.title }?.let {
            return Target(it.title, projectId = it.id, aspectId = it.aspectId, categoryId = it.categoryId)
        }
        val aspects = db.aspectDao().getAllSync().filter { !it.isArchived }
        aspects.match(name) { it.name }?.let {
            return Target(it.name, aspectId = it.id)
        }
        val categories = db.categoryDao().getAllSync().filter { !it.isArchived }
        categories.match(name) { it.name }?.let {
            return Target(it.name, aspectId = it.aspectId, categoryId = it.id)
        }
        return null
    }

    /**
     * Exact name first (ignoring case, spacing and punctuation, so "life ops" finds "Life Ops"), then
     * a *unique* partial hit — "beacon" for "Beacon Blog". Ambiguity resolves to nothing, so an unsure
     * match is reported back to the user instead of filing the task somewhere they didn't mean.
     */
    private fun <T> List<T>.match(name: String, label: (T) -> String): T? {
        val wanted = normalise(name)
        if (wanted.isEmpty()) return null
        firstOrNull { normalise(label(it)) == wanted }?.let { return it }
        val partial = filter { normalise(label(it)).contains(wanted) }
        return partial.singleOrNull()
    }

    private fun normalise(text: String): String =
        text.lowercase().filter { it.isLetterOrDigit() }

    /** "Aug 17 – Aug 23", for a confirmation that says which week the task actually landed in. */
    private suspend fun currentWeekLabel(db: LifeOpsDatabase): String? {
        val week = db.weekDao().getCurrentWeek() ?: return null
        return runCatching {
            val start = LocalDate.parse(week.startDate).format(WEEK_DAY)
            val end = LocalDate.parse(week.endDate).format(WEEK_DAY)
            "$start – $end"
        }.getOrNull()
    }

    private companion object {
        const val ACTIVE = "active"
        val WEEK_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d")
    }
}
