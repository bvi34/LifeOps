package com.advisor.app.data.memory

import com.advisor.app.logic.MemoryRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * The write/read face of the long-term memory store, translating between the persisted
 * [MemoryEntity]/[MemoryTagEntity] rows and the pure [MemoryRecord] the RAG pipeline reasons over.
 * Tags are normalised here — lower-cased, trimmed, de-duplicated — so recall and the facet counts
 * stay consistent no matter how they were typed.
 */
class MemoryRepository(private val dao: MemoryDao) {

    fun observeMemories(): Flow<List<MemoryRecord>> =
        dao.observeMemories().map { rows -> rows.map { it.toRecord() } }

    fun observeByTags(tags: List<String>): Flow<List<MemoryRecord>> =
        dao.observeByTags(tags.map { it.normalizeTag() }).map { rows -> rows.map { it.toRecord() } }

    fun observeTagCounts(): Flow<List<TagCount>> = dao.observeTagCounts()

    /** The full memory set the recall ranker scores against. */
    suspend fun allRecords(): List<MemoryRecord> = dao.getAll().map { it.toRecord() }

    /** Store a new memory. Returns its id. */
    suspend fun remember(
        content: String,
        tags: List<String> = emptyList(),
        kind: String = "note",
        salience: Int = 50,
        pinned: Boolean = false,
        source: String = "user"
    ): String {
        val now = System.currentTimeMillis()
        val id = "mem-" + UUID.randomUUID()
        dao.insertMemory(
            MemoryEntity(
                id = id,
                content = content.trim(),
                kind = kind,
                source = source,
                salience = salience.coerceIn(0, 100),
                pinned = pinned,
                archived = false,
                createdAt = now,
                updatedAt = now,
                lastRecalledAt = null,
                recallCount = 0
            )
        )
        writeTags(id, tags)
        return id
    }

    suspend fun forget(id: String) = dao.deleteMemory(id)

    suspend fun setPinned(id: String, pinned: Boolean) =
        dao.setPinned(id, pinned, System.currentTimeMillis())

    /** Close the recall loop: bump recall stats on the memories that were surfaced. */
    suspend fun markRecalled(ids: List<String>) {
        val now = System.currentTimeMillis()
        for (id in ids) dao.markRecalled(id, now)
    }

    private suspend fun writeTags(memoryId: String, tags: List<String>) {
        dao.clearTags(memoryId)
        val clean = tags.map { it.normalizeTag() }.filter { it.isNotBlank() }.distinct()
        if (clean.isNotEmpty()) dao.insertTags(clean.map { MemoryTagEntity(memoryId, it) })
    }

    private fun String.normalizeTag(): String = trim().lowercase()

    private fun MemoryWithTags.toRecord(): MemoryRecord = MemoryRecord(
        id = memory.id,
        content = memory.content,
        kind = memory.kind,
        tags = tags.map { it.tag },
        salience = memory.salience,
        pinned = memory.pinned,
        source = memory.source,
        createdAt = memory.createdAt,
        updatedAt = memory.updatedAt,
        lastRecalledAt = memory.lastRecalledAt,
        recallCount = memory.recallCount
    )
}
