package com.lifeops.app.connection.service

import com.lifeops.app.data.model.ActivityTemplate
import com.lifeops.app.data.repository.ActivityTemplateRepository
import com.lifeops.app.util.PreferenceSuggestion

/** Use-case layer for saved outdoor activities (weather-fit templates). */
class ActivityService(private val activityTemplateRepository: ActivityTemplateRepository) {

    suspend fun create(
        name: String,
        outdoorPreferred: Boolean = true,
        durationMinutes: Int? = null,
        maxTempF: Int? = null,
        minTempF: Int? = null,
        avoidRain: Boolean = false,
        maxWindMph: Int? = null
    ): ActivityTemplate {
        require(name.isNotBlank()) { "Activity name must not be blank" }
        return activityTemplateRepository.create(
            name = name.trim(),
            outdoorPreferred = outdoorPreferred,
            durationMinutes = durationMinutes,
            maxTempF = maxTempF,
            minTempF = minTempF,
            avoidRain = avoidRain,
            maxWindMph = maxWindMph
        )
    }

    /** Persist an edited [template] (built-in or custom) — the activities screen holds it. */
    suspend fun update(template: ActivityTemplate) = activityTemplateRepository.update(template)

    suspend fun delete(id: String): Boolean {
        val template = activityTemplateRepository.getById(id) ?: return false
        activityTemplateRepository.delete(template)
        return true
    }

    /** Accept a learned preference suggestion (writes the new default, clears its observations). */
    suspend fun applySuggestion(suggestion: PreferenceSuggestion) =
        activityTemplateRepository.applySuggestion(suggestion)

    /** Dismiss a learned suggestion without changing the default. */
    suspend fun dismissSuggestion(activityId: String, field: String) =
        activityTemplateRepository.dismissSuggestion(activityId, field)

    /**
     * Record one manual override of an activity default (Phase-5 preference learning). Only a
     * meaningful delta should be recorded; callers gate on that.
     */
    suspend fun recordOverride(activityId: String, field: String, templateValue: Int?, userValue: Int?) =
        activityTemplateRepository.recordOverride(activityId, field, templateValue, userValue)
}
