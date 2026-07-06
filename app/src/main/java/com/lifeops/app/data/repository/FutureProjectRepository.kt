package com.lifeops.app.data.repository

import com.lifeops.app.data.db.dao.FutureProjectDao
import com.lifeops.app.data.model.FutureProject
import com.lifeops.app.util.DateUtil
import com.lifeops.app.util.toEntity
import com.lifeops.app.util.toModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

class FutureProjectRepository(private val futureProjectDao: FutureProjectDao) {
    fun observeAll(): Flow<List<FutureProject>> = futureProjectDao.observeAll().map { list -> list.map { it.toModel() } }
    fun observeById(id: String): Flow<FutureProject?> = futureProjectDao.observeById(id).map { it?.toModel() }

    suspend fun create(title: String): FutureProject {
        val now = DateUtil.now()
        val project = FutureProject(UUID.randomUUID().toString(), title, "", now, now)
        futureProjectDao.upsert(project.toEntity())
        return project
    }

    suspend fun save(project: FutureProject, title: String, content: String) {
        futureProjectDao.upsert(project.copy(title = title, content = content, updatedAt = DateUtil.now()).toEntity())
    }

    suspend fun delete(id: String) = futureProjectDao.delete(id)
}
