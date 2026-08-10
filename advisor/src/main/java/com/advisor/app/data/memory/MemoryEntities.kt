package com.advisor.app.data.memory

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

/**
 * One long-term memory row. The recall priors — [salience] and [pinned] — and the lifecycle columns
 * ([lastRecalledAt], [recallCount]) are first-class so recall can be tuned and the recall loop can be
 * closed. Tags live in a separate table ([MemoryTagEntity]) rather than a JSON column so the store
 * has real, indexed tag query power — the "tons of tagging potential" the long-term memory is for.
 */
@Entity(
    tableName = "advisor_memories",
    indices = [Index("kind"), Index("salience"), Index("pinned")]
)
data class MemoryEntity(
    @PrimaryKey val id: String,
    val content: String,
    val kind: String,
    val source: String,
    val salience: Int,
    val pinned: Boolean,
    val archived: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val lastRecalledAt: Long?,
    val recallCount: Int
)

/**
 * A single tag on a memory. Normalized (one row per tag) with a composite key and an index on [tag],
 * so "every memory tagged X", "all tags with counts", and multi-tag recall are cheap, indexed
 * queries. Cascades on delete so removing a memory drops its tags.
 */
@Entity(
    tableName = "advisor_memory_tags",
    primaryKeys = ["memoryId", "tag"],
    foreignKeys = [
        ForeignKey(
            entity = MemoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["memoryId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("tag"), Index("memoryId")]
)
data class MemoryTagEntity(
    val memoryId: String,
    val tag: String
)

/** A memory joined with its tags — the shape reads return so callers get the whole memory at once. */
data class MemoryWithTags(
    @Embedded val memory: MemoryEntity,
    @Relation(parentColumn = "id", entityColumn = "memoryId")
    val tags: List<MemoryTagEntity>
)

/** A tag and how many memories carry it — powers the tag filter/facet UI. */
data class TagCount(
    val tag: String,
    val count: Int
)
