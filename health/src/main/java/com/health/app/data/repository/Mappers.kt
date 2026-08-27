package com.health.app.data.repository

import com.health.app.data.db.entities.AllergyEntity
import com.health.app.data.db.entities.CabinetItemEntity
import com.health.app.data.db.entities.CareNoteEntity
import com.health.app.data.db.entities.ConditionEntity
import com.health.app.data.db.entities.DocumentEntity
import com.health.app.data.db.entities.DoseEntity
import com.health.app.data.db.entities.EpisodeEntity
import com.health.app.data.db.entities.ImmunizationEntity
import com.health.app.data.db.entities.InsuranceMemberEntity
import com.health.app.data.db.entities.InsurancePlanEntity
import com.health.app.data.db.entities.MedicationEntity
import com.health.app.data.db.entities.NetworkCheckEntity
import com.health.app.data.db.entities.ProfileEntity
import com.health.app.data.db.entities.ProviderEntity
import com.health.app.data.db.entities.ProviderLinkEntity
import com.health.app.data.db.entities.ReadingEntity
import com.health.app.data.db.entities.SymptomEntity
import com.health.app.data.model.Allergy
import com.health.app.data.model.CabinetItem
import com.health.app.data.model.CareKind
import com.health.app.data.model.CareNote
import com.health.app.data.model.CareRole
import com.health.app.data.model.Condition
import com.health.app.data.model.Document
import com.health.app.data.model.Dose
import com.health.app.data.model.Episode
import com.health.app.data.model.Immunization
import com.health.app.data.model.InsuranceMembership
import com.health.app.data.model.InsurancePlan
import com.health.app.data.model.Medication
import com.health.app.data.model.NetworkCheck
import com.health.app.data.model.Profile
import com.health.app.data.model.Provider
import com.health.app.data.model.ProviderLink
import com.health.app.data.model.Reading
import com.health.app.data.model.ReadingType
import com.health.app.data.model.Symptom
import com.health.app.logic.AllergyKind
import com.health.app.logic.AllergySeverity
import com.health.app.logic.Cabinet
import com.health.app.logic.CabinetFacts
import com.health.app.logic.CheckOutcome
import com.health.app.logic.ConditionStatus
import com.health.app.logic.CoverageKind
import com.health.app.logic.DirectoryOutcome
import com.health.app.logic.DoseReminder
import com.health.app.logic.DocumentKind
import com.health.app.logic.DoseSchedule
import com.health.app.logic.Fever
import com.health.app.logic.MedicationRule
import com.health.app.logic.NetworkCheckRecord
import com.health.app.logic.NetworkStatus
import com.health.app.logic.PlanType
import com.health.app.logic.ReminderMode
import com.health.app.logic.VaccineSource
import com.health.app.logic.Temperature
import com.health.app.logic.TempSite
import com.health.app.logic.TimelineEntry
import com.health.app.logic.TimelineKind
import com.health.app.logic.TempUnit
import com.people.app.sync.PersonPacket
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Rows in, models out — the one place a string column becomes the enum `logic/` reasons about.
 *
 * Split out of [HealthRepository] because they are a different kind of code: the repository decides
 * *what happens*, and these decide *what a row means*. Keeping them together made one file long
 * enough that finding either took a search.
 *
 * The rule they all follow is that a mapper never invents. A column nobody filled in maps to null,
 * not to a default that reads like an answer — see `ConditionEntity.toModel`, where an unset
 * "reading that matters" stays null rather than falling back to TEMPERATURE the way `ReadingType`
 * itself does.
 */

// --- entity → model mapping --------------------------------------------------------------------

fun ProfileEntity.toModel() = Profile(
    id = id,
    name = name,
    relationship = relationship,
    birthDate = birthDate,
    colorArgb = colorArgb,
    baselineTempC = baselineTempC,
    notes = notes,
    sortOrder = sortOrder,
    archived = archived
)

/**
 * This profile as the People sync seam sees them — identity only.
 *
 * Three fields are deliberately absent, and the reasons differ:
 *
 *  - [ProfileEntity.notes] is **medical** — allergies, conditions, the doctor's number. People has a
 *    field called `note` too, but it means "likes hiking, hates crowds". Mapping one onto the other
 *    would copy a person's conditions into the household directory and from there into LifeOps. A
 *    shared wire makes that leak a one-line mistake, so it is refused explicitly rather than left to
 *    whoever next edits this mapper.
 *  - [ProfileEntity.baselineTempC] and the colour are Health's own reading of a person; no other
 *    peer can show or edit them.
 *  - Email and phone are absent because Health has no columns for them. That costs a little binding
 *    strength on the very first round — Health matches by name until it has a key — and nothing
 *    afterwards, since the key is what binds from then on.
 */
fun ProfileEntity.toPacket() = PersonPacket(
    personKey = personKey ?: id,
    name = name,
    relationship = relationship,
    birthDate = birthDate,
    email = null,
    phone = null,
    note = null,
    household = household,
    archived = archived,
    updatedAt = updatedAt,
    deleted = false
)

fun ReadingEntity.toModel() = Reading(
    id = id,
    profileId = profileId,
    episodeId = episodeId,
    type = ReadingType.fromKey(type),
    value = value,
    secondaryValue = secondaryValue,
    site = site?.let { TempSite.fromKey(it) },
    takenAt = takenAt,
    note = note
)

fun SymptomEntity.toModel() = Symptom(
    id = id,
    profileId = profileId,
    episodeId = episodeId,
    name = name,
    severity = severity,
    startedAt = startedAt,
    endedAt = endedAt,
    note = note
)

fun MedicationEntity.toModel() = Medication(
    id = id,
    profileId = profileId,
    name = name,
    strength = strength,
    form = form,
    doseAmount = doseAmount,
    doseUnit = doseUnit,
    minIntervalHours = minIntervalHours,
    maxDosesPer24h = maxDosesPer24h,
    maxAmountPer24h = maxAmountPer24h,
    note = note,
    active = active,
    rxcui = rxcui,
    cabinetItemId = cabinetItemId,
    reminderMode = ReminderMode.fromKey(reminderMode),
    reminderTimes = DoseReminder.parseTimes(reminderTimes)
)

fun CabinetItemEntity.toModel() = CabinetItem(
    id = id,
    rxcui = rxcui,
    name = name,
    brandName = brandName,
    strength = strength,
    form = form,
    quantity = quantity,
    quantityUnit = quantityUnit,
    expiryDate = expiryDate,
    location = location,
    lowStockThreshold = lowStockThreshold,
    note = note,
    updatedAt = updatedAt
)

/** The stock facts `logic/Cabinet` reasons about, paired with one person's dose of the item. */
fun CabinetItemEntity.toFacts(doseAmount: Double? = null, doseUnit: String = "") = CabinetFacts(
    quantity = quantity,
    quantityUnit = quantityUnit,
    expiryDate = expiryDate,
    lowStockThreshold = lowStockThreshold,
    doseAmount = doseAmount,
    doseUnit = doseUnit
)

/** The label's rules, in the shape `logic/DoseSchedule` reasons about. */
fun MedicationEntity.toRule() = MedicationRule(
    name = name,
    doseAmount = doseAmount,
    doseUnit = doseUnit,
    minIntervalHours = minIntervalHours,
    maxDosesPer24h = maxDosesPer24h,
    maxAmountPer24h = maxAmountPer24h
)

fun DoseEntity.toModel() = Dose(
    id = id,
    profileId = profileId,
    medicationId = medicationId,
    medicationName = medicationName,
    amount = amount,
    unit = unit,
    takenAt = takenAt,
    note = note,
    episodeId = episodeId
)

/**
 * The row → history-entry mappers.
 *
 * Each one decides how its record reads in a list somebody may be holding out to a doctor, which is
 * why the wording is plain and complete rather than terse: "38.4 °C (ear)" tells you what an
 * abbreviation would not, and the site is the difference between a fever and a normal reading.
 *
 * Readings carry the fever verdict Health already computed, so the history and every other screen
 * give the same answer to the same number. Nothing else carries a care level — Health has no opinion
 * about a care note, and defaulting one to "routine" would be inventing one.
 *
 * A temperature is stored in Celsius and read in whatever the household chose, so [unit] has to be
 * passed in: this is the one mapper that writes a number the display unit governs, and hard-coding
 * Celsius here is how a history ends up quoting °C to somebody who set the app to °F everywhere else.
 */
fun ReadingEntity.toTimelineEntry(ageMonths: Int?, unit: TempUnit): TimelineEntry {
    val readingType = ReadingType.fromKey(type)
    val tempSite = site?.let { TempSite.fromKey(it) }
    val assessment = if (readingType == ReadingType.TEMPERATURE) {
        Fever.assess(value, tempSite ?: TempSite.ORAL, ageMonths)
    } else {
        null
    }
    val headline = when (readingType) {
        ReadingType.TEMPERATURE -> buildString {
            append(Temperature.format(value, unit))
            tempSite?.let { append(" (").append(it.label.lowercase()).append(')') }
        }
        ReadingType.BLOOD_PRESSURE ->
            "${trimAmount(value)}/${secondaryValue?.let { trimAmount(it) } ?: "?"} ${readingType.unit}"
        else -> "${trimAmount(value)} ${readingType.unit}"
    }
    return TimelineEntry(
        id = "reading:$id",
        kind = TimelineKind.READING,
        atMillis = takenAt,
        recordedAtMillis = createdAt,
        headline = headline,
        detail = listOfNotNull(
            readingType.label.takeIf { readingType != ReadingType.TEMPERATURE },
            assessment?.band?.label,
            note
        ).joinToString(" · ").ifBlank { null },
        careLevel = assessment?.careLevel
    )
}

/**
 * A symptom becomes up to **two** entries: one where it started, one where it was marked over.
 *
 * They are separate moments and a history that showed only the start would be missing the answer to
 * "when did the cough stop?" — which is usually the question being asked, because it is how you tell
 * whether things are getting better.
 */
fun SymptomEntity.toTimelineEntries(): List<TimelineEntry> = buildList {
    add(
        TimelineEntry(
            id = "symptom-start:$id",
            kind = TimelineKind.SYMPTOM_STARTED,
            atMillis = startedAt,
            recordedAtMillis = createdAt,
            headline = name,
            detail = listOfNotNull("severity $severity of 5", note).joinToString(" · ")
        )
    )
    endedAt?.let { ended ->
        add(
            TimelineEntry(
                id = "symptom-end:$id",
                kind = TimelineKind.SYMPTOM_ENDED,
                atMillis = ended,
                // The end was recorded when it was marked over, which Health doesn't store
                // separately — the row's createdAt is when the symptom was *added*, and reusing it
                // here would claim the ending was written up months before it happened.
                recordedAtMillis = null,
                headline = "$name passed"
            )
        )
    }
}

fun DoseEntity.toTimelineEntry() = TimelineEntry(
    id = "dose:$id",
    kind = TimelineKind.DOSE,
    atMillis = takenAt,
    recordedAtMillis = createdAt,
    headline = medicationName,
    detail = listOfNotNull(
        "${trimAmount(amount)} $unit".trim().takeIf { amount > 0 },
        note
    ).joinToString(" · ").ifBlank { null }
)

fun CareNoteEntity.toTimelineEntry() = TimelineEntry(
    id = "care:$id",
    kind = TimelineKind.CARE,
    atMillis = at,
    recordedAtMillis = createdAt,
    headline = text,
    detail = CareKind.fromKey(kind).label
)

/** Numbers in the history read as people write them: "5", not "5.0". */
private fun trimAmount(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else Temperature.round1(value).toString()

fun EpisodeEntity.toModel() = Episode(
    id = id,
    profileId = profileId,
    title = title,
    startedAt = startedAt,
    endedAt = endedAt,
    note = note
)

fun CareNoteEntity.toModel() = CareNote(
    id = id,
    profileId = profileId,
    episodeId = episodeId,
    kind = CareKind.fromKey(kind),
    text = text,
    at = at
)

// --- coverage and care-team mapping ---------------------------------------------------------------

fun InsurancePlanEntity.toModel() = InsurancePlan(
    id = id,
    carrierName = carrierName,
    planName = planName,
    coverageKind = CoverageKind.fromKey(coverageKind),
    planType = PlanType.fromKey(planType),
    groupNumber = groupNumber,
    payerId = payerId,
    rxBin = rxBin,
    rxPcn = rxPcn,
    rxGroup = rxGroup,
    memberServicesPhone = memberServicesPhone,
    nurseLinePhone = nurseLinePhone,
    effectiveDate = effectiveDate,
    endDate = endDate,
    directoryUrl = directoryUrl,
    directoryBaseUrl = directoryBaseUrl,
    // A plan nobody has probed is NOT_CONFIGURED rather than UNREACHABLE: nothing was asked, so
    // nothing failed, and the two read very differently to somebody deciding whether to trust it.
    directoryStatus = directoryStatus?.let { DirectoryOutcome.fromKey(it) }
        ?: DirectoryOutcome.NOT_CONFIGURED,
    directoryCheckedAt = directoryCheckedAt,
    directoryDetail = directoryDetail,
    frontImagePath = frontImagePath,
    backImagePath = backImagePath,
    note = note,
    archived = archived,
    updatedAt = updatedAt
)

fun InsuranceMemberEntity.toModel() = InsuranceMembership(
    id = id,
    profileId = profileId,
    planId = planId,
    memberId = memberId,
    personCode = personCode,
    subscriberName = subscriberName,
    relationshipToSubscriber = relationshipToSubscriber,
    effectiveDate = effectiveDate,
    endDate = endDate,
    primaryCoverage = primaryCoverage,
    frontImagePath = frontImagePath,
    backImagePath = backImagePath,
    note = note
)

fun ProviderEntity.toModel() = Provider(
    id = id,
    name = name,
    npi = npi,
    specialty = specialty,
    practiceName = practiceName,
    phone = phone,
    addressLine = addressLine,
    website = website,
    note = note
)

fun ProviderLinkEntity.toModel() = ProviderLink(
    id = id,
    profileId = profileId,
    providerId = providerId,
    role = CareRole.fromKey(role),
    since = since,
    note = note
)

fun NetworkCheckEntity.toModel() = NetworkCheck(
    id = id,
    providerId = providerId,
    planId = planId,
    checkedAt = checkedAt,
    outcome = CheckOutcome.fromKey(outcome),
    directoryLabel = directoryLabel,
    directoryUrl = directoryUrl,
    matchedName = matchedName,
    matchedNpi = matchedNpi,
    matchCount = matchCount,
    networks = networks?.split(CSV)?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty(),
    detail = detail
)

/** The stored check, in the shape `logic/NetworkStatus` reasons about. */
fun NetworkCheck.toRecord() = NetworkCheckRecord(
    checkedAt = checkedAt,
    outcome = outcome,
    directoryLabel = directoryLabel,
    matchedName = matchedName,
    networks = networks,
    detail = detail
)

/**
 * How the coverage tables store "nothing was typed here".
 *
 * Every one of these columns is optional and most of them stay empty, so a blank string arriving
 * from a text field has to become a null rather than an empty value that then renders as a card line
 * with nothing after the colon. Written once here because it is applied about sixty times.
 */
internal fun String?.clean(): String? = this?.trim()?.ifBlank { null }

/** The separator for the short lists that don't earn a table — network names, at present. */
const val CSV = ", "

fun AllergyEntity.toModel() = Allergy(
    id = id,
    profileId = profileId,
    substance = substance,
    kind = AllergyKind.fromKey(kind),
    severity = AllergySeverity.fromKey(severity),
    reaction = reaction,
    rxcui = rxcui,
    noticedDate = noticedDate,
    note = note
)

fun ConditionEntity.toModel() = Condition(
    id = id,
    profileId = profileId,
    name = name,
    status = ConditionStatus.fromKey(status),
    onsetDate = onsetDate,
    resolvedDate = resolvedDate,
    providerId = providerId,
    // A reading type nobody set stays null. `ReadingType.fromKey` falls back to TEMPERATURE, which is
    // the right default for a reading that must be *something* and the wrong one for a column whose
    // whole meaning is "one of these matters here, or none does".
    monitorReadingType = monitorReadingType?.let { key -> ReadingType.entries.firstOrNull { it.key == key } },
    note = note
)

fun ImmunizationEntity.toModel() = Immunization(
    id = id,
    profileId = profileId,
    vaccine = vaccine,
    cvxCode = cvxCode,
    givenDate = givenDate,
    doseNumber = doseNumber,
    source = VaccineSource.fromKey(source),
    providerId = providerId,
    lotNumber = lotNumber,
    site = site,
    note = note
)

fun DocumentEntity.toModel() = Document(
    id = id,
    profileId = profileId,
    title = title,
    kind = DocumentKind.fromKey(kind),
    documentDate = documentDate,
    fileName = fileName,
    mimeType = mimeType,
    sizeBytes = sizeBytes,
    episodeId = episodeId,
    conditionId = conditionId,
    immunizationId = immunizationId,
    providerId = providerId,
    note = note,
    createdAt = createdAt
)
