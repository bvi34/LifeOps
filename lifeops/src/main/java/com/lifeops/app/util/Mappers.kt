package com.lifeops.app.util

import com.lifeops.app.data.db.entities.*
import com.lifeops.app.data.model.*

fun AspectEntity.toModel() = Aspect(id, name, color, icon, isArchived)
fun Aspect.toEntity() = AspectEntity(id, name, color, icon, isArchived)

fun CategoryEntity.toModel() = Category(id, aspectId, name, isArchived)
fun Category.toEntity() = CategoryEntity(id, aspectId, name, isArchived)

fun WeekEntity.toModel() = Week(id, startDate, endDate, isClosed, closedAt)
fun Week.toEntity() = WeekEntity(id, startDate, endDate, isClosed, closedAt)

fun TaskEntity.toModel() = Task(
    id, weekId, title, aspectId, categoryId,
    Priority.from(priority), dueDate, hardDeadline,
    TaskStatus.from(status), resourceValue, completedAt, carriedFromTaskId, createdAt,
    isRecurring, estimatedMinutes, carriedCount, sortOrder, isManuallyAdded, operationId,
    TaskSource.from(source), slug,
    CarryForwardReason.from(carryForwardReason), counterId,
    recurrenceIntervalWeeks, recurrenceDayOfMonth, isCommitment
)

fun Task.toEntity() = TaskEntity(
    id, weekId, title, aspectId, categoryId,
    priority.label, dueDate, hardDeadline,
    status.value, resourceValue, completedAt, carriedFromTaskId, createdAt,
    isRecurring, estimatedMinutes, carriedCount, sortOrder, isManuallyAdded, operationId,
    source.name, slug.ifEmpty { title.toSlug() },
    carryForwardReason?.value, counterId,
    recurrenceIntervalWeeks, recurrenceDayOfMonth, isCommitment
)

fun CounterEntity.toModel() = Counter(id, name, categoryId, isArchived, sortOrder, createdAt, isHabit, reminderHour)
fun Counter.toEntity() = CounterEntity(id, name, categoryId, isArchived, sortOrder, createdAt, isHabit, reminderHour)

fun CounterEventEntity.toModel(): CounterEvent {
    val weather = CounterEventWeather(
        temperatureF = weatherTempF,
        feelsLikeF = weatherFeelsLikeF,
        humidityPct = weatherHumidityPct,
        windMph = weatherWindMph,
        conditions = weatherConditions,
        locationName = weatherLocationName,
        observedAt = weatherObservedAt
    )
    return CounterEvent(id, counterId, weekKey, occurredAt, delta, note, weather.takeUnless { it.isEmpty })
}

fun TaskNoteEntity.toModel() = TaskNote(id, taskId, content, createdAt, subtaskId)
fun TaskNote.toEntity() = TaskNoteEntity(id, taskId, content, createdAt, subtaskId)

fun TaskAttachmentEntity.toModel() = TaskAttachment(id, taskId, imageData, caption, createdAt)
fun TaskAttachment.toEntity() = TaskAttachmentEntity(id, taskId, imageData, caption, createdAt)

fun BusyBlockEntity.toModel() = BusyBlock(id, title, startMinutes, endMinutes, daysMask, specificDate, personId, createdAt, reminderEnabled, googleEventId, googleCalendarId)
fun BusyBlock.toEntity() = BusyBlockEntity(id, title, startMinutes, endMinutes, daysMask, specificDate, personId, createdAt, reminderEnabled, googleEventId, googleCalendarId)

fun TimeEntryEntity.toModel() = TimeEntry(id, taskId, durationMinutes, note, recordedAt, subtaskId)
fun TimeEntry.toEntity() = TimeEntryEntity(id, taskId, durationMinutes, note, recordedAt, subtaskId)

fun GameResourceEntity.toModel() = GameResource(id, name, currentValue, lifetimeEarned, slotIndex)
fun GameResource.toEntity() = GameResourceEntity(id, name, currentValue, lifetimeEarned, slotIndex)

fun GameResourceMappingEntity.toModel() = GameResourceMapping(id, gameResourceId, aspectId, weight)
fun GameResourceMapping.toEntity() = GameResourceMappingEntity(id, gameResourceId, aspectId, weight)

fun GameScoreEntity.toModel() = GameScore(
    id, weekKey, pointInvestment, score, setReached, waveReached, totalWaves,
    levelReached, weapon, challengeMode, createdAt
)
fun GameScore.toEntity() = GameScoreEntity(
    id, weekKey, pointInvestment, score, setReached, waveReached, totalWaves,
    levelReached, weapon, challengeMode, createdAt
)

fun ResourceTransactionEntity.toModel() = ResourceTransaction(id, resourceId, amount, type, note, createdAt)
fun ResourceTransaction.toEntity() = ResourceTransactionEntity(id, resourceId, amount, type, note, createdAt)

fun CostResourceEntity.toModel() = CostResource(
    id, name, ResourceResetCycle.from(resetCycle), capacity, isActive, sortIndex, createdAt
)
fun CostResource.toEntity() = CostResourceEntity(id, name, resetCycle.label, capacity, isActive, sortIndex, createdAt)

fun TaskCostEntryEntity.toModel() = TaskCostEntry(id, taskId, resourceId, amount, note, recordedAt)
fun TaskCostEntry.toEntity() = TaskCostEntryEntity(id, taskId, resourceId, amount, note, recordedAt)

fun RunbookEntity.toModel() = Runbook(id, name, createdAt)
fun Runbook.toEntity() = RunbookEntity(id, name, createdAt)

fun RunbookStepEntity.toModel() = RunbookStep(id, runbookId, label, stepOrder)
fun RunbookStep.toEntity() = RunbookStepEntity(id, runbookId, label, stepOrder)

fun SubtaskEntity.toModel() = Subtask(id, taskId, runbookId, label, stepOrder, isChecked)
fun Subtask.toEntity() = SubtaskEntity(id, taskId, runbookId, label, stepOrder, isChecked)

fun TemplateEntity.toModel() = Template(id, name, createdAt)
fun Template.toEntity() = TemplateEntity(id, name, createdAt)

fun TemplateTaskEntity.toModel() = TemplateTask(id, templateId, title, aspectName, categoryName, priority, estimatedMinutes, runbookId, taskOrder)
fun TemplateTask.toEntity() = TemplateTaskEntity(id, templateId, title, aspectName, categoryName, priority, estimatedMinutes, runbookId, taskOrder)

fun FoodItemEntity.toModel() = FoodItem(
    id, name, brand, servingSize, servingUnit, servingSizeGrams,
    calories, carbsG, proteinG, fatG, fiberG, sodiumMg,
    FoodSource.from(source), fdcId, createdAt
)
fun FoodItem.toEntity() = FoodItemEntity(
    id, name, brand, servingSize, servingUnit, servingSizeGrams,
    calories, carbsG, proteinG, fatG, fiberG, sodiumMg,
    source.name, fdcId, createdAt
)

fun RecipeEntity.toModel() = Recipe(id, name, servings, createdAt, instructions, sourceUrl)
fun Recipe.toEntity() = RecipeEntity(id, name, servings, createdAt, instructions, sourceUrl)

fun RecipeIngredientEntity.toModel() = RecipeIngredient(id, recipeId, foodItemId, quantity, IngredientUnit.from(unit), sortOrder)
fun RecipeIngredient.toEntity() = RecipeIngredientEntity(id, recipeId, foodItemId, quantity, unit.name, sortOrder)

fun FoodLogEntryEntity.toModel() = FoodLogEntry(
    id, foodItemId, name, quantity, IngredientUnit.from(unit), calories, carbsG, proteinG, fatG, loggedAt,
    FoodLogSource.from(source), confirmed, confirmedAt, weeklyMenuItemId
)
fun FoodLogEntry.toEntity() = FoodLogEntryEntity(
    id, foodItemId, name, quantity, unit.name, calories, carbsG, proteinG, fatG, loggedAt,
    source.name, confirmed, confirmedAt, weeklyMenuItemId
)

fun WeeklyMenuItemEntity.toModel() = WeeklyMenuItem(id, weekStartDate, recipeId, mealName, plannedServings, assignedDate, mealType, createdAt)
fun WeeklyMenuItem.toEntity() = WeeklyMenuItemEntity(id, weekStartDate, recipeId, mealName, plannedServings, assignedDate, mealType, createdAt)

fun BookEntity.toModel() = Book(id, title, author, BookStatus.from(status), createdAt, completedAt, sourceType, category, sourceId, citationTitle)
fun Book.toEntity() = BookEntity(id, title, author, status.name, createdAt, completedAt, sourceType, category, sourceId, citationTitle)

fun BookNoteEntity.toModel() = BookNote(id, bookId, content, createdAt)
fun BookNote.toEntity() = BookNoteEntity(id, bookId, content, createdAt)

fun BookTimeEntryEntity.toModel() = BookTimeEntry(id, bookId, durationMinutes, note, recordedAt)
fun BookTimeEntry.toEntity() = BookTimeEntryEntity(id, bookId, durationMinutes, note, recordedAt)

// The entity's legacy long-form `content` column stays only for old-backup compatibility;
// MIGRATION_25_26 folded it into future_operation_notes, so notes are the source of truth now.
fun FutureOperationEntity.toModel() = FutureOperation(id, title, createdAt, updatedAt, FutureOperationStatus.from(status))
fun FutureOperation.toEntity() = FutureOperationEntity(id, title, "", createdAt, updatedAt, status.value)

fun FutureOperationNoteEntity.toModel() = FutureOperationNote(id, operationId, content, createdAt)
fun FutureOperationNote.toEntity() = FutureOperationNoteEntity(id, operationId, content, createdAt)

// Weather — the domain models are deliberately source-agnostic (no sortOrder/createdAt/locationId),
// so entity construction that needs those persistence-only fields lives in WeatherRepository.
fun WeatherLocationEntity.toModel() = WeatherLocation(id, latitude, longitude, name)

fun WeatherAlertEntity.toModel() = WeatherAlert(
    id, event, AlertSeverity.from(severity), headline, description, instruction, onset, expires, areaDesc
)

fun PersonEntity.toModel() = Person(
    id, name, heatToleranceMaxF, coldToleranceMinF, uvMax, windMaxMph, maxPrecipitationPct,
    SunSensitivity.from(sunSensitivity), activityPreferences, isArchived, sortOrder, createdAt,
    Relationship.from(relationship), email, phone
)
fun Person.toEntity() = PersonEntity(
    id, name, heatToleranceMaxF, coldToleranceMinF, uvMax, windMaxMph, maxPrecipitationPct,
    sunSensitivity.value, activityPreferences, isArchived, sortOrder, createdAt, relationship?.value,
    email, phone
)

/**
 * This person as the People sync seam sees them — identity only.
 *
 * The weather-comfort tolerances, sun sensitivity and sort order are pointedly absent: they are
 * LifeOps' own reading of a person, no other peer can show or edit them, and putting them on a
 * shared wire would invite a peer that has never heard of a UV index to overwrite them with a
 * stale copy.
 */
fun PersonEntity.toPacket() = com.people.app.sync.PersonPacket(
    personKey = personKey ?: id,
    name = name,
    relationship = relationship,
    email = email,
    phone = phone,
    note = activityPreferences,
    archived = isArchived,
    updatedAt = updatedAt,
    deleted = false
)

fun PersonNoteEntity.toModel() = PersonNote(id, personId, content, createdAt)
fun PersonNote.toEntity() = PersonNoteEntity(id, personId, content, createdAt)

fun MilestoneEntity.toModel() = Milestone(
    id, title, description, points, aspectId, personId, achievedAt, createdAt
)
fun Milestone.toEntity() = MilestoneEntity(
    id, title, description, points, aspectId, personId, achievedAt, createdAt
)

fun TaskWeatherRequirementEntity.toModel() = TaskWeatherRequirement(
    taskId, outdoorPreferred, durationMinutes, maxTempF, minTempF, avoidRain, maxWindMph
)
fun TaskWeatherRequirement.toEntity() = TaskWeatherRequirementEntity(
    taskId, outdoorPreferred, durationMinutes, maxTempF, minTempF, avoidRain, maxWindMph
)

fun ActivityTemplateEntity.toModel() = ActivityTemplate(
    id, name, outdoorPreferred, durationMinutes, maxTempF, minTempF, avoidRain, maxWindMph,
    isBuiltIn, sortOrder, createdAt
)
fun ActivityTemplate.toEntity() = ActivityTemplateEntity(
    id, name, outdoorPreferred, durationMinutes, maxTempF, minTempF, avoidRain, maxWindMph,
    isBuiltIn, sortOrder, createdAt
)

fun ActivityOverrideEntity.toModel() = ActivityOverride(id, activityId, field, templateValue, userValue, createdAt)
fun ActivityOverride.toEntity() = ActivityOverrideEntity(id, activityId, field, templateValue, userValue, createdAt)

fun WellnessCheckinEntity.toModel() = WellnessCheckin(
    id = id,
    kind = WellnessKind.from(kind),
    recordedAt = recordedAt,
    weekKey = weekKey,
    dayKey = dayKey,
    energy = energy,
    sensory = sensory,
    trend = WellnessTrend.from(trend),
    sensoryTrend = SensoryTrend.from(sensoryTrend),
    initiative = Initiative.from(initiative),
    energyDerived = energyDerived,
    sensoryDerived = sensoryDerived,
    tired = tired,
    sleepMinutes = sleepMinutes,
    note = note,
    sleepBedtime = sleepBedtime,
    sleepWakeTime = sleepWakeTime,
    sleepInterruptions = sleepInterruptions,
    longestSleepMinutes = longestSleepMinutes
)
fun WellnessCheckin.toEntity() = WellnessCheckinEntity(
    id = id,
    kind = kind.value,
    recordedAt = recordedAt,
    weekKey = weekKey,
    dayKey = dayKey,
    energy = energy,
    sensory = sensory,
    trend = trend?.value,
    sensoryTrend = sensoryTrend?.value,
    initiative = initiative?.value,
    energyDerived = energyDerived,
    sensoryDerived = sensoryDerived,
    tired = tired,
    sleepMinutes = sleepMinutes,
    note = note,
    sleepBedtime = sleepBedtime,
    sleepWakeTime = sleepWakeTime,
    sleepInterruptions = sleepInterruptions,
    longestSleepMinutes = longestSleepMinutes
)

fun PhoneActivityEventEntity.toModel() = PhoneActivityEvent(
    id, PhoneActivityType.from(type) ?: PhoneActivityType.SCREEN_OFF, occurredAt, dayKey
)
fun PhoneActivityEvent.toEntity() = PhoneActivityEventEntity(id, type.value, occurredAt, dayKey)
