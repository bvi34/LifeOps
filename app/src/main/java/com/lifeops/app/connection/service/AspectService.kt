package com.lifeops.app.connection.service

import com.lifeops.app.data.model.Aspect
import com.lifeops.app.data.model.Category
import com.lifeops.app.data.repository.AspectRepository

/**
 * Use-case layer for aspects (life domains) and their categories. Creation is find-or-create so a
 * connection caller can't fork a second aspect/category of the same name — matching import and UI
 * behaviour.
 */
class AspectService(private val aspectRepository: AspectRepository) {

    suspend fun createAspect(name: String, color: String? = null, icon: String? = null): Aspect {
        require(name.isNotBlank()) { "Aspect name must not be blank" }
        return aspectRepository.findOrCreateAspect(
            name = name.trim(),
            color = color,
            icon = icon ?: "star"
        )
    }

    /** Rename an aspect. Null if no aspect has [id]. */
    suspend fun renameAspect(id: String, name: String): Aspect? {
        require(name.isNotBlank()) { "Aspect name must not be blank" }
        val current = aspectRepository.getAllAspectsSync().firstOrNull { it.id == id } ?: return null
        val updated = current.copy(name = name.trim())
        aspectRepository.updateAspect(updated)
        return updated
    }

    suspend fun setAspectArchived(id: String, archived: Boolean) {
        aspectRepository.setAspectArchived(id, archived)
    }

    suspend fun createCategory(aspectId: String, name: String): Category {
        require(name.isNotBlank()) { "Category name must not be blank" }
        return aspectRepository.findOrCreateCategory(aspectId, name.trim())
    }

    suspend fun setCategoryArchived(id: String, archived: Boolean) {
        aspectRepository.setCategoryArchived(id, archived)
    }
}
