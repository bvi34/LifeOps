package com.lifeops.app.data.repository

import com.lifeops.app.data.db.dao.AspectDao
import com.lifeops.app.data.db.dao.CategoryDao
import com.lifeops.app.data.model.Aspect
import com.lifeops.app.data.model.Category
import com.lifeops.app.util.nextAspectColor
import com.lifeops.app.util.toEntity
import com.lifeops.app.util.toModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class AspectRepository(
    private val aspectDao: AspectDao,
    private val categoryDao: CategoryDao
) {
    fun observeAspects(): Flow<List<Aspect>> =
        aspectDao.observeActive().map { list -> list.map { it.toModel() } }

    fun observeAllAspects(): Flow<List<Aspect>> =
        aspectDao.observeAll().map { list -> list.map { it.toModel() } }

    fun observeCategories(): Flow<List<Category>> =
        categoryDao.observeAll().map { list -> list.map { it.toModel() } }

    fun observeCategoriesForAspect(aspectId: String): Flow<List<Category>> =
        categoryDao.observeByAspect(aspectId).map { list -> list.map { it.toModel() } }

    suspend fun upsertAspect(aspect: Aspect) = aspectDao.upsert(aspect.toEntity())

    suspend fun upsertCategory(category: Category) = categoryDao.upsert(category.toEntity())

    suspend fun updateCategory(category: Category) = categoryDao.update(category.toEntity())

    suspend fun setAspectArchived(id: String, archived: Boolean) = aspectDao.setArchived(id, archived)

    suspend fun setCategoryArchived(id: String, archived: Boolean) = categoryDao.setArchived(id, archived)

    suspend fun findAspectByName(name: String): Aspect? =
        aspectDao.findByName(name)?.toModel()

    suspend fun findCategoryByName(aspectId: String, name: String): Category? =
        categoryDao.findByAspectAndName(aspectId, name)?.toModel()

    suspend fun findOrCreateAspect(name: String, color: String? = null, icon: String = "star"): Aspect {
        val existing = aspectDao.findByName(name)
        if (existing != null) return existing.toModel()
        val resolvedColor = color ?: suggestNextColor()
        val new = Aspect(java.util.UUID.randomUUID().toString(), name, resolvedColor, icon)
        aspectDao.upsert(new.toEntity())
        return new
    }

    /** Suggests the next aspect color, skipping any color already in use by an existing aspect. */
    suspend fun suggestNextColor(): String =
        nextAspectColor(aspectDao.getAllSync().map { it.color })

    suspend fun findOrCreateCategory(aspectId: String, name: String): Category {
        val existing = categoryDao.findByAspectAndName(aspectId, name)
        if (existing != null) return existing.toModel()
        val new = Category(java.util.UUID.randomUUID().toString(), aspectId, name)
        categoryDao.upsert(new.toEntity())
        return new
    }

    suspend fun getAllAspectsSync(): List<Aspect> = aspectDao.getAllSync().map { it.toModel() }
}
