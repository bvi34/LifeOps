package com.advisor.app.data.source

import android.content.Context
import com.advisor.app.logic.KnowledgeDocument
import com.advisor.app.logic.SourceApp
import com.people.app.data.db.PeopleDatabase
import com.people.app.logic.CheckInKind
import com.people.app.logic.CheckIns
import com.people.app.logic.DateKind
import com.people.app.logic.ImportantDates
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Reads the household directory into [KnowledgeDocument]s: who is in it, how to reach them, the
 * dates that come round, the running notes kept about them, the **daily check-in** log — the form
 * each person is asked, the recent days in full and the rest as a shape — and the **partner seam**:
 * who this household is paired with, the week they published, and what has been sent to it.
 *
 * Two things are deliberate about the parts beyond the directory:
 *
 *  - **The check-in log is summarised, not enumerated.** A year of daily check-ins for four people
 *    is over a thousand near-identical documents that would drown every other source in the corpus;
 *    the recent days are indexed in full and the rest are characterised, which is what a question
 *    about them actually needs. This is the same bargain Health's readings strike.
 *  - **A pairing is indexed; its secrets never are.** `partner_links` holds the two halves that make
 *    the seam's token, and they are read here only to be left behind: what goes into the corpus is
 *    who the partner is and whether the link is working. A secret in a retrieval corpus is a secret
 *    one badly-grounded answer away from being read out loud.
 *
 * People is the one source whose rows are *replicated* rather than owned outright — LifeOps holds
 * its own copy of the same humans and the two reconcile over the sync seam. Advisor indexes
 * **People's** copy and not LifeOps' `persons` table, deliberately: indexing both would put two
 * documents about the same person into a corpus that has no idea they are the same person, and the
 * retriever would happily cite whichever is staler. LifeOps' source keeps to what only LifeOps
 * knows — the tasks and time that involve a person, not the person.
 */
class PeopleKnowledgeSource(context: Context) : KnowledgeSource {

    private val appContext = context.applicationContext
    override val source = SourceApp.PEOPLE

    override suspend fun load(): List<KnowledgeDocument> {
        val db = PeopleDatabase.getInstance(appContext)
        val dao = db.peopleDao()
        val today = LocalDate.now()
        val docs = ArrayList<KnowledgeDocument>()

        val people = dao.getAll()
        val names = people.associate { it.id to it.name }

        for (person in people) {
            docs += KnowledgeDocument(
                id = "people:person:${person.id}",
                source = source,
                kind = "person",
                title = person.name,
                body = buildString {
                    append("Household member: ").append(person.name)
                    person.relationship?.takeIf { it.isNotBlank() }?.let { append(" (").append(it).append(')') }
                    person.birthDate?.let { append(". Born ").append(it) }
                    person.email?.takeIf { it.isNotBlank() }?.let { append(". Email: ").append(it) }
                    person.phone?.takeIf { it.isNotBlank() }?.let { append(". Phone: ").append(it) }
                    person.note?.takeIf { it.isNotBlank() }?.let { append(". Note: ").append(it) }
                    // Whether they live here, which is the flag Health grows a profile from — and
                    // the difference between "who is in this house" and "who is in this directory".
                    if (!person.household) append(". Not part of the household itself.")
                    if (person.archived) append(". No longer in the active household list.")
                },
                timestamp = person.updatedAt
            )

            // A birth date is a recurring date like any other — surfaced here so "when is Ellie's
            // birthday" is answerable without the user having entered it twice.
            ImportantDates.monthDayOf(person.birthDate)?.let { monthDay ->
                ImportantDates.resolve(
                    personId = person.id,
                    label = "${person.name}'s birthday",
                    kind = DateKind.BIRTHDAY,
                    monthDay = monthDay,
                    year = ImportantDates.parseIso(person.birthDate)?.year,
                    from = today
                )?.let { upcoming ->
                    docs += KnowledgeDocument(
                        id = "people:birthday:${person.id}",
                        source = source,
                        kind = "date",
                        title = upcoming.label,
                        body = buildString {
                            append(upcoming.label).append(": ").append(upcoming.next)
                            append(" (").append(ImportantDates.describe(upcoming.daysUntil)).append(')')
                            upcoming.turning?.let { append(". Turning ").append(it) }
                        }
                    )
                }
            }
        }

        for (row in dao.getAllDates()) {
            val who = names[row.personId] ?: continue
            val upcoming = ImportantDates.resolve(
                personId = row.personId,
                label = row.label,
                kind = DateKind.fromKey(row.kind),
                monthDay = row.monthDay,
                year = row.year,
                from = today
            ) ?: continue
            docs += KnowledgeDocument(
                id = "people:date:${row.id}",
                source = source,
                kind = "date",
                title = row.label,
                body = buildString {
                    append(who).append(" — ").append(row.label)
                    append(" (").append(upcoming.kind.label.lowercase()).append("): ")
                    append(upcoming.next).append(" (")
                    append(ImportantDates.describe(upcoming.daysUntil)).append(')')
                    upcoming.turning?.let { append(". Number ").append(it) }
                    row.note?.takeIf { it.isNotBlank() }?.let { append(". Note: ").append(it) }
                }
            )
        }

        for (note in dao.getAllNotes()) {
            val who = names[note.personId] ?: continue
            docs += KnowledgeDocument(
                id = "people:note:${note.id}",
                source = source,
                kind = "person-note",
                title = "Note about $who",
                body = "Note about $who: ${note.content}",
                timestamp = note.createdAt
            )
        }

        docs += checkIns(db, names, today)
        docs += partners(db, names)

        return docs
    }

    /**
     * The daily check-in: the form, the recent days, and the shape of everything older.
     *
     * The form is its own document because "what do I even ask about her" is a question, and because
     * a day's answers read as labels-and-values that only make sense next to the questions that
     * produced them. Retired questions are named as retired rather than dropped: they are why six
     * months of answers exist at all, and an answer whose question has vanished is unreadable.
     */
    private suspend fun checkIns(
        db: PeopleDatabase,
        names: Map<String, String>,
        today: LocalDate
    ): List<KnowledgeDocument> {
        val dao = db.checkInDao()
        val fields = dao.allFields()
        val days = dao.allCheckIns()
        if (fields.isEmpty() && days.isEmpty()) return emptyList()

        val docs = ArrayList<KnowledgeDocument>()
        val fieldsById = fields.associateBy { it.id }
        val answersByCheckIn = dao.allAnswers().groupBy { it.checkInId }
        val daysByPerson = days.groupBy { it.personId }

        for ((personId, form) in fields.groupBy { it.personId }) {
            val who = names[personId] ?: continue
            val live = form.filter { !it.retired }
            docs += KnowledgeDocument(
                id = "people:check-in-form:$personId",
                source = source,
                kind = "check-in-form",
                title = "$who's daily check-in",
                body = buildString {
                    append("Daily check-in form for ").append(who).append(": ")
                    append(
                        live.joinToString("; ") { field ->
                            val kind = CheckInKind.fromKey(field.kind)
                            val options = CheckIns.decodeOptions(field.options)
                            buildString {
                                append(field.label).append(" (").append(kind.label.lowercase())
                                if (options.isNotEmpty()) append(": ").append(options.joinToString(", "))
                                append(')')
                            }
                        }.ifBlank { "no questions set" }
                    )
                    val retired = form.count { it.retired }
                    if (retired > 0) {
                        append(". ").append(retired)
                        append(" retired question(s) still name their old answers.")
                    }
                }
            )
        }

        for ((personId, recorded) in daysByPerson) {
            val who = names[personId] ?: continue
            val sorted = recorded.sortedByDescending { it.day }
            val parsed = recorded.mapNotNull { CheckIns.parseDay(it.day) }

            // The recent days in full — the ones a question is almost always about.
            for (checkIn in sorted.take(RECENT_DAYS)) {
                val answers = answersByCheckIn[checkIn.id].orEmpty().mapNotNull { answer ->
                    val field = fieldsById[answer.fieldId] ?: return@mapNotNull null
                    field.label to CheckIns.display(CheckInKind.fromKey(field.kind), answer.value)
                }
                val day = CheckIns.parseDay(checkIn.day)
                docs += KnowledgeDocument(
                    id = "people:check-in:${checkIn.id}",
                    source = source,
                    kind = "check-in",
                    title = "$who — ${checkIn.day}",
                    body = buildString {
                        append("Check-in for ").append(who).append(" on ").append(checkIn.day)
                        day?.let { append(" (").append(CheckIns.describeDay(it, today)).append(')') }
                        append(". ")
                        append(
                            if (answers.isEmpty()) "Recorded with nothing filled in."
                            else answers.joinToString(" · ") { (label, value) -> "$label: $value" }
                        )
                    },
                    timestamp = checkIn.updatedAt
                )
            }

            // Everything older as one shape: how long the habit has run, not a thousand rows of it.
            docs += KnowledgeDocument(
                id = "people:check-in-history:$personId",
                source = source,
                kind = "check-in-history",
                title = "$who's check-in history",
                body = buildString {
                    append("Check-in history for ").append(who).append(": ")
                    append(recorded.size).append(" day(s) recorded")
                    val first = parsed.minOrNull()
                    val last = parsed.maxOrNull()
                    if (first != null && last != null) {
                        append(", from ").append(first).append(" to ").append(last)
                    }
                    append(". Current streak: ").append(CheckIns.streak(parsed, today)).append(" day(s)")
                    if (recorded.size > RECENT_DAYS) {
                        append(". The ").append(RECENT_DAYS)
                        append(" most recent days are indexed in full; the ")
                        append(recorded.size - RECENT_DAYS).append(" older ones are counted here only.")
                    }
                },
                timestamp = sorted.firstOrNull()?.updatedAt ?: 0L
            )
        }

        return docs
    }

    /**
     * The partner seam: who this household is paired with, the week they published, and what has
     * been sent to them and not landed yet.
     *
     * A partner's week is *somebody else's* week, mirrored here rather than in LifeOps for exactly
     * that reason — so every document says whose it is, and none of them is phrased as though the
     * work were the user's own.
     */
    private suspend fun partners(
        db: PeopleDatabase,
        names: Map<String, String>
    ): List<KnowledgeDocument> {
        val dao = db.partnerDao()
        val links = dao.allLinks()
        if (links.isEmpty()) return emptyList()

        val docs = ArrayList<KnowledgeDocument>()
        val partnerOf = links.associate { it.id to it.partnerName }

        for (link in links) {
            val who = names[link.personId]
            docs += KnowledgeDocument(
                id = "people:partner:${link.id}",
                source = source,
                kind = "partner",
                title = "Partner link with ${link.partnerName}",
                body = buildString {
                    // Deliberately narrow: the pairing, never `mySecret`/`partnerSecret` and never
                    // the instance id. What is useful to answer with is who and whether it works.
                    append("Partner link: ").append(link.partnerName)
                    who?.let { append(", paired with ").append(it).append(" in the directory") }
                    append(". Pairing: ").append(if (link.confirmedAt != null) "confirmed" else "not yet confirmed")
                    link.lastSyncAt?.let { append(". Last synced: ").append(dayOf(it)) }
                    link.lastRejection?.takeIf { it.isNotBlank() }
                        ?.let { append(". Last round failed: ").append(it) }
                },
                timestamp = link.lastSyncAt ?: link.createdAt
            )
        }

        for (task in dao.allWeekTasks()) {
            val partner = partnerOf[task.linkId] ?: continue
            docs += KnowledgeDocument(
                id = "people:partner-task:${task.linkId}:${task.taskId}",
                source = source,
                kind = "partner-task",
                title = task.title,
                body = buildString {
                    append("On ").append(partner).append("'s week: ").append(task.title)
                    append(". Status: ").append(if (task.done) "done" else "todo")
                    append(". Week of ").append(task.weekStart)
                    task.dueDate?.takeIf { it.isNotBlank() }?.let { append(". Due: ").append(it) }
                    if (task.fromContribution != null) append(". Added from this household.")
                }
            )
        }

        for (entry in dao.allOutbox()) {
            val partner = partnerOf[entry.linkId] ?: continue
            docs += KnowledgeDocument(
                id = "people:partner-contribution:${entry.id}",
                source = source,
                kind = "partner-task",
                title = entry.title,
                body = buildString {
                    append("Sent to ").append(partner).append("'s week and not landed yet: ")
                    append(entry.title)
                    append(". Status: todo")
                    entry.dueDate?.takeIf { it.isNotBlank() }?.let { append(". Due: ").append(it) }
                },
                timestamp = entry.createdAt
            )
        }

        return docs
    }

    /** Epoch millis as the day it fell on here — a date a person would recognise, not a number. */
    private fun dayOf(millis: Long): LocalDate =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()

    private companion object {
        /**
         * How many of a person's most recent check-in days are indexed one document each.
         *
         * A month is the window a question reaches into ("how has she been sleeping?"), and it keeps
         * the log's share of the corpus bounded by the number of people rather than by how long the
         * habit has been kept.
         */
        const val RECENT_DAYS = 30
    }
}
