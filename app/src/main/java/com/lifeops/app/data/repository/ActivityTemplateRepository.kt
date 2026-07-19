package com.lifeops.app.data.repository

import com.lifeops.app.data.db.dao.ActivityTemplateDao
import com.lifeops.app.data.model.ActivityTemplate
import com.lifeops.app.util.DateUtil
import com.lifeops.app.util.toEntity
import com.lifeops.app.util.toModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * Saved activities (Phase 4). Ships a set of sensible built-ins seeded on first launch, but every
 * template — built-in or not — is an ordinary editable/deletable row, so users can tweak the
 * defaults or build entirely new activities of their own.
 */
class ActivityTemplateRepository(private val dao: ActivityTemplateDao) {

    fun observeAll(): Flow<List<ActivityTemplate>> =
        dao.observeAll().map { list -> list.map { it.toModel() } }

    suspend fun getById(id: String): ActivityTemplate? = dao.getById(id)?.toModel()

    /** Create a brand-new custom activity. Sorts after the built-ins. */
    suspend fun create(
        name: String,
        outdoorPreferred: Boolean = true,
        durationMinutes: Int? = null,
        maxTempF: Int? = null,
        minTempF: Int? = null,
        avoidRain: Boolean = false,
        maxWindMph: Int? = null
    ): ActivityTemplate {
        val template = ActivityTemplate(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            outdoorPreferred = outdoorPreferred,
            durationMinutes = durationMinutes,
            maxTempF = maxTempF,
            minTempF = minTempF,
            avoidRain = avoidRain,
            maxWindMph = maxWindMph,
            isBuiltIn = false,
            sortOrder = CUSTOM_SORT_BASE + (System.currentTimeMillis() % 100_000).toInt(),
            createdAt = DateUtil.now()
        )
        dao.upsert(template.toEntity())
        return template
    }

    /** Persist an edit (rename or any requirement change) to an existing template. */
    suspend fun update(template: ActivityTemplate) = dao.upsert(template.toEntity())

    suspend fun delete(template: ActivityTemplate) = dao.delete(template.toEntity())

    /** Seed the built-in activities exactly once (idempotent — no-op if any rows exist). */
    suspend fun ensureDefaults() {
        if (dao.count() > 0) return
        val now = DateUtil.now()
        DEFAULTS.forEachIndexed { index, d ->
            dao.upsert(
                ActivityTemplate(
                    id = "builtin-${d.name.lowercase().replace(' ', '-')}",
                    name = d.name,
                    outdoorPreferred = true,
                    durationMinutes = d.durationMinutes,
                    maxTempF = d.maxTempF,
                    minTempF = d.minTempF,
                    avoidRain = d.avoidRain,
                    maxWindMph = d.maxWindMph,
                    isBuiltIn = true,
                    sortOrder = index,
                    createdAt = now
                ).toEntity()
            )
        }
    }

    private data class Default(
        val name: String,
        val durationMinutes: Int?,
        val maxTempF: Int?,
        val minTempF: Int?,
        val avoidRain: Boolean,
        val maxWindMph: Int?
    )

    companion object {
        private const val CUSTOM_SORT_BASE = 1_000

        // Reasonable starting points; users are expected to tune these to their own comfort.
        private val DEFAULTS = listOf(
            Default("Outdoor Play", 60, 95, 40, avoidRain = true, maxWindMph = 20),
            Default("Mowing", 90, 90, 45, avoidRain = true, maxWindMph = 20),
            Default("Gardening", 60, 92, 45, avoidRain = true, maxWindMph = 20),
            Default("Walking", 45, 95, 30, avoidRain = true, maxWindMph = 25),
            Default("Bike Ride", 60, 92, 40, avoidRain = true, maxWindMph = 15),
            Default("Car Washing", 45, 100, 50, avoidRain = true, maxWindMph = 20),
            Default("Photography", 90, 95, 25, avoidRain = false, maxWindMph = 25),
            Default("Camping", null, 95, 35, avoidRain = true, maxWindMph = 20)
        )
    }
}
