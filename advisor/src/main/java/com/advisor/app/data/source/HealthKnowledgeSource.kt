package com.advisor.app.data.source

import android.content.Context
import com.advisor.app.logic.KnowledgeDocument
import com.advisor.app.logic.SourceApp
import com.health.app.data.db.HealthDatabase
import com.health.app.data.model.ReadingType
import com.health.app.logic.Age
import com.health.app.logic.CareLevel
import com.health.app.logic.Fever
import com.health.app.logic.TempSite
import com.health.app.logic.Temperature

/**
 * Reads Health's household records into [KnowledgeDocument]s: who is tracked, their recent
 * temperatures and other readings, symptoms, medicines and the doses given, and past illnesses. It
 * is what lets Advisor answer "when did she last have paracetamol" or "how long was his fever" from
 * the same rows the Health screens show.
 *
 * Three things are deliberate here:
 *
 *  - **Every document names the person.** The corpus is flat text with no per-row scoping, so a
 *    document that says "38.4 at 21:00" without saying whose is a document that can be retrieved
 *    into an answer about the wrong person. Nothing from this source is anonymous.
 *  - **Readings are summarised, not enumerated.** A year of temperatures is thousands of near-identical
 *    lines that would drown the rest of the corpus; recent ones are listed and the rest are
 *    characterised, which is what a question about them actually needs.
 *  - **The assessment travels with the number.** The band and care level come from Health's own
 *    `logic/Fever`, so Advisor repeats Health's reading of a temperature rather than inventing a
 *    second opinion — and the standing "not medical advice" caveat rides along with it.
 *
 * Like every source, this one is loaded only when the user has granted Health in
 * [com.advisor.app.logic.AdvisorPermissions] — the permission gate lives above this class.
 */
class HealthKnowledgeSource(context: Context) : KnowledgeSource {

    private val appContext = context.applicationContext
    override val source = SourceApp.HEALTH

    override suspend fun load(): List<KnowledgeDocument> {
        val dao = HealthDatabase.getInstance(appContext).healthDao()
        val now = System.currentTimeMillis()
        val docs = ArrayList<KnowledgeDocument>()

        val profiles = dao.getProfiles()
        val names = profiles.associate { it.id to it.name }
        fun who(profileId: String) = names[profileId] ?: "Someone"

        for (profile in profiles) {
            val age = Age.describe(profile.birthDate, now)
            docs += KnowledgeDocument(
                id = "health:profile:${profile.id}",
                source = source,
                kind = "person",
                title = profile.name,
                body = buildString {
                    append("Health profile: ").append(profile.name)
                    profile.relationship?.takeIf { it.isNotBlank() }?.let { append(" (").append(it).append(')') }
                    age?.let { append(". Age: ").append(it) }
                    profile.baselineTempC?.let {
                        append(". Usual temperature: ").append(Temperature.round1(it)).append(" °C")
                    }
                    profile.notes?.takeIf { it.isNotBlank() }?.let { append(". Notes: ").append(it) }
                    if (profile.archived) append(". Archived.")
                },
                timestamp = profile.updatedAt
            )
        }

        // --- readings: the recent ones in full, the rest as a shape ---
        val readings = dao.getAllReadings()
        val temperaturesByPerson = readings
            .filter { it.type == ReadingType.TEMPERATURE.key }
            .groupBy { it.profileId }

        for ((profileId, rows) in temperaturesByPerson) {
            val sorted = rows.sortedByDescending { it.takenAt }
            val profile = profiles.firstOrNull { it.id == profileId }
            val ageMonths = Age.monthsAt(profile?.birthDate, now)

            sorted.take(RECENT_READINGS).forEach { reading ->
                val site = TempSite.fromKey(reading.site)
                val assessment = Fever.assess(reading.value, site, ageMonths)
                docs += KnowledgeDocument(
                    id = "health:temperature:${reading.id}",
                    source = source,
                    kind = "temperature",
                    title = "${who(profileId)} temperature",
                    body = buildString {
                        append(who(profileId)).append(" temperature: ")
                        append(Temperature.round1(reading.value)).append(" °C (")
                        append(site.label.lowercase()).append("). ")
                        append(assessment.band.label).append('.')
                        if (assessment.careLevel != CareLevel.ROUTINE) {
                            append(' ').append(assessment.careLevel.label).append('.')
                        }
                        reading.note?.takeIf { it.isNotBlank() }?.let { append(" Note: ").append(it) }
                    },
                    timestamp = reading.takenAt
                )
            }

            if (sorted.size > RECENT_READINGS) {
                val peak = sorted.maxByOrNull { it.value + TempSite.fromKey(it.site).toOralOffsetC }
                docs += KnowledgeDocument(
                    id = "health:temperature-history:$profileId",
                    source = source,
                    kind = "temperature-history",
                    title = "${who(profileId)} temperature history",
                    body = buildString {
                        append(who(profileId)).append(" has ").append(sorted.size)
                        append(" recorded temperatures. Highest: ")
                        append(peak?.let { Temperature.round1(it.value).toString() } ?: "unknown")
                        append(" °C. The most recent ").append(RECENT_READINGS)
                        append(" are listed individually.")
                    },
                    timestamp = sorted.first().takenAt
                )
            }
        }

        readings
            .filterNot { it.type == ReadingType.TEMPERATURE.key }
            .sortedByDescending { it.takenAt }
            .take(RECENT_OTHER_READINGS)
            .forEach { reading ->
                val type = ReadingType.fromKey(reading.type)
                docs += KnowledgeDocument(
                    id = "health:reading:${reading.id}",
                    source = source,
                    kind = "reading",
                    title = "${who(reading.profileId)} ${type.label.lowercase()}",
                    body = buildString {
                        append(who(reading.profileId)).append(' ').append(type.label.lowercase())
                        append(": ").append(trimNumber(reading.value))
                        reading.secondaryValue?.let { append('/').append(trimNumber(it)) }
                        append(' ').append(type.unit).append('.')
                        reading.note?.takeIf { it.isNotBlank() }?.let { append(" Note: ").append(it) }
                    },
                    timestamp = reading.takenAt
                )
            }

        // --- symptoms ---
        for (symptom in dao.getAllSymptoms()) {
            docs += KnowledgeDocument(
                id = "health:symptom:${symptom.id}",
                source = source,
                kind = "symptom",
                title = "${who(symptom.profileId)}: ${symptom.name}",
                body = buildString {
                    append(who(symptom.profileId)).append(" symptom: ").append(symptom.name)
                    append(", severity ").append(symptom.severity).append(" of 5")
                    append(if (symptom.endedAt == null) ". Still going." else ". Resolved.")
                    symptom.note?.takeIf { it.isNotBlank() }?.let { append(" Note: ").append(it) }
                },
                timestamp = symptom.startedAt
            )
        }

        // --- medicines and doses ---
        for (medication in dao.getAllMedications()) {
            docs += KnowledgeDocument(
                id = "health:medication:${medication.id}",
                source = source,
                kind = "medication",
                title = "${who(medication.profileId)}: ${medication.name}",
                body = buildString {
                    append(who(medication.profileId)).append(" takes ").append(medication.name)
                    medication.strength?.takeIf { it.isNotBlank() }?.let { append(" (").append(it).append(')') }
                    medication.doseAmount?.let {
                        append(". Usual dose ").append(trimNumber(it)).append(' ').append(medication.doseUnit)
                    }
                    medication.minIntervalHours?.let {
                        append(". No more often than every ").append(trimNumber(it)).append(" hours")
                    }
                    medication.maxDosesPer24h?.let { append(". Max ").append(it).append(" doses per 24 hours") }
                    medication.maxAmountPer24h?.let {
                        append(". Max ").append(trimNumber(it)).append(' ').append(medication.doseUnit)
                            .append(" per 24 hours")
                    }
                    append('.')
                    if (!medication.active) append(" Currently paused.")
                },
                timestamp = medication.createdAt
            )
        }

        dao.getAllDoses().sortedByDescending { it.takenAt }.take(RECENT_DOSES).forEach { dose ->
            docs += KnowledgeDocument(
                id = "health:dose:${dose.id}",
                source = source,
                kind = "dose",
                title = "${who(dose.profileId)}: ${dose.medicationName} dose",
                body = buildString {
                    append(who(dose.profileId)).append(" was given ").append(dose.medicationName)
                    if (dose.amount > 0) append(", ").append(trimNumber(dose.amount)).append(' ').append(dose.unit)
                    append('.')
                    dose.note?.takeIf { it.isNotBlank() }?.let { append(" Note: ").append(it) }
                },
                timestamp = dose.takenAt
            )
        }

        // --- illnesses ---
        for (episode in dao.getAllEpisodes()) {
            val hours = ((episode.endedAt ?: now) - episode.startedAt).coerceAtLeast(0L) / 3_600_000
            docs += KnowledgeDocument(
                id = "health:episode:${episode.id}",
                source = source,
                kind = "illness",
                title = "${who(episode.profileId)}: ${episode.title}",
                body = buildString {
                    append(who(episode.profileId)).append(" illness: ").append(episode.title)
                    append(if (episode.endedAt == null) ". Still going" else ". Ended")
                    append(", ").append(hours).append(" hours so far.")
                    episode.note?.takeIf { it.isNotBlank() }?.let { append(" Note: ").append(it) }
                },
                timestamp = episode.startedAt
            )
        }

        // --- the care log ---
        dao.getAllCareNotes().sortedByDescending { it.at }.take(RECENT_CARE_NOTES).forEach { note ->
            docs += KnowledgeDocument(
                id = "health:care:${note.id}",
                source = source,
                kind = "care",
                title = "${who(note.profileId)}: care note",
                body = "${who(note.profileId)} care note (${note.kind}): ${note.text}",
                timestamp = note.at
            )
        }

        return docs
    }

    /** Render 2.0 as "2" but keep 2.5 as "2.5" — the same convention the other sources use. */
    private fun trimNumber(value: Double): String =
        if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

    companion object {
        private const val RECENT_READINGS = 24
        private const val RECENT_OTHER_READINGS = 40
        private const val RECENT_DOSES = 60
        private const val RECENT_CARE_NOTES = 40
    }
}
