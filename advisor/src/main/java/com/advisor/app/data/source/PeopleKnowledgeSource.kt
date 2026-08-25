package com.advisor.app.data.source

import android.content.Context
import com.advisor.app.logic.KnowledgeDocument
import com.advisor.app.logic.SourceApp
import com.people.app.data.db.PeopleDatabase
import com.people.app.logic.DateKind
import com.people.app.logic.ImportantDates
import java.time.LocalDate

/**
 * Reads the household directory into [KnowledgeDocument]s: who is in it, how to reach them, the
 * dates that come round, and the running notes kept about them.
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
        val dao = PeopleDatabase.getInstance(appContext).peopleDao()
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

        return docs
    }
}
