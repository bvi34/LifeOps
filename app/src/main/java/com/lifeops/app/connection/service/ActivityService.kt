package com.lifeops.app.connection.service

import com.lifeops.app.data.model.ActivityTemplate
import com.lifeops.app.data.repository.ActivityTemplateRepository

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

    suspend fun delete(id: String): Boolean {
        val template = activityTemplateRepository.getById(id) ?: return false
        activityTemplateRepository.delete(template)
        return true
    }
}
