package com.lifeops.app.data.repository

import com.lifeops.app.data.db.dao.GameResourceDao
import com.lifeops.app.data.db.dao.GameResourceMappingDao
import com.lifeops.app.data.db.dao.MilestoneDao
import com.lifeops.app.data.db.dao.ResourceTransactionDao
import com.lifeops.app.data.db.entities.ResourceTransactionEntity
import com.lifeops.app.data.model.Milestone
import com.lifeops.app.util.DateUtil
import com.lifeops.app.util.toEntity
import com.lifeops.app.util.toModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import kotlin.math.roundToInt

/**
 * The one thing ViewModels talk to for Milestones. Mirrors CounterRepository / PersonRepository in
 * shape: plain models out, entity plumbing hidden.
 *
 * Milestones are the deliberate exception to the "only closed weeks emit resources" rule: they are
 * rare, once-in-a-lifetime accomplishments recorded after the fact, so their points are minted the
 * moment the milestone is created rather than deferred to week-close. The mint reuses the exact
 * aspect → game-resource path week-close uses ([GameResourceMappingDao.getByAspect] scaled by each
 * mapping's weight, then [GameResourceDao.addValue]), and records a "milestone" resource transaction
 * so the grant is visible in the Resources ledger. A milestone with no aspect (or an aspect with no
 * mappings) simply records its point value without minting anything.
 *
 * Like week-close, the mint is permanent: deleting a milestone removes the record but does not claw
 * back already-granted (and possibly already-spent) resources.
 */
class MilestoneRepository(
    private val milestoneDao: MilestoneDao,
    private val gameResourceMappingDao: GameResourceMappingDao,
    private val gameResourceDao: GameResourceDao,
    private val resourceTransactionDao: ResourceTransactionDao
) {
    fun observeAll(): Flow<List<Milestone>> =
        milestoneDao.observeAll().map { list -> list.map { it.toModel() } }

    fun observeForAspect(aspectId: String): Flow<List<Milestone>> =
        milestoneDao.observeForAspect(aspectId).map { list -> list.map { it.toModel() } }

    fun observeForPerson(personId: String): Flow<List<Milestone>> =
        milestoneDao.observeForPerson(personId).map { list -> list.map { it.toModel() } }

    /**
     * Record a milestone and immediately grant its points. Returns the persisted milestone.
     * [achievedAt] falls back to now when blank; [points] is floored at zero.
     */
    suspend fun create(
        title: String,
        description: String?,
        points: Int,
        aspectId: String?,
        personId: String?,
        achievedAt: String?
    ): Milestone {
        val now = DateUtil.now()
        val milestone = Milestone(
            id = UUID.randomUUID().toString(),
            title = title.trim(),
            description = description?.trim()?.takeIf { it.isNotBlank() },
            points = points.coerceAtLeast(0),
            aspectId = aspectId,
            personId = personId,
            achievedAt = achievedAt?.takeIf { it.isNotBlank() } ?: now,
            createdAt = now
        )
        milestoneDao.upsert(milestone.toEntity())
        grantPoints(milestone)
        return milestone
    }

    suspend fun delete(milestone: Milestone) = milestoneDao.delete(milestone.toEntity())

    /** Mint [Milestone.points] into the attached aspect's mapped resources, right now. */
    private suspend fun grantPoints(milestone: Milestone) {
        val aspectId = milestone.aspectId ?: return
        if (milestone.points <= 0) return
        val now = DateUtil.now()
        gameResourceMappingDao.getByAspect(aspectId).forEach { mapping ->
            val amount = (milestone.points * mapping.weight).roundToInt()
            if (amount > 0) {
                gameResourceDao.addValue(mapping.gameResourceId, amount)
                resourceTransactionDao.insert(
                    ResourceTransactionEntity(
                        id = UUID.randomUUID().toString(),
                        resourceId = mapping.gameResourceId,
                        amount = amount,
                        type = "milestone",
                        note = "Milestone: ${milestone.title}",
                        createdAt = now
                    )
                )
            }
        }
    }
}
